"""Environment-backed configuration for the standalone AI gateway."""

from functools import lru_cache

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings loaded from process variables and local development env files."""

    service_name: str = "lm-platform-ai-service"
    environment: str = "development"
    model_config_path: str = "config/models.json"
    deepseek_api_key: SecretStr | None = None
    chat_timeout_seconds: float = 120.0

    model_config = SettingsConfigDict(
        env_prefix="AI_",
        env_file=("../.env", ".env"),
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    """Return one immutable settings instance for the process lifetime."""
    return Settings()
