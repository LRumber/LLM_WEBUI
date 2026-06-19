"""FastAPI entry point exposing health, model discovery, and normalized streaming chat."""

import json
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from app.chat import ChatConfigurationError, ChatGateway, ModelBusyError, UpstreamChatError
from app.config import get_settings
from app.models import ChatRequest, HealthResponse, ModelEndpoint, ModelHealthResponse
from app.registry import ModelRegistry

settings = get_settings()
registry = ModelRegistry(settings.model_config_path)
chat_gateway = ChatGateway(settings)


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
    return registry.list_enabled()


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
