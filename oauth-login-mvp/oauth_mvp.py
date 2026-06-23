#!/usr/bin/env python3
"""Standalone OAuth2 login probe for the SITP identity gateway."""

from __future__ import annotations

import argparse
import html
import json
import secrets
import sys
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen


APP_DIR = Path(__file__).resolve().parent
CONFIG_PATH = APP_DIR / "config.json"
SESSION_COOKIE = "sitp_oauth_mvp"
SESSIONS: dict[str, dict[str, Any]] = {}


class IdentityResponseError(RuntimeError):
    def __init__(self, message: str, status: int | None = None, content_type: str = "", body: str = "") -> None:
        super().__init__(message)
        self.status = status
        self.content_type = content_type
        self.body = body


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(
            f"Missing {path.name}. Copy config.example.json to config.json and fill in the client credentials."
        )
    config = json.loads(path.read_text(encoding="utf-8"))
    required = ("server", "client_id", "client_secret", "redirect_uri")
    missing = [key for key in required if not str(config.get(key, "")).strip()]
    if missing:
        raise ValueError(f"Missing configuration: {', '.join(missing)}")
    if config["client_id"] == "replace-me" or config["client_secret"] == "replace-me":
        raise ValueError("Replace the OAuth2 client_id and client_secret in config.json")
    return config


def endpoint(config: dict[str, Any], key: str, fallback: str) -> str:
    return str(config["server"]).rstrip("/") + str(config.get(key, fallback))


def request_identity(request: Request) -> tuple[dict[str, Any], str, str, int]:
    try:
        with urlopen(request, timeout=15) as response:
            status = response.status
            charset = response.headers.get_content_charset() or "utf-8"
            content_type = response.headers.get("Content-Type", "")
            payload = response.read().decode(charset)
    except HTTPError as exc:
        content_type = exc.headers.get("Content-Type", "")
        body = exc.read().decode("utf-8", errors="replace")
        raise IdentityResponseError(
            f"Identity gateway returned HTTP {exc.code}. Content-Type: {content_type or 'unknown'}; Body preview: {body[:500]}",
            exc.code,
            content_type,
            body,
        ) from exc
    except URLError as exc:
        raise IdentityResponseError(f"Cannot connect to identity gateway: {exc.reason}") from exc

    stripped = payload.strip()
    if not stripped:
        raise IdentityResponseError(
            f"Identity gateway returned an empty response. HTTP {status}; Content-Type: {content_type or 'unknown'}",
            status,
            content_type,
            payload,
        )

    try:
        result = json.loads(stripped)
        if isinstance(result, dict):
            return result, payload, content_type, status
        raise IdentityResponseError(
            "Identity gateway returned an unexpected JSON value. "
            f"HTTP {status}; Content-Type: {content_type or 'unknown'}; Body preview: {payload[:500]}",
            status,
            content_type,
            payload,
        )
    except json.JSONDecodeError:
        pass

    parsed = parse_qs(stripped, keep_blank_values=True)
    if parsed:
        form_result = {key: values[-1] if values else "" for key, values in parsed.items()}
        if form_result:
            return form_result, payload, content_type, status

    if "=" not in stripped and "<" not in stripped[:20]:
        return {"access_token": stripped}, payload, content_type, status

    raise IdentityResponseError(
        "Identity gateway returned non-JSON data. "
        f"HTTP {status}; Content-Type: {content_type or 'unknown'}; Body preview: {payload[:500]}",
        status,
        content_type,
        payload,
    )


def redact_url(url: str) -> str:
    parsed = urlparse(url)
    params = parse_qs(parsed.query, keep_blank_values=True)
    for key in ("client_secret", "access_token", "token"):
        if key in params:
            params[key] = ["***"]
    redacted_query = urlencode({key: values[-1] if values else "" for key, values in params.items()})
    return parsed._replace(query=redacted_query).geturl()


def token_requests(config: dict[str, Any], code: str) -> list[tuple[str, Request]]:
    token_endpoint = endpoint(config, "access_token_path", "/uias/oauth/access_token")
    base_params = {
        "client_id": config["client_id"],
        "client_secret": config["client_secret"],
        "code": code,
    }
    oauth_params = {
        **base_params,
        "grant_type": "authorization_code",
        "redirect_uri": config["redirect_uri"],
    }
    form_body = urlencode(oauth_params).encode("utf-8")
    return [
        ("POST query params without body", Request(token_endpoint + "?" + urlencode(base_params), method="POST")),
        ("POST query params with empty body", Request(token_endpoint + "?" + urlencode(base_params), data=b"", method="POST")),
        ("GET query params", Request(token_endpoint + "?" + urlencode(base_params), method="GET")),
        (
            "POST form body",
            Request(
                token_endpoint,
                data=form_body,
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                method="POST",
            ),
        ),
        ("POST OAuth query params", Request(token_endpoint + "?" + urlencode(oauth_params), data=b"", method="POST")),
    ]


def summarize_attempt(name: str, request: Request, error: Exception) -> str:
    method = request.get_method()
    url = redact_url(request.full_url)
    if isinstance(error, IdentityResponseError):
        return f"{name}: {method} {url} -> {error}"
    return f"{name}: {method} {url} -> {error}"


def exchange_code(config: dict[str, Any], code: str) -> tuple[str, dict[str, Any], dict[str, Any]]:
    attempt_errors: list[str] = []
    token_response: dict[str, Any] | None = None
    for name, request in token_requests(config, code):
        try:
            candidate, _, _, _ = request_identity(request)
        except Exception as exc:
            attempt_errors.append(summarize_attempt(name, request, exc))
            continue
        candidate_token = candidate.get("access_token") or candidate.get("token")
        if isinstance(candidate_token, str) and candidate_token:
            token_response = candidate
            break
        attempt_errors.append(
            f"{name}: {request.get_method()} {redact_url(request.full_url)} -> no access_token. Keys: {', '.join(candidate.keys())}"
        )

    if token_response is None:
        details = "\n".join(attempt_errors) if attempt_errors else "No token request was attempted."
        raise RuntimeError("Cannot exchange authorization code for access_token.\n\nAttempts:\n" + details)

    access_token = token_response.get("access_token")
    if not access_token:
        access_token = token_response.get("token")
    if not isinstance(access_token, str) or not access_token:
        raise RuntimeError(f"Token response does not contain access_token. Keys: {', '.join(token_response.keys())}")

    # The real gateway expects access_token as a query parameter, not a Bearer header.
    userinfo_url = endpoint(config, "userinfo_path", "/uias/oauth/userInfo") + "?" + urlencode(
        {"access_token": access_token}
    )
    userinfo, _, _, _ = request_identity(Request(userinfo_url, method="GET"))
    return access_token, token_response, userinfo


def page(title: str, content: str) -> bytes:
    document = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)}</title>
  <style>
    :root {{ color-scheme: light; font-family: system-ui, "Microsoft YaHei", sans-serif; }}
    body {{ margin: 0; background: #f4f6f8; color: #17202a; }}
    main {{ width: min(920px, calc(100% - 32px)); margin: 48px auto; }}
    section {{ background: white; border: 1px solid #dfe5eb; border-radius: 8px; padding: 28px; }}
    h1 {{ margin: 0 0 10px; font-size: 26px; }}
    p {{ line-height: 1.7; }}
    a.button {{ display: inline-block; padding: 10px 18px; border-radius: 6px; background: #1267a5; color: white; text-decoration: none; }}
    dl {{ display: grid; grid-template-columns: 140px 1fr; gap: 10px 18px; }}
    dt {{ color: #5b6670; }} dd {{ margin: 0; overflow-wrap: anywhere; }}
    pre {{ overflow: auto; padding: 16px; background: #f7f9fb; border: 1px solid #e1e6eb; border-radius: 6px; }}
    .error {{ color: #a62222; white-space: pre-wrap; }}
    .actions {{ display: flex; gap: 12px; margin-top: 22px; }}
    @media (max-width: 600px) {{ dl {{ grid-template-columns: 1fr; gap: 3px; }} dd {{ margin-bottom: 8px; }} }}
  </style>
</head>
<body><main><section>{content}</section></main></body>
</html>"""
    return document.encode("utf-8")


def display_value(userinfo: dict[str, Any], *keys: str) -> str:
    sources = [userinfo]
    if isinstance(userinfo.get("data"), dict):
        sources.insert(0, userinfo["data"])
    for source in sources:
        for key in keys:
            value = source.get(key)
            if value is not None and str(value).strip():
                return html.escape(str(value))
    return "<span style='color:#8a949e'>未返回</span>"


class OAuthHandler(BaseHTTPRequestHandler):
    config: dict[str, Any] = {}

    def log_message(self, fmt: str, *args: Any) -> None:
        # Keep authorization codes and tokens out of access logs.
        clean_path = urlparse(self.path).path
        sys.stderr.write(f"{self.client_address[0]} {self.command} {clean_path}\n")

    def session(self, create: bool = True) -> tuple[str | None, dict[str, Any] | None]:
        cookie = SimpleCookie(self.headers.get("Cookie"))
        session_id = cookie.get(SESSION_COOKIE)
        if session_id and session_id.value in SESSIONS:
            return session_id.value, SESSIONS[session_id.value]
        if not create:
            return None, None
        new_id = secrets.token_urlsafe(32)
        SESSIONS[new_id] = {}
        return new_id, SESSIONS[new_id]

    def send_html(self, body: bytes, status: int = HTTPStatus.OK, session_id: str | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if session_id:
            self.send_header("Set-Cookie", f"{SESSION_COOKIE}={session_id}; Path=/; HttpOnly; SameSite=Lax")
        self.end_headers()
        self.wfile.write(body)

    def redirect(self, location: str, session_id: str | None = None) -> None:
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", location)
        self.send_header("Cache-Control", "no-store")
        if session_id:
            self.send_header("Set-Cookie", f"{SESSION_COOKIE}={session_id}; Path=/; HttpOnly; SameSite=Lax")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/login":
            self.start_login()
        elif parsed.path == "/logout":
            self.logout()
        elif parsed.path in ("/", "/callback") and "code" in query:
            self.callback(query)
        elif parsed.path == "/":
            self.home()
        else:
            self.send_html(page("未找到", "<h1>404</h1><p>页面不存在。</p>"), HTTPStatus.NOT_FOUND)

    def start_login(self) -> None:
        session_id, session = self.session()
        assert session_id and session is not None
        state = secrets.token_urlsafe(24)
        session["state"] = state
        params = {
            "client_id": self.config["client_id"],
            "redirect_uri": self.config["redirect_uri"],
            "response_type": "code",
        }
        # The existing production sample omits state. It can be enabled after gateway compatibility is confirmed.
        if self.config.get("send_state", False):
            params["state"] = state
        authorize_url = endpoint(self.config, "authorize_path", "/uias/oauth/authorize") + "?" + urlencode(params)
        self.redirect(authorize_url, session_id)

    def callback(self, query: dict[str, list[str]]) -> None:
        session_id, session = self.session()
        assert session_id and session is not None
        if "error" in query:
            message = html.escape(query["error"][0])
            self.send_html(page("登录失败", f"<h1>统一登录失败</h1><p class='error'>{message}</p>"), session_id=session_id)
            return
        if self.config.get("send_state", False) and query.get("state", [None])[0] != session.pop("state", None):
            self.send_html(page("登录失败", "<h1>统一登录失败</h1><p class='error'>state 校验失败。</p>"), HTTPStatus.BAD_REQUEST, session_id)
            return
        try:
            access_token, token_response, userinfo = exchange_code(self.config, query["code"][0])
        except RuntimeError as exc:
            self.send_html(page("登录失败", f"<h1>统一登录失败</h1><p class='error'>{html.escape(str(exc))}</p>"), HTTPStatus.BAD_GATEWAY, session_id)
            return
        session.update({"access_token": access_token, "token_response": token_response, "userinfo": userinfo})
        self.redirect("/", session_id)

    def home(self) -> None:
        session_id, session = self.session()
        assert session_id and session is not None
        userinfo = session.get("userinfo")
        if not isinstance(userinfo, dict):
            content = """
              <h1>SITP 统一登录 MVP</h1>
              <p>此页面仅用于验证授权码换取 Token 及用户信息接口，不连接业务数据库。</p>
              <div class="actions"><a class="button" href="/login">统一登录</a></div>
            """
        else:
            raw = html.escape(json.dumps(userinfo, ensure_ascii=False, indent=2))
            content = f"""
              <h1>登录成功</h1>
              <p>已成功从统一身份认证中心取得用户信息。</p>
              <dl>
                <dt>用户标识 idNo</dt><dd>{display_value(userinfo, "idNo", "userId", "id")}</dd>
                <dt>姓名 userName</dt><dd>{display_value(userinfo, "userName", "displayName", "name", "realName")}</dd>
                <dt>工号 userNumber</dt><dd>{display_value(userinfo, "userNumber", "userNo", "employeeNumber")}</dd>
                <dt>账号</dt><dd>{display_value(userinfo, "accountNumber", "account", "username", "loginName")}</dd>
                <dt>邮箱</dt><dd>{display_value(userinfo, "email")}</dd>
                <dt>电话</dt><dd>{display_value(userinfo, "telephone", "phone", "mobile")}</dd>
              </dl>
              <h2>完整 userInfo 响应</h2>
              <pre>{raw}</pre>
              <div class="actions"><a class="button" href="/logout">清除本地会话</a></div>
            """
        self.send_html(page("SITP 统一登录 MVP", content), session_id=session_id)

    def logout(self) -> None:
        session_id, _ = self.session(create=False)
        if session_id:
            SESSIONS.pop(session_id, None)
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", "/")
        self.send_header("Set-Cookie", f"{SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
        self.end_headers()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the standalone SITP OAuth2 login MVP")
    parser.add_argument("--config", type=Path, default=CONFIG_PATH)
    args = parser.parse_args()
    config = load_config(args.config.resolve())
    OAuthHandler.config = config
    address = (str(config.get("listen_host", "0.0.0.0")), int(config.get("listen_port", 8505)))
    server = ThreadingHTTPServer(address, OAuthHandler)
    print(f"OAuth2 MVP listening on http://{address[0]}:{address[1]}")
    print(f"Registered redirect URI: {config['redirect_uri']}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
