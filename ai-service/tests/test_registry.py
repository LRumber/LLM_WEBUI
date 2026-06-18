import json

from app.registry import ModelRegistry


def test_registry_only_returns_enabled_models(tmp_path):
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
