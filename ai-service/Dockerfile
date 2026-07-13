FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AI_MODEL_CONFIG_PATH=/app/config/models.json

RUN set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    for attempt in 1 2 3; do \
      apt-get update \
      && apt-get install -y --no-install-recommends --download-only \
         tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng fonts-noto-cjk \
         ffmpeg \
      && break; \
      if [ "$attempt" = "3" ]; then exit 1; fi; \
      rm -rf /var/lib/apt/lists/*; \
      sleep 8; \
    done; \
    apt-get install -y --no-install-recommends \
       tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng fonts-noto-cjk \
       ffmpeg; \
    rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app

WORKDIR /app
COPY pyproject.toml ./pyproject.toml
COPY app ./app
RUN pip install --no-cache-dir .

RUN mkdir -p /app/config
COPY config/models.example.json /app/config/models.json
RUN chown -R app:app /app

USER app
VOLUME ["/app/config"]
EXPOSE 8001
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
