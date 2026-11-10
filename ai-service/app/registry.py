"""Load and query declarative model endpoint registrations."""

import json
import os
from pathlib import Path
from threading import RLock

from app.models import ModelEndpoint
from app.credentials import ModelCredentialStore
from pydantic import SecretStr


class ModelRegistry:
    """In-memory view of the validated model configuration file."""

    def __init__(self, config_path: str) -> None:
        """Prepare a registry backed by the supplied JSON path."""
        self._config_path = Path(config_path)
        self._models: dict[str, ModelEndpoint] = {}
        self._lock = RLock()
        self._credentials = ModelCredentialStore(self._config_path)

    def load(self) -> None:
        """Reload all registrations atomically; a missing file represents an empty registry."""
        if not self._config_path.exists():
            self._models = {}
            return

        payload = json.loads(self._config_path.read_text(encoding="utf-8"))
        models = []
        for item in payload.get("models", []):
            model = ModelEndpoint.model_validate(item)
            api_key = self._credentials.get(model.key)
            models.append(model.model_copy(update={"api_key": SecretStr(api_key) if api_key else None}))
        with self._lock:
            self._models = {model.key: model for model in models}

    def list_enabled(self) -> list[ModelEndpoint]:
        """Return models currently available to platform users."""
        with self._lock:
            return [model for model in self._models.values() if model.enabled]

    def list_user_visible(self) -> list[ModelEndpoint]:
        """Return callable models explicitly allowed in the conversation model selector."""
        with self._lock:
            return sorted(
                (model for model in self._models.values() if model.enabled and model.frontend_visible),
                key=lambda model: model.display_order,
            )

    def list_all(self) -> list[ModelEndpoint]:
        """Return every registration for administration, including disabled models."""
        with self._lock:
            return list(self._models.values())

    def get(self, key: str) -> ModelEndpoint | None:
        """Resolve an enabled model key without exposing disabled registrations."""
        with self._lock:
            model = self._models.get(key)
        return model if model and model.enabled else None

    def upsert(self, model: ModelEndpoint) -> ModelEndpoint:
        """Atomically persist a validated registration and publish it to new requests."""
        with self._lock:
            supplied_key = model.api_key.get_secret_value() if model.api_key else ""
            if supplied_key and supplied_key != "**********":
                self._credentials.put(model.key, supplied_key)
            else:
                existing = self._models.get(model.key)
                model = model.model_copy(update={"api_key": existing.api_key if existing else None})
            self._models[model.key] = model
            self._persist()
        return model

    def delete(self, key: str) -> bool:
        """Remove one registration without touching external model files or processes."""
        with self._lock:
            if key not in self._models:
                return False
            del self._models[key]
            self._credentials.delete(key)
            self._persist()
        return True

    def _persist(self) -> None:
        """Replace the JSON file atomically so readers never observe partial configuration."""
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self._config_path.with_suffix(self._config_path.suffix + ".tmp")
        payload = {"models": [model.model_dump(mode="json") for model in self._models.values()]}
        temporary_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        os.replace(temporary_path, self._config_path)
