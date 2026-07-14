"""Proxy registered OpenAI-compatible rerank services without exposing credentials."""

import os

import httpx

from app.config import Settings
from app.models import ModelEndpoint, RerankRequest, RerankResponse, RerankResult


class RerankError(RuntimeError):
    """Raised when a configured rerank endpoint cannot score the submitted documents."""


class RerankGateway:
    """Calls an administrator-configured rerank API suitable for offline LAN deployment."""

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    async def rerank(self, request: RerankRequest, model: ModelEndpoint) -> RerankResponse:
        if any(not document.strip() for document in request.documents):
            raise RerankError("Rerank documents must contain non-empty text")
        url = f"{str(model.base_url).rstrip('/')}/rerank"
        headers = self._headers_for(model)
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(self._settings.chat_timeout_seconds, connect=15.0),
            transport=self._transport,
        ) as client:
            response = await client.post(
                url,
                headers=headers,
                json={
                    "model": model.model_name,
                    "query": request.query,
                    "documents": request.documents,
                },
            )
        if response.status_code >= 400:
            raise RerankError(f"Rerank endpoint returned {response.status_code}: {response.text[:500]}")
        payload = response.json()
        results = payload.get("results") or payload.get("data") or []
        normalized = [
            RerankResult(
                index=int(item["index"]),
                relevance_score=float(item.get("relevance_score", item.get("score", 0))),
            )
            for item in results
            if "index" in item and 0 <= int(item["index"]) < len(request.documents)
        ]
        if not normalized:
            raise RerankError("Rerank endpoint returned no usable scores")
        return RerankResponse(model=model.key, results=normalized)

    def _headers_for(self, model: ModelEndpoint) -> dict[str, str]:
        api_key = self._api_key_for(model)
        return {"Authorization": f"Bearer {api_key}"} if api_key else {}

    def _api_key_for(self, model: ModelEndpoint) -> str | None:
        """Resolve encrypted or environment credentials entirely inside the AI process."""
        if model.api_key_env and (configured := os.getenv(model.api_key_env)):
            return configured
        if model.api_key:
            return model.api_key.get_secret_value()
        if model.engine.value in {"vllm", "sglang", "ollama", "llama.cpp"}:
            return "local-inference"
        return None
