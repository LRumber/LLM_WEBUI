# 运行依赖

## 容器部署

Ubuntu 22.04 x86_64 目标机只需要 Docker Engine 与 Docker Compose plugin。Java、Node.js、Python、Tesseract、中文 OCR 语言包和字体均包含在应用镜像中。

完整步骤见 [Ubuntu 部署说明](deployment-ubuntu.md)。

容器组成：

- `gateway`：Nginx、前端静态资源、HTTP/HTTPS 和 `/api` 反向代理
- `backend`：Java 21 Spring Boot 核心服务
- `ai-service`：Python 3.12、FastAPI、文档解析、Tesseract OCR
- `postgres`：业务数据和知识分片
- `minio`：私有原始文件
- `redis`：Spring Session，保存 OAuth2 状态、用户和管理员会话

模型推理不在本 Compose 内部署。目标网络必须能够访问管理员注册的 OpenAI 兼容 Chat、Embedding 和可选 Reranker API。

## 本地 Windows 开发

- Java 21、Maven 3.9+
- Node.js 20+、npm
- Python 3.11+
- PostgreSQL、MinIO
- 图片 OCR 需要 Tesseract 5.x、`chi_sim` 与 `eng`

本地可以仅用 Compose 启动基础设施：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml up -d postgres redis minio
```

## 文件能力

- PDF、DOCX、XLSX、PPTX、TXT、Markdown：由 AI 服务提取文本和结构。
- 图片：可由 Tesseract OCR；作为模型上下文时要求模型具有 `vision` 能力。
- 音频：当前未接入语音识别服务。
- 原始文件保存在 MinIO；解析结果、索引状态和引用保存在 PostgreSQL。
