"""Pydantic contracts shared by model registration and streaming-chat endpoints."""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, Field, HttpUrl, SecretStr


class ModelCapability(StrEnum):
    """Capabilities used by clients to enable only compatible UI and orchestration features."""
    CHAT = "chat"
    VISION = "vision"
    EMBEDDING = "embedding"
    RERANK = "rerank"
    SPEECH_TO_TEXT = "speech_to_text"
    TEXT_TO_SPEECH = "text_to_speech"


class InferenceEngine(StrEnum):
    """Supported inference runtimes hidden behind the OpenAI-compatible gateway contract."""

    VLLM = "vllm"
    SGLANG = "sglang"
    OLLAMA = "ollama"
    LLAMA_CPP = "llama.cpp"
    OPENAI_COMPATIBLE = "openai-compatible"


class ModelCategory(StrEnum):
    """User-facing model classes that drive multimodal compatibility checks."""

    LANGUAGE = "language"
    MULTIMODAL = "multimodal"


class ModelEndpoint(BaseModel):
    """Validated local registration for an OpenAI-compatible inference endpoint."""
    key: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]+$")
    display_name: str
    provider: str
    engine: InferenceEngine = InferenceEngine.OPENAI_COMPATIBLE
    category: ModelCategory = ModelCategory.LANGUAGE
    base_url: HttpUrl
    model_name: str
    capabilities: set[ModelCapability]
    enabled: bool = True
    frontend_visible: bool = True
    context_window: int | None = Field(default=None, gt=0)
    max_concurrency: int = Field(default=4, ge=1, le=1024)
    api_key_env: str | None = Field(default=None, pattern=r"^[A-Z][A-Z0-9_]*$")
    api_key: SecretStr | None = Field(default=None, exclude=True)


class ModelDiscoveryRequest(BaseModel):
    """Credentials used once to discover model ids from an OpenAI-compatible endpoint."""

    base_url: HttpUrl
    api_key: SecretStr | None = None


class HealthResponse(BaseModel):
    """Health endpoint response contract."""
    service: str
    status: str
    registered_models: int


class ModelHealthResponse(BaseModel):
    """Normalized readiness result for one registered inference endpoint."""

    model: str
    status: Literal["UP", "DOWN"]
    latency_ms: int
    detail: str | None = None


class TextContentPart(BaseModel):
    """Text segment inside an OpenAI-compatible multimodal message."""

    type: Literal["text"] = "text"
    text: str = Field(min_length=1, max_length=100_000)


class ImageUrlValue(BaseModel):
    """Image reference accepted by local vision models as URL or data URL."""

    url: str
    detail: Literal["auto", "low", "high"] = "auto"


class ImageContentPart(BaseModel):
    """OpenAI-compatible image segment for vision-capable model endpoints."""

    type: Literal["image_url"] = "image_url"
    image_url: ImageUrlValue


MessageContentPart = Annotated[TextContentPart | ImageContentPart, Field(discriminator="type")]


class ChatMessage(BaseModel):
    """Single role-bound text or multimodal message accepted by compatible models."""
    role: Literal["system", "user", "assistant"]
    content: str | list[MessageContentPart]


class ParsedDocument(BaseModel):
    """Normalized extraction result persisted by the platform document pipeline."""

    media_type: str
    text: str
    page_count: int | None = None
    tables: list[list[list[str]]] = Field(default_factory=list)
    metadata: dict[str, str | int | float | bool | None] = Field(default_factory=dict)


class ChatRequest(BaseModel):
    """Bounded chat request forwarded to a registered model."""
    model: str
    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int | None = Field(default=None, gt=0, le=32_768)
    thinking_enabled: bool = False


class TokenUsage(BaseModel):
    """Normalized token counts reported by compatible inference engines."""
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
