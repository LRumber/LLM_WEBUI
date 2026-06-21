"""Contract tests for configurable OpenAI-compatible embedding endpoints."""

import json

import httpx
import pytest
from pydantic import SecretStr

from app.config import Settings
from app.embeddings import EmbeddingGateway
from app.models import EmbeddingRequest, ModelCapability, ModelEndpoint


@pytest.mark.asyncio
async def test_embedding_gateway_preserves_batch_order():
    """Return normalized vectors in input order while keeping the credential server-side."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer test-key"
        assert json.loads(request.content) == {"model": "bge-m3", "input": ["甲", "乙"]}
        return httpx.Response(200, json={
            "data": [
                {"index": 1, "embedding": [0.0, 1.0]},
                {"index": 0, "embedding": [1.0, 0.0]},
            ],
            "usage": {"prompt_tokens": 2, "total_tokens": 2},
        })

    gateway = EmbeddingGateway(
        Settings(), transport=httpx.MockTransport(handler)
    )
    model = ModelEndpoint(
        key="embedding-bge-m3", display_name="BGE M3", provider="local",
        base_url="http://embedding.local/v1", model_name="bge-m3",
        capabilities={ModelCapability.EMBEDDING}, api_key=SecretStr("test-key"),
        frontend_visible=False,
    )

    response = await gateway.embed(
        EmbeddingRequest(model=model.key, input=["甲", "乙"]), model
    )

    assert [item.embedding for item in response.data] == [[1.0, 0.0], [0.0, 1.0]]
    assert response.usage.total_tokens == 2
