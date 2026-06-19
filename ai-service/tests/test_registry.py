"""Tests for model registry validation and enabled-model filtering."""

import json

from app.registry import ModelRegistry
from app.models import ModelCapability, ModelEndpoint


def test_registry_only_returns_enabled_models(tmp_path):
    """Disabled registrations must not be discoverable or directly resolvable."""
    config = tmp_path / "models.json"
    config.write_text(
        json.dumps(
            {
                "models": [
                    {
                        "key": "chat-model",
                        "display_name": "Chat",
                        "provider": "vllm",
                        "base_url": "http://localhost:8000/v1",
                        "model_name": "chat",
                        "capabilities": ["chat"],
                        "enabled": True,
                    },
                    {
                        "key": "disabled-model",
                        "display_name": "Disabled",
                        "provider": "vllm",
                        "base_url": "http://localhost:8002/v1",
                        "model_name": "disabled",
                        "capabilities": ["chat"],
                        "enabled": False,
                    },
                ]
            }
        ),
        encoding="utf-8",
    )

    registry = ModelRegistry(str(config))
    registry.load()

    assert [model.key for model in registry.list_enabled()] == ["chat-model"]
    assert registry.get("disabled-model") is None


def test_registry_persists_dynamic_model_updates(tmp_path):
    """Administrative updates must survive process restart without partial JSON files."""
    config = tmp_path / "models.json"
    registry = ModelRegistry(str(config))
    registry.load()
    registry.upsert(ModelEndpoint(
        key="local-chat",
        display_name="Local Chat",
        provider="local",
        engine="vllm",
        base_url="http://localhost:8000/v1",
        model_name="chat",
        capabilities={ModelCapability.CHAT},
        quantization="awq",
        gpu_ids=[0, 1],
        tensor_parallel_size=2,
        max_concurrency=8,
    ))

    reloaded = ModelRegistry(str(config))
    reloaded.load()
    model = reloaded.get("local-chat")

    assert model is not None
    assert model.quantization == "awq"
    assert model.gpu_ids == [0, 1]
    assert model.max_concurrency == 8
