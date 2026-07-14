"""Proxy registered OpenAI-compatible embedding services without exposing credentials."""

import os

import httpx

from app.config import Settings
from app.models import EmbeddingItem, EmbeddingRequest, EmbeddingResponse, ModelEndpoint, TokenUsage


class EmbeddingError(RuntimeError):
    """Raised when a configured embedding endpoint cannot produce a valid vector batch."""


class EmbeddingGateway:
    """Calls an administrator-configured embedding API suitable for offline LAN deployment."""

    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    async def embed(self, request: EmbeddingRequest, model: ModelEndpoint) -> EmbeddingResponse:
        values = [request.input] if isinstance(request.input, str) else request.input
        if not values or any(not value.strip() for value in values):
            raise EmbeddingError("Embedding input must contain non-empty text")
        url = f"{str(model.base_url).rstrip('/')}/embeddings"
        headers = self._headers_for(model)
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(self._settings.chat_timeout_seconds, connect=15.0),
            transport=self._transport,
        ) as client:
            response = await client.post(
                url, headers=headers, json={"model": model.model_name, "input": values}
            )
        if response.status_code >= 400:
            raise EmbeddingError(
                f"Embedding endpoint returned {response.status_code}: {response.text[:500]}"
            )
        payload = response.json()
        ordered = sorted(payload.get("data", []), key=lambda item: item.get("index", 0))
        if len(ordered) != len(values) or any(not item.get("embedding") for item in ordered):
            raise EmbeddingError("Embedding endpoint returned an incomplete vector batch")
        return EmbeddingResponse(
            data=[
                EmbeddingItem(index=index, embedding=item["embedding"])
                for index, item in enumerate(ordered)
            ],
            model=model.key,
            usage=TokenUsage.model_validate(payload.get("usage") or {}),
        )

    def _headers_for(self, model: ModelEndpoint) -> dict[str, str]:
        api_key = self._api_key_for(model)
        return {"Authorization": f"Bearer {api_key}"} if api_key else {}

    def _api_key_for(self, model: ModelEndpoint) -> str | None:
        """Resolve encrypted or environment credentials entirely inside the AI process."""
        if model.api_key_env and (configured := os.getenv(model.api_key_env)):
            return configured
        if model.api_key:
            return model.api_key.get_secret_value()
        if model.provider == "deepseek" and self._settings.deepseek_api_key:
            return self._settings.deepseek_api_key.get_secret_value()
        if model.engine.value in {"vllm", "sglang", "ollama", "llama.cpp"}:
            return "local-inference"
        return None
