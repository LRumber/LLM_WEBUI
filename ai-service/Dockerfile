FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AI_MODEL_CONFIG_PATH=/app/config/models.json

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng fonts-noto-cjk \
       libreoffice-writer libreoffice-calc libreoffice-impress \
    && rm -rf /var/lib/apt/lists/* \
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
