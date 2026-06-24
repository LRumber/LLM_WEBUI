# 内网大模型对话平台

面向局域网和离线环境的大模型对话平台。仓库由 Vue 前端、Spring Boot 核心服务和 FastAPI AI 网关组成，通过 OpenAI 兼容 API 接入对话、Embedding、Reranker 和语音识别模型。

## 当前功能

### 用户工作台

- OpenAI 兼容模型列表与模型切换
- SSE 流式对话、推理过程展示和 Token 用量展示
- 会话历史、搜索、重命名、软删除、分支、消息编辑和重新生成
- 系统提示词与个人提示词模板
- 文档和图片附件上传、解析、下载及处理状态展示
- 多模态模型图片输入
- 本地浏览器录音输入，调用内网语音识别模型转文字
- 个人知识库选择、检索上下文注入和回答引用
- 团队空间、团队成员和团队知识库

### 知识库

- 创建、编辑和删除个人或团队知识库
- PDF、DOC、DOCX、XLS、XLSX、PPT、PPTX、TXT、Markdown 文档解析
- 扫描版 PDF 会渲染页面并用本地 OCR 识别
- 旧 Office 格式通过容器内 LibreOffice 离线转换后解析
- 图片 OCR，依赖 Tesseract 的 `chi_sim` 和 `eng` 语言包
- 文档切片、Embedding、关键词与向量混合检索
- 可选 Reranker 重排
- 检索测试、引用快照和检索日志
- Embedding 不可用时回退到关键词及有限片段召回

当前向量保存为 PostgreSQL `REAL[]`，相似度在 Java 服务中计算。Compose 使用 pgvector 镜像，但代码尚未切换为 pgvector 索引查询。

### 管理后台

- 独立本地管理员登录
- 平台概览、用户权限和用量统计
- OpenAI 兼容模型发现、注册、启停和健康检查
- 控制哪些对话模型在前台展示
- 设置默认 Embedding、Reranker 和语音识别模型，配置保存到数据库
- 系统提示词模板管理
- 系统设置展示运行策略；域名和端口由部署文件、Nginx 和证书配置固定管理

模型 API Key 由 AI 服务加密保存，不通过查询接口返回。模型注册、密文和加密密钥位于 AI 服务的配置目录，部署时必须持久化并共同备份。

## 尚未实现或未达到生产状态

- 语音合成
- Agent、Skill、MCP 和应用发布
- pgvector 索引检索
- OAuth2 单点退出、令牌刷新和身份中心停用事件主动推送
- 自动证书续期

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 核心服务 | Java 21、Spring Boot 3、Spring Security、JDBC、Flyway |
| AI 网关 | Python 3.11+、FastAPI、Pydantic、httpx |
| 数据库 | PostgreSQL；Compose 当前使用 pgvector PostgreSQL 镜像 |
| 文件存储 | MinIO |
| 会话 | Spring Session、Redis |
| 文档解析 | pypdf、PyMuPDF、python-docx、openpyxl、python-pptx、Pillow、Tesseract、LibreOffice |
| 模型协议 | OpenAI 兼容 Chat、Embeddings、Audio Transcriptions，以及 Jina/OpenAI 风格 Rerank |

## 仓库结构

```text
LM_web/
|- frontend/           Vue 用户工作台和管理后台
|- backend/            Spring Boot 业务、权限、对话和知识库服务
|- ai-service/         模型代理、文档解析、Embedding 和 Rerank 网关
|- deploy/             完整 Compose、Nginx、备份恢复与离线交付脚本
|- docs/               架构、运行依赖和开发规范
|- .env.example        本地环境变量示例
`- README.md
```

## 本地开发依赖

- Java 21 和 Maven 3.9+
- Node.js 20+ 和 npm
- Python 3.11+
- Docker 与 Docker Compose，用于 PostgreSQL、Redis 和 MinIO
- Tesseract OCR 5.x，仅在需要图片 OCR 时必需
- LibreOffice，仅在本机直接解析 `.doc/.xls/.ppt` 时必需；Docker 镜像内已包含
- ffmpeg，仅在本机直接使用浏览器录音转写时建议安装；Docker AI 镜像内已包含
- 至少一个可从部署网络访问的 OpenAI 兼容对话模型 API
- 使用知识库向量检索时，还需要 Embedding API；Reranker 可选；语音输入需要 OpenAI 兼容语音识别 API

内网离线安装时，先在联网的 Ubuntu 22.04 x86_64 构建机执行 `deploy/export-offline.sh`，再把生成的镜像包导入目标机。

## 环境配置

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

至少检查以下配置：

- `DATABASE_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD`
- `MINIO_ENDPOINT`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_BUCKET`
- `AI_SERVICE_BASE_URL`
- 初始管理员 `ADMIN_INITIAL_*`
- OAuth2 服务与客户端参数

Embedding、Reranker 和语音识别默认模型不再通过 `.env` 配置。先在 `/admin/models` 注册模型，再在同一页面保存默认模型。语音识别统一按 OpenAI 兼容接口调用：API 地址填写为 `http://<模型服务器>:<端口>/v1`，模型名填写服务实际暴露的 id，例如 `Qwen/Qwen3-ASR-1.7B`，系统会调用 `/audio/transcriptions + file`。浏览器录音会在前端编码为 `wav` 后上传，避免本地开发环境依赖 ffmpeg；Docker AI 镜像仍内置 ffmpeg 作为格式转换兜底。

## 本地启动

1. 启动基础设施：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml --profile tools up -d postgres redis minio minio-client
```

2. 启动 AI 服务：

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

也可以直接使用本地脚本：

```powershell
scripts\run-ai-service-local.cmd
```

3. 启动后端：

```powershell
cd backend
mvn spring-boot:run
```

Spring Boot 启动时自动执行 Flyway 数据库迁移。

本地调试登录可通过 `DEV_LOGIN_ENABLED=true` 启用，脚本 [run-backend-local.cmd](scripts/run-backend-local.cmd) 已内置该变量。

4. 启动前端：

```powershell
cd frontend
npm ci
npm run dev
```

默认地址：

- 用户工作台：`http://127.0.0.1:8505/app`
- 知识库：`http://127.0.0.1:8505/app/knowledge`
- 团队空间：`http://127.0.0.1:8505/app/teams`
- 管理后台：`http://127.0.0.1:8505/admin`
- 后端健康检查：`http://127.0.0.1:8080/actuator/health`
- AI 服务健康检查：`http://127.0.0.1:8001/health`
- MinIO Console：`http://127.0.0.1:9001`

## 测试与构建

```powershell
cd backend
mvn test

cd ..\frontend
npm test
npm run build

cd ..\ai-service
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\.venv\Scripts\python.exe -m pytest
```

## Docker 部署

仓库现在提供 Ubuntu 22.04 x86_64 的完整容器编排：

- Vue 构建与 Nginx HTTP/HTTPS 网关
- Spring Boot 后端
- FastAPI、Tesseract、`chi_sim`、`eng` 和中文字体
- LibreOffice、PyMuPDF 和 ffmpeg，用于旧 Office 文件转换、扫描版 PDF OCR 与浏览器录音格式转换
- PostgreSQL、MinIO 和用于 Spring Session 的 Redis
- 健康检查、启动依赖、资源限制和持久卷
- 默认域名 `ai.sitp.ac.cn`；域名和端口由部署配置管理
- PostgreSQL、MinIO、模型配置和密钥备份恢复
- 联网构建及 x86_64 离线镜像导出导入

快速启动：

```bash
cp .env.example .env
cp deploy/runtime.env.example deploy/runtime.env
chmod 600 deploy/certs/sitp.ac.cn.key
chmod +x deploy/*.sh
./deploy/platform.sh up
```

详细步骤、端口变更、离线交付和恢复流程见 [Ubuntu 部署说明](docs/deployment-ubuntu.md)。

## 主要接口

Spring Boot：

- `/api/models`、`/api/chat/stream`
- `/api/conversations/**`
- `/api/files/**`
- `/api/knowledge-bases/**`
- `/api/teams/**`
- `/api/prompt-templates/**`
- `/api/admin/**`

FastAPI：

- `/health`
- `/v1/models`
- `/v1/chat/stream`
- `/v1/embeddings`
- `/v1/rerank`
- `/v1/audio/transcriptions`
- `/v1/documents/parse`
- `/internal/models/**`

更多边界说明见 [运行依赖](docs/runtime-dependencies.md) 和 [架构说明](docs/architecture.md)。
