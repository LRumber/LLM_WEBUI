import json
from pathlib import Path

from app.models import ModelEndpoint


class ModelRegistry:
    def __init__(self, config_path: str) -> None:
        self._config_path = Path(config_path)
        self._models: dict[str, ModelEndpoint] = {}

    def load(self) -> None:
        if not self._config_path.exists():
            self._models = {}
            return

        payload = json.loads(self._config_path.read_text(encoding="utf-8"))
        models = [ModelEndpoint.model_validate(item) for item in payload.get("models", [])]
        self._models = {model.key: model for model in models}

    def list_enabled(self) -> list[ModelEndpoint]:
        return [model for model in self._models.values() if model.enabled]

    def get(self, key: str) -> ModelEndpoint | None:
        model = self._models.get(key)
        return model if model and model.enabled else None
