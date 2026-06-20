"""Tests for model registry validation and enabled-model filtering."""

import json

from app.registry import ModelRegistry
from app.models import ModelCapability, ModelCategory, ModelEndpoint
from pydantic import SecretStr


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
        provider="openai-compatible",
        base_url="http://localhost:8000/v1",
        model_name="chat",
        category=ModelCategory.LANGUAGE,
        capabilities={ModelCapability.CHAT},
        frontend_visible=False,
        max_concurrency=8,
    ))

    reloaded = ModelRegistry(str(config))
    reloaded.load()
    model = reloaded.get("local-chat")

    assert model is not None
    assert model.category == ModelCategory.LANGUAGE
    assert model.frontend_visible is False
    assert model.max_concurrency == 8


def test_registry_encrypts_api_key_and_hides_plaintext(tmp_path):
    """API credentials must survive reload without appearing in model registry JSON."""
    config = tmp_path / "models.json"
    registry = ModelRegistry(str(config))
    registry.load()
    registry.upsert(ModelEndpoint(
        key="secured-chat",
        display_name="Secured Chat",
        provider="openai-compatible",
        base_url="https://models.example/v1",
        model_name="chat",
        capabilities={ModelCapability.CHAT},
        api_key=SecretStr("top-secret"),
    ))

    assert "top-secret" not in config.read_text(encoding="utf-8")
    reloaded = ModelRegistry(str(config))
    reloaded.load()
    assert reloaded.get("secured-chat").api_key.get_secret_value() == "top-secret"
