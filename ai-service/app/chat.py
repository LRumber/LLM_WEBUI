"""Translate provider-specific OpenAI streams into the platform's normalized SSE events."""

import json
import asyncio
import os
import time
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


class ModelBusyError(RuntimeError):
    """Raised when a model has exhausted its configured concurrent request slots."""

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
        self._semaphores: dict[str, tuple[int, asyncio.Semaphore]] = {}

    async def stream(
        self,
        request: ChatRequest,
        model: ModelEndpoint,
    ) -> AsyncIterator[dict]:
        """Yield normalized delta, usage, and completion events from one upstream chat stream."""
        semaphore = self._semaphore_for(model)
        try:
            await asyncio.wait_for(semaphore.acquire(), timeout=1.0)
        except TimeoutError as exc:
            raise ModelBusyError(f"Model concurrency limit reached: {model.key}") from exc

        try:
            async for event in self._stream_acquired(request, model):
                yield event
        finally:
            semaphore.release()

    async def _stream_acquired(
        self,
        request: ChatRequest,
        model: ModelEndpoint,
    ) -> AsyncIterator[dict]:
        """Call an upstream endpoint after the per-model concurrency slot is acquired."""
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

    async def health(self, model: ModelEndpoint) -> dict:
        """Probe the OpenAI-compatible model listing endpoint and normalize latency and errors."""
        started = time.monotonic()
        url = f"{str(model.base_url).rstrip('/')}/models"
        headers = {"Authorization": f"Bearer {self._api_key_for(model)}"}
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(8.0, connect=3.0), transport=self._transport
            ) as client:
                response = await client.get(url, headers=headers)
                response.raise_for_status()
            status = "UP"
            detail = None
        except Exception as exc:
            status = "DOWN"
            detail = str(exc)[:300]
        return {
            "model": model.key,
            "status": status,
            "latency_ms": int((time.monotonic() - started) * 1000),
            "detail": detail,
        }

    def _api_key_for(self, model: ModelEndpoint) -> str:
        """Resolve a provider credential while keeping secret values inside the AI process."""
        if model.api_key_env:
            configured = os.getenv(model.api_key_env)
            if configured:
                return configured
        if model.provider == "deepseek" and self._settings.deepseek_api_key:
            return self._settings.deepseek_api_key.get_secret_value()
        if model.engine.value in {"vllm", "sglang", "ollama", "llama.cpp"}:
            return "local-inference"
        raise ChatConfigurationError(f"API key is not configured for provider: {model.provider}")

    def _semaphore_for(self, model: ModelEndpoint) -> asyncio.Semaphore:
        """Create or replace a semaphore when an administrator changes the model limit."""
        current = self._semaphores.get(model.key)
        if current is None or current[0] != model.max_concurrency:
            current = (model.max_concurrency, asyncio.Semaphore(model.max_concurrency))
            self._semaphores[model.key] = current
        return current[1]
