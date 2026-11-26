# 内网大模型对话平台

面向局域网和离线环境的大模型对话平台。仓库由 Vue 前端、Spring Boot 核心服务和 FastAPI AI 网关组成，通过 OpenAI 兼容 API 接入对话、Embedding、Reranker 和语音识别模型。

## 当前功能

### 用户工作台

- OpenAI 兼容模型列表与模型切换
- SSE 流式对话、推理过程展示和 Token 用量展示
- 流式回复自动跟随；手动上翻后暂停跟随，可点击返回最新回复
- Markdown 代码块高亮与复制
- 会话历史、搜索、重命名、软删除、分支、消息编辑和重新生成
- 按模型配置的上下文窗口自动压缩较早对话，保留摘要和近期消息
- 系统提示词与个人提示词模板
- 文档和图片附件上传、解析、下载及处理状态展示
- 多模态模型图片输入
- 本地浏览器录音输入，调用内网语音识别模型转文字
- 个人知识库选择、检索上下文注入和回答引用
- 团队空间、团队成员和团队知识库
- 团队管理员申请、审批状态及拒绝意见查看
- 平台公告展示和历史更新查看
- 意见反馈提交，后台可按提交时间分页查看反馈人姓名、工号和内容

### 知识库

- 创建、编辑和删除个人或团队知识库
- PDF、DOC、DOCX、XLS、XLSX、PPT、PPTX、TXT、Markdown 文档解析
- 扫描版 PDF 会渲染页面并用本地 OCR 识别
- 旧 Office 格式由后端 Apache POI 离线解析，不依赖 LibreOffice
- 图片 OCR，依赖 Tesseract 的 `chi_sim` 和 `eng` 语言包
- 文档切片、Embedding、关键词与向量混合检索
- 可选 Reranker 重排
- 检索测试、引用快照和检索日志
- Embedding 不可用时回退到关键词及有限片段召回

当前向量保存为 PostgreSQL `REAL[]`，相似度在 Java 服务中计算。Compose 使用 pgvector 镜像，但代码尚未切换为 pgvector 索引查询。

### 管理后台

- 独立本地管理员登录
- 平台概览、用户权限和用量统计；网页对话与 API 分开统计，支持按月份和模型查看
- 团队管理员申请审批：通过或拒绝、填写意见、查看审批记录
- API 额度申请审批：追加 Token 或申请无限制，审批结果与额度调整流水同步保存
- API 调用记录：用户查看自己的记录，管理员按用户、模型、日期和结果排查失败原因
- OpenAI 兼容模型发现、注册、启停和健康检查
- 控制哪些对话模型在前台展示及展示顺序，配置模型上下文窗口
- 设置默认 Embedding、Reranker 和语音识别模型，配置保存到数据库
- 系统提示词模板管理
- 平台公告创建、编辑、发布及历史管理
- 系统设置支持新用户默认 API 额度、网页对话每日请求限额、每用户并发控制及维护模式；基础信息和文件限制折叠展示，运行状态在平台概览中查看

运行策略保存后对新请求生效，默认不限额、不限并发且维护模式关闭。每日限额按北京时间计数，包含受理后失败的网页对话、编辑后生成和重新生成请求，自功能上线起记录；API 推理与网页对话共享用户并发上限，但不计入网页每日限额。维护模式暂停新推理和上传请求，允许查看历史、下载文件和管理后台操作。

升级需执行 `V49__runtime_policy.sql`。迁移保留已有 API 余额，新用户首次创建账号时按当前默认额度初始化；更改默认额度不会重置已有用户余额。并发名额通过数据库租约管理，正常完成或异步流结束时释放，运行中的请求定时续租，异常进程遗留租约约 15 分钟后回收。域名、端口和证书仍由部署配置管理。

模型 API Key 由 AI 服务加密保存，不通过查询接口返回。模型注册、密文和加密密钥位于 AI 服务的配置目录，部署时必须持久化并共同备份。

API 额度申请及调用日志的权限、升级与记录边界见 [API 额度说明](docs/api-credit-accounting.md)。

### 团队管理员申请与审批

1. 用户进入 `/app/teams`，点击“申请成为团队管理员”，填写申请理由（最多 1000 字）。
2. 提交后显示待审批状态，同一用户不能重复提交待审批申请。
3. 系统管理员进入 `/admin/users`，在“团队管理员申请”列表中选择通过或拒绝，并可填写审批意见。
4. 通过后，用户可创建团队；拒绝后，用户可查看意见并重新申请。审批记录保留申请人、理由、结果、意见、审批人和时间。

团队管理员权限用于创建团队，不授予系统管理后台访问权限。具体团队内的所有者或管理员负责成员及团队知识库管理，普通成员可查看团队知识库。后台原有的直接设置团队管理员入口仍可使用。

### API 开发平台

- 入口为 `/app/api-platform`，支持 API Key 创建、重置和管理，以及个人额度、额度申请和调用记录查看。
- 对外 OpenAI 兼容 API 的 Base URL 为 `https://<部署域名>:<端口>/api/v1`，使用平台签发的 API Key 进行 Bearer 认证。
- 支持模型列表、流式及非流式对话、Embedding、Rerank 和语音转写；可用模型由后台配置。

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
| 文档解析 | Apache POI、pypdf、PyMuPDF、python-docx、openpyxl、python-pptx、Pillow、Tesseract |
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
- 旧 Office `.doc/.xls/.ppt` 由后端 Apache POI 解析，不需要安装 LibreOffice
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

团队管理员申请与审批依赖新增的 `V32__team_administrator_requests.sql` 迁移。已有部署升级时，应同时更新前后端，并等待后端迁移成功后使用新入口。

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
- API 开发平台：`http://127.0.0.1:8505/app/api-platform`
- 管理后台：`http://127.0.0.1:8505/admin`
- 团队管理员审批：`http://127.0.0.1:8505/admin/users`
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

后端数据库集成测试需额外设置 `CREDIT_TEST_DB_URL`、`CREDIT_TEST_DB_USER` 和 `CREDIT_TEST_DB_PASSWORD`，指向专用 PostgreSQL 测试库；未设置连接地址时会跳过。测试账号需有创建和删除测试 schema 的权限。

## Docker 部署

仓库现在提供 Ubuntu 22.04 x86_64 的完整容器编排：

- Vue 构建与 Nginx HTTP/HTTPS 网关
- Spring Boot 后端
- FastAPI、Tesseract、`chi_sim`、`eng` 和中文字体
- Apache POI、PyMuPDF、Tesseract 和 ffmpeg，用于旧 Office 文本提取、扫描版 PDF OCR 与浏览器录音格式转换
- PostgreSQL、MinIO 和用于 Spring Session 的 Redis
- 健康检查、启动依赖、资源限制和持久卷
- 默认入口 `https://datc.sitp.ac.cn:8505`；域名和端口由部署配置管理
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
- `/api/teams/administrator-request`：当前用户的团队管理员申请提交与状态查询
- `/api/admin/team-administrator-requests`：后台分页查询申请；`/{id}/review` 审批
- `/api/developer/**`：个人 API Key、额度申请及调用记录
- `/api/v1/**`：对外 OpenAI 兼容 API
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
