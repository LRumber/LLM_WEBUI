"""Speech-to-text gateway for OpenAI-compatible audio transcription endpoints."""

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import httpx

from app.models import ModelEndpoint


class SpeechTranscriptionError(RuntimeError):
    """Raised when a registered speech model cannot transcribe the supplied audio."""

    pass


class SpeechGateway:
    """Call /audio/transcriptions without exposing credentials to the browser."""

    def __init__(self, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._transport = transport

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
        audio_content, audio_format = self._normalize_audio(content, filename, media_type)
        data = {
            "model": model.model_name,
            "language": "zh",
            "response_format": "json",
        }
        files = {
            "file": (
                self._normalized_filename(filename, audio_format),
                audio_content,
                self._media_type_for(audio_format),
            )
        }
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(120.0, connect=10.0), transport=self._transport
            ) as client:
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
        return self._clean_transcript(text)

    def _normalize_audio(self, content: bytes, filename: str, media_type: str) -> tuple[bytes, str]:
        """Prefer WAV for ASR while allowing local development without ffmpeg."""
        audio_format = self._audio_format(filename, media_type)
        if audio_format != "webm":
            return content, audio_format
        converted = self._convert_webm_to_wav(content)
        return (converted, "wav") if converted else (content, "webm")

    def _convert_webm_to_wav(self, content: bytes) -> bytes | None:
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            return None
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "recording.webm"
            target = Path(directory) / "recording.wav"
            source.write_bytes(content)
            result = subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-i",
                    str(source),
                    "-ac",
                    "1",
                    "-ar",
                    "16000",
                    str(target),
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                check=False,
            )
            if result.returncode != 0 or not target.exists():
                return None
            return target.read_bytes()

    def _audio_format(self, filename: str, media_type: str) -> str:
        suffix = Path(filename).suffix.lower().lstrip(".")
        if suffix in {"wav", "mp3", "flac", "m4a", "ogg", "webm"}:
            return suffix
        normalized = media_type.lower().split(";")[0].strip()
        return {
            "audio/wav": "wav",
            "audio/x-wav": "wav",
            "audio/mpeg": "mp3",
            "audio/mp3": "mp3",
            "audio/flac": "flac",
            "audio/mp4": "m4a",
            "audio/ogg": "ogg",
            "audio/webm": "webm",
            "video/webm": "webm",
        }.get(normalized, "wav")

    def _normalized_filename(self, filename: str, audio_format: str) -> str:
        stem = Path(filename).stem or "recording"
        return f"{stem}.{audio_format}"

    def _media_type_for(self, audio_format: str) -> str:
        return {
            "wav": "audio/wav",
            "mp3": "audio/mpeg",
            "flac": "audio/flac",
            "m4a": "audio/mp4",
            "ogg": "audio/ogg",
            "webm": "audio/webm",
        }.get(audio_format, "application/octet-stream")

    def _clean_transcript(self, text: str) -> str:
        """Remove Qwen3-ASR metadata prefixes while preserving the spoken text."""
        cleaned = text.strip()
        marker = "<asr_text>"
        if marker in cleaned:
            cleaned = cleaned.split(marker, 1)[1].strip()
        if cleaned.lower().startswith("language "):
            parts = cleaned.split(maxsplit=2)
            if len(parts) == 3:
                cleaned = parts[2].strip()
        return cleaned

    def _api_key_for(self, model: ModelEndpoint) -> str:
        if model.api_key_env:
            configured = os.getenv(model.api_key_env)
            if configured:
                return configured
        if model.api_key:
            return model.api_key.get_secret_value()
        return "local-inference"
