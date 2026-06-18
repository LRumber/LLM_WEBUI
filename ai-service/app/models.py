from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field, HttpUrl


class ModelCapability(StrEnum):
    CHAT = "chat"
    VISION = "vision"
    EMBEDDING = "embedding"
    RERANK = "rerank"
    SPEECH_TO_TEXT = "speech_to_text"


class ModelEndpoint(BaseModel):
    key: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]+$")
    display_name: str
    provider: str
    base_url: HttpUrl
    model_name: str
    capabilities: set[ModelCapability]
    enabled: bool = True
    context_window: int | None = Field(default=None, gt=0)


class HealthResponse(BaseModel):
    service: str
    status: str
    registered_models: int


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=100_000)


class ChatRequest(BaseModel):
    model: str
    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int | None = Field(default=None, gt=0, le=32_768)


class TokenUsage(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
