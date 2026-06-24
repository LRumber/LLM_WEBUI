"""Contract tests for OpenAI-compatible speech transcription requests."""

import httpx
import pytest

from app.models import ModelCapability, ModelEndpoint
from app.speech import SpeechGateway


@pytest.mark.asyncio
async def test_speech_gateway_calls_audio_transcriptions():
    """Send Qwen3-ASR audio through the OpenAI transcription endpoint."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/audio/transcriptions"
        assert request.headers["Authorization"] == "Bearer local-inference"
        body = request.content
        assert b'name="model"\r\n\r\nQwen/Qwen3-ASR-1.7B' in body
        assert b'name="language"\r\n\r\nzh' in body
        assert b'name="file"; filename="recording.wav"' in body
        assert b"fake wav" in body
        return httpx.Response(200, json={"text": "language Chinese<asr_text>你好，这是语音识别测试。"})

    gateway = SpeechGateway(transport=httpx.MockTransport(handler))
    model = ModelEndpoint(
        key="qwen-asr",
        display_name="Qwen ASR",
        provider="openai-compatible",
        base_url="http://qwen-asr.local/v1",
        model_name="Qwen/Qwen3-ASR-1.7B",
        category="speech",
        capabilities={ModelCapability.SPEECH_TO_TEXT},
        frontend_visible=False,
    )

    text = await gateway.transcribe(model, b"fake wav", "recording.wav", "audio/wav")

    assert text == "你好，这是语音识别测试。"


def test_speech_gateway_cleans_language_prefix_without_marker():
    """Handle providers that return language metadata without the Qwen marker."""

    assert SpeechGateway()._clean_transcript("language English Hello world.") == "Hello world."
