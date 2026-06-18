import json

import httpx
import pytest
from pydantic import SecretStr

from app.chat import ChatGateway
from app.config import Settings
from app.models import ChatMessage, ChatRequest, ModelCapability, ModelEndpoint


@pytest.mark.asyncio
async def test_chat_gateway_translates_openai_stream():
    upstream_events = [
        {"choices": [{"delta": {"content": "你"}}]},
        {"choices": [{"delta": {"content": "好"}}]},
        {
            "choices": [],
            "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
        },
    ]
    body = "".join(f"data: {json.dumps(event, ensure_ascii=False)}\n\n" for event in upstream_events)
    body += "data: [DONE]\n\n"

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        assert request.url.path == "/chat/completions"
        return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})

    settings = Settings(deepseek_api_key=SecretStr("test-key"))
    gateway = ChatGateway(settings, transport=httpx.MockTransport(handler))
    model = ModelEndpoint(
        key="deepseek-chat",
        display_name="DeepSeek Chat",
        provider="deepseek",
        base_url="https://api.deepseek.com",
        model_name="deepseek-chat",
        capabilities={ModelCapability.CHAT},
    )
    chat_request = ChatRequest(
        model="deepseek-chat",
        messages=[ChatMessage(role="user", content="你好")],
    )

    events = [event async for event in gateway.stream(chat_request, model)]

    assert events == [
        {"type": "delta", "content": "你"},
        {"type": "delta", "content": "好"},
        {
            "type": "usage",
            "prompt_tokens": 3,
            "completion_tokens": 2,
            "total_tokens": 5,
        },
        {"type": "done"},
    ]
