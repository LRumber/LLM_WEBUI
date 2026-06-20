"""Encrypt model API credentials at rest beside the dynamic registry configuration."""

import json
import os
from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken


class ModelCredentialStore:
    """Small encrypted credential store whose key and ciphertext are kept in separate files."""

    def __init__(self, config_path: Path) -> None:
        """Place credential artifacts beside the model registry for offline backup and restore."""
        self._secret_path = config_path.with_name("model-secrets.json")
        self._key_path = config_path.with_name(".model-secret.key")
        self._cipher = Fernet(self._load_or_create_key())

    def get(self, model_key: str) -> str | None:
        """Decrypt one credential; corrupt or foreign ciphertext is treated as unavailable."""
        encrypted = self._read().get(model_key)
        if not encrypted:
            return None
        try:
            return self._cipher.decrypt(encrypted.encode()).decode()
        except (InvalidToken, ValueError):
            return None

    def put(self, model_key: str, api_key: str) -> None:
        """Encrypt and atomically persist one API key without writing plaintext to disk."""
        payload = self._read()
        payload[model_key] = self._cipher.encrypt(api_key.encode()).decode()
        self._write(payload)

    def delete(self, model_key: str) -> None:
        """Remove a credential when its model registration is deleted."""
        payload = self._read()
        if payload.pop(model_key, None) is not None:
            self._write(payload)

    def _load_or_create_key(self) -> bytes:
        if self._key_path.exists():
            return self._key_path.read_bytes().strip()
        self._key_path.parent.mkdir(parents=True, exist_ok=True)
        key = Fernet.generate_key()
        self._key_path.write_bytes(key + b"\n")
        try:
            os.chmod(self._key_path, 0o600)
        except OSError:
            pass
        return key

    def _read(self) -> dict[str, str]:
        if not self._secret_path.exists():
            return {}
        return json.loads(self._secret_path.read_text(encoding="utf-8"))

    def _write(self, payload: dict[str, str]) -> None:
        temporary = self._secret_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, self._secret_path)
