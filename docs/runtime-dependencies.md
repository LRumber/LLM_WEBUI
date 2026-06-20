# 本地运行依赖

平台当前采用“模型 API 接入”模式，不在管理后台安装或部署 GPU 推理引擎。管理员填写 OpenAI 兼容 API 地址与 API Key，发现模型后设置模型类型、运行状态和前台可见性。

## 已安装到项目

AI 服务的 Python 依赖已安装到 `ai-service/.venv`，包括 FastAPI、HTTP 客户端、PDF/Word/Excel/PPT 解析、图片处理、Tesseract Python 适配和密钥加密库。项目虚拟环境不会提交到 Git。

启动 AI 服务：

```powershell
ai-service\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir ai-service --host 127.0.0.1 --port 8001
```

## 需要手动安装

1. Docker Desktop for Windows
   - 安装时启用 WSL 2 后端；系统可能要求管理员权限和重启。
   - Docker 用于启动 Redis、MinIO，后续也可启动 PostgreSQL + pgvector。
   - 安装完成后用 `docker version` 和 `docker compose version` 验证。
2. Tesseract OCR 5.x for Windows
   - 至少安装英文 `eng` 和简体中文 `chi_sim` 语言包。
   - 将 Tesseract 安装目录加入系统 `PATH`，再用 `tesseract --version` 验证。
   - Python 包 `pytesseract` 已安装，但没有系统可执行程序时图片 OCR 仍不能工作。

Redis 和 MinIO 不建议再单独安装 Windows 服务。Docker Desktop 安装完成后，在仓库根目录执行：

```powershell
docker compose -f deploy/docker-compose.yml up -d redis minio
```

随后用 `docker compose -f deploy/docker-compose.yml ps` 检查容器健康状态。不要安装 vLLM、SGLang、Ollama 或 llama.cpp；当前需求只保留外部 OpenAI 兼容 API 接入。

## 文件能力边界

- PDF、DOCX、XLSX、PPTX、TXT、Markdown：先由 AI 服务提取文本、表格、页数和元数据，再把提取结果交给大语言模型，因此不要求模型具有视觉能力。
- 常见图片：只有声明 `vision` 能力的多模态模型可以作为图片上下文；OCR 可独立提取图片文字。
- 音频：需要后续接入 Whisper 或 FunASR 服务，并为模型声明 `speech_to_text` 能力。
- 原始文件写入 MinIO 私有对象存储；解析结果、状态和元数据写入数据库。图片发给模型时由后端读取私有对象并构造数据 URL，不暴露 MinIO 地址。
