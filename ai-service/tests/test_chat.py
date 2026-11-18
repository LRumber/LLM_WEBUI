"""Contract tests for OpenAI-compatible SSE translation and usage extraction."""

import json

import httpx
import pytest
from pydantic import SecretStr

from app.chat import ChatGateway
from app.config import Settings
from app.models import ChatMessage, ChatRequest, ModelCapability, ModelEndpoint


@pytest.mark.asyncio
async def test_chat_gateway_translates_openai_stream():
    """Ensure provider chunks become ordered platform delta, usage, and done events."""
    upstream_events = [
        {"choices": [{"delta": {"reasoning_content": "先分析"}}]},
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
        """Act as a deterministic DeepSeek-compatible streaming endpoint."""
        assert request.headers["Authorization"] == "Bearer test-key"
        assert request.url.path == "/chat/completions"
        payload = json.loads(request.content)
        assert "enable_thinking" not in payload
        assert payload["model"] == "deepseek-reasoner"
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
        thinking_enabled=True,
    )

    events = [event async for event in gateway.stream(chat_request, model)]

    assert events == [
        {"type": "reasoning_delta", "content": "先分析"},
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


def test_chat_gateway_keeps_chat_model_for_fast_answers():
    """Keep the configured DeepSeek chat model when reasoning is disabled."""
    settings = Settings(deepseek_api_key=SecretStr("test-key"))
    gateway = ChatGateway(settings)
    model = ModelEndpoint(
        key="deepseek-chat",
        display_name="DeepSeek Chat",
        provider="deepseek",
        base_url="https://api.deepseek.com",
        model_name="deepseek-chat",
        capabilities={ModelCapability.CHAT},
    )
    request = ChatRequest(
        model="deepseek-chat",
        messages=[ChatMessage(role="user", content="hello")],
        thinking_enabled=False,
    )

    assert gateway._upstream_model_name(request, model) == "deepseek-chat"


def test_chat_gateway_keeps_qwen3_non_thinking_for_now():
    """Qwen3 registrations remain in non-thinking mode until deployment is validated."""
    gateway = ChatGateway(Settings())
    model = ModelEndpoint(
        key="qwen3",
        display_name="Qwen3",
        provider="openai-compatible",
        base_url="http://qwen3.local/v1",
        model_name="Qwen3",
        capabilities={ModelCapability.CHAT},
        reasoning_mode="qwen3",
    )
    request = ChatRequest(
        model="qwen3",
        messages=[ChatMessage(role="user", content="hello")],
        thinking_enabled=True,
    )
    payload = {"model": "Qwen3", "messages": [], "temperature": 0.7, "stream": True}

    gateway._apply_reasoning_mode(payload, request, model)

    assert payload["chat_template_kwargs"] == {"enable_thinking": False}


def test_chat_gateway_applies_deepseek_v4_thinking_kwargs():
    """DeepSeek V4 registrations use vLLM chat template kwargs for reasoning."""
    gateway = ChatGateway(Settings())
    model = ModelEndpoint(
        key="deepseek-v4-flash",
        display_name="DeepSeek V4 Flash",
        provider="openai-compatible",
        base_url="http://deepseek.local/v1",
        model_name="deepseek-v4-flash",
        capabilities={ModelCapability.CHAT},
        reasoning_mode="deepseek_v4",
    )
    request = ChatRequest(
        model="deepseek-v4-flash",
        messages=[ChatMessage(role="user", content="hello")],
        thinking_enabled=True,
    )
    payload = {"model": "deepseek-v4-flash", "messages": [], "temperature": 0.7, "stream": True}

    gateway._apply_reasoning_mode(payload, request, model)

    assert payload["chat_template_kwargs"] == {"thinking": True, "reasoning_effort": "high"}


@pytest.mark.asyncio
@pytest.mark.parametrize(('status', 'body', 'expected'), [(200, {'count': 123}, 123), (404, {}, None), (200, {'count': '123'}, None)])
async def test_native_tokenizer_uses_registered_model_and_preserves_chat_fields(status, body, expected):
    def handler(request):
        assert request.url.path == '/proxy/tokenize'
        payload = json.loads(request.content)
        assert payload == {'model': 'DeepSeek-V4', 'messages': [{'role': 'user', 'content': 'hello'}],
                           'chat_template_kwargs': {'thinking': False}}
        return httpx.Response(status, json=body)

    gateway = ChatGateway(Settings(), transport=httpx.MockTransport(handler))
    model = ModelEndpoint(key='ds4', display_name='DS4', provider='openai-compatible',
                          base_url='http://local-chat/proxy/v1', model_name='DeepSeek-V4',
                          capabilities={ModelCapability.CHAT}, reasoning_mode='deepseek_v4')
    assert await gateway.count_openai_prompt({'model': 'ds4', 'messages': [{'role': 'user', 'content': 'hello'}],
                                             'stream': True, 'max_tokens': 256}, model) == expected


@pytest.mark.asyncio
async def test_chat_health_allows_openai_compatible_endpoint_without_api_key():
    """Allow local OpenAI-compatible services that do not require Authorization."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/models"
        assert request.headers.get("authorization") is None
        return httpx.Response(200, json={"data": [{"id": "local-chat"}]})

    gateway = ChatGateway(Settings(), transport=httpx.MockTransport(handler))
    model = ModelEndpoint(
        key="local-chat",
        display_name="Local Chat",
        provider="openai-compatible",
        base_url="http://local-chat/v1",
        model_name="local-chat",
        capabilities={ModelCapability.CHAT},
    )

    result = await gateway.health(model)

    assert result["status"] == "UP"


@pytest.mark.asyncio
async def test_openai_completion_preserves_optional_fields():
    """External API proxy should keep OpenAI Chat Completions fields for Dify/tool callers."""

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["model"] == "Qwen3-Coder-30B-A3B-Instruct"
        assert payload["tools"] == [{"type": "function", "function": {"name": "lookup"}}]
        assert payload["tool_choice"] == "auto"
        assert payload["response_format"] == {"type": "json_object"}
        assert payload["stop"] == ["END"]
        assert payload["top_p"] == 0.9
        assert payload["stream"] is False
        return httpx.Response(
            200,
            json={
                "id": "chatcmpl-test",
                "object": "chat.completion",
                "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            },
        )

    gateway = ChatGateway(Settings(), transport=httpx.MockTransport(handler))
    model = ModelEndpoint(
        key="qwen3-coder",
        display_name="Qwen3 Coder",
        provider="openai-compatible",
        base_url="http://qwen.local/v1",
        model_name="Qwen3-Coder-30B-A3B-Instruct",
        capabilities={ModelCapability.CHAT},
    )
    payload = {
        "model": "qwen3-coder",
        "messages": [{"role": "user", "content": "hello"}],
        "tools": [{"type": "function", "function": {"name": "lookup"}}],
        "tool_choice": "auto",
        "response_format": {"type": "json_object"},
        "stop": ["END"],
        "top_p": 0.9,
    }

    status, body, _ = await gateway.openai_completion(payload, model)

    assert status == 200
    assert json.loads(body)["usage"]["total_tokens"] == 2


@pytest.mark.asyncio
async def test_stream_openai_completion_preserves_tool_role_messages():
    """Streaming proxy should not reject OpenAI tool-role messages."""
    upstream_events = [
        {"choices": [{"delta": {"content": "ok"}}]},
        {"choices": [], "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}},
    ]
    body = "".join(f"data: {json.dumps(event)}\n\n" for event in upstream_events) + "data: [DONE]\n\n"

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert payload["messages"][1]["role"] == "tool"
        assert payload["stream"] is True
        assert payload["stream_options"] == {"include_usage": True}
        return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})

    gateway = ChatGateway(Settings(), transport=httpx.MockTransport(handler))
    model = ModelEndpoint(
        key="tool-chat",
        display_name="Tool Chat",
        provider="openai-compatible",
        base_url="http://tool.local/v1",
        model_name="tool-chat",
        capabilities={ModelCapability.CHAT},
    )
    payload = {
        "model": "tool-chat",
        "messages": [
            {"role": "assistant", "tool_calls": [{"id": "call_1", "type": "function"}], "content": None},
            {"role": "tool", "tool_call_id": "call_1", "content": "result"},
        ],
        "stream": True,
    }

    lines = [line async for line in gateway.stream_openai_completion(payload, model) if line]

    assert "data:" in lines[0]
    assert lines[-1] == "data: [DONE]"
