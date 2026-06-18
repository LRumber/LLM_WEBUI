"""Load and query declarative model endpoint registrations."""

import json
from pathlib import Path

from app.models import ModelEndpoint


class ModelRegistry:
    """In-memory view of the validated model configuration file."""

    def __init__(self, config_path: str) -> None:
        """Prepare a registry backed by the supplied JSON path."""
        self._config_path = Path(config_path)
        self._models: dict[str, ModelEndpoint] = {}

    def load(self) -> None:
        """Reload all registrations atomically; a missing file represents an empty registry."""
        if not self._config_path.exists():
            self._models = {}
            return

        payload = json.loads(self._config_path.read_text(encoding="utf-8"))
        models = [ModelEndpoint.model_validate(item) for item in payload.get("models", [])]
        self._models = {model.key: model for model in models}

    def list_enabled(self) -> list[ModelEndpoint]:
        """Return models currently available to platform users."""
        return [model for model in self._models.values() if model.enabled]

    def get(self, key: str) -> ModelEndpoint | None:
        """Resolve an enabled model key without exposing disabled registrations."""
        model = self._models.get(key)
        return model if model and model.enabled else None
