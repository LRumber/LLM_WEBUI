"""Speech-to-text gateway for local or LAN OpenAI-compatible audio endpoints."""

import os

import httpx

from app.models import ModelEndpoint


class SpeechTranscriptionError(RuntimeError):
    """Raised when a registered speech model cannot transcribe the supplied audio."""

    pass


class SpeechGateway:
    """Call an OpenAI-compatible transcription endpoint without browser-visible secrets."""

    async def transcribe(
        self,
        model: ModelEndpoint,
        content: bytes,
        filename: str,
        media_type: str,
    ) -> str:
        api_key = self._api_key_for(model)
        url = f"{str(model.base_url).rstrip('/')}/audio/transcriptions"
        headers = {"Authorization": f"Bearer {api_key}"}
        data = {"model": model.model_name}
        files = {"file": (filename, content, media_type)}
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0)) as client:
                response = await client.post(url, headers=headers, data=data, files=files)
            if response.status_code >= 400:
                raise SpeechTranscriptionError(
                    f"Speech model returned {response.status_code}: {response.text[:500]}"
                )
            payload = response.json()
        except SpeechTranscriptionError:
            raise
        except Exception as exc:
            raise SpeechTranscriptionError(f"Speech transcription failed: {str(exc)[:300]}") from exc

        text = payload.get("text") or payload.get("transcript")
        if not isinstance(text, str) or not text.strip():
            raise SpeechTranscriptionError("Speech model response did not contain text")
        return text.strip()

    def _api_key_for(self, model: ModelEndpoint) -> str:
        if model.api_key_env:
            configured = os.getenv(model.api_key_env)
            if configured:
                return configured
        if model.api_key:
            return model.api_key.get_secret_value()
        if model.engine.value in {"vllm", "sglang", "ollama", "llama.cpp"}:
            return "local-inference"
        return "local-inference"
