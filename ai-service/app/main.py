from contextlib import asynccontextmanager

import json

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from app.chat import ChatConfigurationError, ChatGateway, UpstreamChatError
from app.config import get_settings
from app.models import ChatRequest, HealthResponse, ModelEndpoint
from app.registry import ModelRegistry

settings = get_settings()
registry = ModelRegistry(settings.model_config_path)
chat_gateway = ChatGateway(settings)


@asynccontextmanager
async def lifespan(_: FastAPI):
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
    return HealthResponse(
        service=settings.service_name,
        status="UP",
        registered_models=len(registry.list_enabled()),
    )


@app.get("/v1/models", response_model=list[ModelEndpoint])
def list_models() -> list[ModelEndpoint]:
    return registry.list_enabled()


@app.get("/v1/models/{model_key}", response_model=ModelEndpoint)
def get_model(model_key: str) -> ModelEndpoint:
    model = registry.get(model_key)
    if model is None:
        raise HTTPException(status_code=404, detail="Model not found")
    return model


@app.post("/v1/chat/stream")
def stream_chat(request: ChatRequest) -> StreamingResponse:
    model = registry.get(request.model)
    if model is None or "chat" not in model.capabilities:
        raise HTTPException(status_code=404, detail="Chat model not found")

    async def event_stream():
        try:
            async for event in chat_gateway.stream(request, model):
                yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except ChatConfigurationError as exc:
            event = {"type": "error", "code": "configuration_error", "message": str(exc)}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except UpstreamChatError as exc:
            event = {"type": "error", "code": "upstream_error", "message": str(exc)}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except Exception:
            event = {"type": "error", "code": "internal_error", "message": "Chat service failed"}
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
