"""Translate provider-specific OpenAI streams into the platform's normalized SSE events."""

import json
from collections.abc import AsyncIterator

import httpx

from app.config import Settings
from app.models import ChatRequest, ModelEndpoint


class ChatConfigurationError(RuntimeError):
    """Raised when a registered provider has no usable local credential."""

    pass


class UpstreamChatError(RuntimeError):
    """Raised when the remote or local inference endpoint rejects a request."""

    pass


class ChatGateway:
    """Call OpenAI-compatible model endpoints without exposing credentials to Java or browsers."""

    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        """Create a gateway; an optional transport allows deterministic HTTP unit tests."""
        self._settings = settings
        self._transport = transport

    async def stream(
        self,
        request: ChatRequest,
        model: ModelEndpoint,
    ) -> AsyncIterator[dict]:
        """Yield normalized delta, usage, and completion events from one upstream chat stream."""
        api_key = self._api_key_for(model)
        payload = {
            "model": model.model_name,
            "messages": [message.model_dump() for message in request.messages],
            "temperature": request.temperature,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if request.max_tokens is not None:
            payload["max_tokens"] = request.max_tokens

        url = f"{str(model.base_url).rstrip('/')}/chat/completions"
        timeout = httpx.Timeout(self._settings.chat_timeout_seconds, connect=15.0)
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

        async with httpx.AsyncClient(timeout=timeout, transport=self._transport) as client:
            async with client.stream("POST", url, headers=headers, json=payload) as response:
                if response.status_code >= 400:
                    detail = (await response.aread()).decode("utf-8", errors="replace")
                    raise UpstreamChatError(f"Upstream returned {response.status_code}: {detail[:500]}")

                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if not data or data == "[DONE]":
                        continue

                    chunk = json.loads(data)
                    choices = chunk.get("choices") or []
                    if choices:
                        content = choices[0].get("delta", {}).get("content")
                        if content:
                            yield {"type": "delta", "content": content}

                    usage = chunk.get("usage")
                    if usage:
                        yield {
                            "type": "usage",
                            "prompt_tokens": usage.get("prompt_tokens", 0),
                            "completion_tokens": usage.get("completion_tokens", 0),
                            "total_tokens": usage.get("total_tokens", 0),
                        }

        yield {"type": "done"}

    def _api_key_for(self, model: ModelEndpoint) -> str:
        """Resolve a provider credential while keeping secret values inside the AI process."""
        if model.provider == "deepseek" and self._settings.deepseek_api_key:
            return self._settings.deepseek_api_key.get_secret_value()
        raise ChatConfigurationError(f"API key is not configured for provider: {model.provider}")
