"""FastAPI entry point exposing health, model discovery, and normalized streaming chat."""

import json
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from app.chat import ChatConfigurationError, ChatGateway, ModelBusyError, UpstreamChatError
from app.config import get_settings
from app.documents import DocumentParseError, DocumentParser
from app.embeddings import EmbeddingError, EmbeddingGateway
from app.models import ChatRequest, EmbeddingRequest, EmbeddingResponse, HealthResponse, ModelCapability, ModelDiscoveryRequest, ModelEndpoint, ModelHealthResponse, ParsedDocument, RerankRequest, RerankResponse, SpeechTranscriptionResponse
from app.registry import ModelRegistry
from app.rerank import RerankError, RerankGateway
from app.speech import SpeechGateway, SpeechTranscriptionError

settings = get_settings()
registry = ModelRegistry(settings.model_config_path)
chat_gateway = ChatGateway(settings)
document_parser = DocumentParser()
embedding_gateway = EmbeddingGateway(settings)
rerank_gateway = RerankGateway(settings)
speech_gateway = SpeechGateway()


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Load model configuration before the service begins accepting traffic."""
    registry.load()
    yield


app = FastAPI(
    title="LM Platform AI Service",
    version="0.1.0",
    docs_url="/docs",
    lifespan=lifespan,
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Return process liveness and the number of enabled model registrations."""
    return HealthResponse(
        service=settings.service_name,
        status="UP",
        registered_models=len(registry.list_enabled()),
    )


@app.get("/v1/models", response_model=list[ModelEndpoint])
def list_models() -> list[ModelEndpoint]:
    """List models that administrators have enabled in the local registry."""
    return registry.list_user_visible()


@app.post("/internal/models/discover", response_model=list[str])
async def discover_models(request: ModelDiscoveryRequest) -> list[str]:
    """Recognize model ids exposed by an administrator-supplied OpenAI-compatible API."""
    headers = {}
    if request.api_key:
        headers["Authorization"] = f"Bearer {request.api_key.get_secret_value()}"
    url = f"{str(request.base_url).rstrip('/')}/models"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
        return [str(item["id"]) for item in response.json().get("data", []) if item.get("id")]
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        if status in {401, 403}:
            raise HTTPException(status_code=status, detail="API key is missing, invalid, or not allowed") from exc
        if status == 404:
            raise HTTPException(status_code=404, detail="Model list endpoint was not found") from exc
        raise HTTPException(
            status_code=502,
            detail=f"Model discovery failed with upstream HTTP {status}: {exc.response.text[:300]}",
        ) from exc
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="Model discovery timed out") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Model discovery failed: {str(exc)[:300]}") from exc


@app.get("/internal/models", response_model=list[ModelEndpoint])
def list_all_models() -> list[ModelEndpoint]:
    """List enabled and disabled registrations for the trusted Java administration service."""
    return registry.list_all()


@app.put("/internal/models/{model_key}", response_model=ModelEndpoint)
def upsert_model(model_key: str, model: ModelEndpoint) -> ModelEndpoint:
    """Create or replace one dynamic model registration with a stable key."""
    if model.key != model_key:
        raise HTTPException(status_code=400, detail="Path and model key must match")
    return registry.upsert(model)


@app.delete("/internal/models/{model_key}", status_code=204)
def delete_model(model_key: str) -> None:
    """Delete only the gateway registration, never model weights or engine processes."""
    if not registry.delete(model_key):
        raise HTTPException(status_code=404, detail="Model not found")


@app.post("/internal/models/{model_key}/health", response_model=ModelHealthResponse)
async def check_model_health(model_key: str) -> ModelHealthResponse:
    """Probe one enabled model using its OpenAI-compatible discovery endpoint."""
    model = registry.get(model_key)
    if model is None:
        raise HTTPException(status_code=404, detail="Enabled model not found")
    return ModelHealthResponse.model_validate(await chat_gateway.health(model))


@app.get("/v1/models/{model_key}", response_model=ModelEndpoint)
def get_model(model_key: str) -> ModelEndpoint:
    """Return one enabled model or a stable not-found response."""
    model = registry.get(model_key)
    if model is None:
        raise HTTPException(status_code=404, detail="Model not found")
    return model


@app.post("/v1/chat/stream")
def stream_chat(request: ChatRequest) -> StreamingResponse:
    """Validate a model key and stream normalized SSE events to the Java service."""
    model = registry.get(request.model)
    if model is None or "chat" not in model.capabilities:
        raise HTTPException(status_code=404, detail="Chat model not found")

    async def event_stream():
        """Convert service exceptions into final SSE error events after headers are sent."""
        try:
            async for event in chat_gateway.stream(request, model):
                yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except ChatConfigurationError as exc:
            event = {"type": "error", "code": "configuration_error", "message": str(exc)}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except UpstreamChatError as exc:
            event = {"type": "error", "code": "upstream_error", "message": str(exc)}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except ModelBusyError as exc:
            event = {"type": "error", "code": "model_busy", "message": str(exc)}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except Exception:
            event = {"type": "error", "code": "internal_error", "message": "Chat service failed"}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/v1/embeddings", response_model=EmbeddingResponse)
async def create_embeddings(request: EmbeddingRequest) -> EmbeddingResponse:
    """Generate vectors through an enabled embedding-capable LAN or local model endpoint."""
    model = registry.get(request.model)
    if model is None or "embedding" not in model.capabilities:
        raise HTTPException(status_code=404, detail="Embedding model not found")
    try:
        return await embedding_gateway.embed(request, model)
    except EmbeddingError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.post("/v1/rerank", response_model=RerankResponse)
async def rerank_documents(request: RerankRequest) -> RerankResponse:
    """Score candidate documents through an enabled rerank-capable model endpoint."""
    model = registry.get(request.model)
    if model is None or "rerank" not in model.capabilities:
        raise HTTPException(status_code=404, detail="Rerank model not found")
    try:
        return await rerank_gateway.rerank(request, model)
    except RerankError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.post("/v1/documents/parse", response_model=ParsedDocument)
async def parse_document(file: UploadFile = File(...)) -> ParsedDocument:
    """Parse one bounded document using only local libraries and OCR services."""
    try:
        return await document_parser.parse(file)
    except DocumentParseError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.post("/v1/audio/transcriptions", response_model=SpeechTranscriptionResponse)
async def transcribe_audio(file: UploadFile = File(...), model: str | None = None) -> SpeechTranscriptionResponse:
    """Transcribe audio through an enabled local or LAN speech-to-text model registration."""
    speech_models = [
        candidate for candidate in registry.list_enabled()
        if ModelCapability.SPEECH_TO_TEXT in candidate.capabilities
    ]
    selected = registry.get(model) if model else (speech_models[0] if speech_models else None)
    if selected is None or ModelCapability.SPEECH_TO_TEXT not in selected.capabilities:
        raise HTTPException(status_code=404, detail="Speech-to-text model not configured")
    media_type = file.content_type or "application/octet-stream"
    if not media_type.startswith("audio/") and media_type not in {"video/webm", "application/octet-stream"}:
        raise HTTPException(status_code=415, detail="Only audio recordings can be transcribed")
    try:
        text = await speech_gateway.transcribe(
            selected,
            await file.read(),
            file.filename or "recording.webm",
            media_type,
        )
        return SpeechTranscriptionResponse(text=text)
    except SpeechTranscriptionError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
