# 离线大模型对话平台

面向无互联网环境部署的企业级大模型对话平台。平台统一接入现有 OAuth2 身份认证，支持多模型切换、多模态文件、语音识别、个人及团队知识库，并为智能体编排、应用发布、Skill 和 MCP 工具扩展预留标准接口。

## 建设目标

- 统一 OAuth2 登录，不提供本地注册和密码登录
- 支持本地大模型的接入、切换、授权和资源治理
- 支持文件、图片、OCR 和离线语音识别
- 支持个人、团队和项目知识库
- 统计用户 Token、对话、登录及工具调用情况
- 支持后续智能体编排、应用发布、Skill 和 MCP 服务
- 所有运行依赖、模型及升级包均可离线交付

## 技术架构

```text
Vue 3 用户端/管理端
        |
Spring Boot 核心平台
  |- OAuth2、用户、团队、权限
  |- 对话、配额、统计、审计
  |- 模型和工具注册管理
  `- 统一 API 与任务调度
        |
FastAPI AI 服务
  |- 模型路由与推理适配
  |- 文档解析、OCR、RAG
  |- 语音识别
  `- Agent、Skill、MCP 运行时
        |
本地模型 / PostgreSQL / Redis / MinIO
```

## 技术选型

| 领域 | 技术 |
| --- | --- |
| Web | Vue 3、TypeScript、Vite、Element Plus |
| 核心后端 | Java 21、Spring Boot 3、Spring Security |
| AI 服务 | Python 3.11、FastAPI、Pydantic |
| 模型推理 | vLLM / SGLang / llama.cpp，统一 OpenAI 兼容接口 |
| 数据库 | PostgreSQL、pgvector |
| 缓存 | Redis |
| 文件存储 | MinIO |
| 异步任务 | RabbitMQ（按业务量启用） |
| 可观测性 | Prometheus、Grafana、Loki |
| 部署 | Docker Compose，后续可迁移 Kubernetes |

## OAuth2 对接

平台不提供注册功能。用户由统一身份认证中心维护，本平台仅保存业务授权和统计所需的影子用户。

1. 浏览器访问 `{server}/uias/oauth/authorize`，携带 `client_id`、`redirect_uri`、`response_type=code` 和随机 `state`。
2. 核心后端校验回调中的 `state`，使用授权码请求 `{server}/uias/oauth/access_token`。
3. 核心后端使用访问令牌请求 `{server}/uias/oauth/userInfo`。
4. 以 `userId` 作为外部身份唯一标识，创建或更新本地影子用户。
5. 本地仅维护角色、团队、数据权限、模型权限和资源配额。

`client_secret` 和访问令牌只能在服务端使用，不得写入浏览器存储、URL 或普通日志。对接文档中的 `check_token` 接口已废弃，不纳入实现。

正式联调前还需向身份认证平台确认统一退出、刷新令牌、用户停用同步和认证服务不可用时的处理方式。

## 仓库结构

```text
LM_web/
|- backend/            Spring Boot 核心业务平台
|- ai-service/         FastAPI AI 能力服务
|- frontend/           Vue 3 用户端与管理端
|- deploy/             离线部署和基础设施配置
|- docs/               架构、接口及运维文档
`- .env.example        本地环境变量示例
```

## 核心数据域

- 身份权限：用户、团队、部门、角色、权限、登录流水
- 对话模型：会话、消息、附件、模型、模型授权
- 知识服务：知识库、文档、分片、索引任务、引用
- 用量治理：调用流水、Token 用量、每日汇总、配额
- 工具扩展：工具、Skill、MCP Server、调用流水
- 智能体：智能体、工作流、版本、发布应用、运行记录

Token 与登录统计采用“原始事件流水 + 每日聚合表”。原始流水用于审计，聚合表用于后台报表查询。

对话请求采用以下持久化链路：

```text
Vue -> Spring Boot -> PostgreSQL
          |
          `-> FastAPI -> DeepSeek / vLLM
```

Spring Boot 创建会话并保存用户消息，从 PostgreSQL 加载历史上下文，再将 FastAPI 的 SSE 增量逐块转发给前端。生成完成后保存助手消息、Token、响应耗时和每日用量汇总。

## 第一阶段范围

- [x] 项目结构与架构约定
- [ ] OAuth2 登录及影子用户同步
- [ ] RBAC 与团队管理
- [x] DeepSeek 模型注册、切换和流式对话
- [x] 对话消息、Token 和每日用量持久化
- [ ] 文件上传及对象存储
- [ ] 基础管理后台

第二阶段加入知识库、OCR 和语音识别；第三阶段加入 Skill、MCP 与安全沙箱；第四阶段实现智能体编排和应用发布。

## 本地开发

复制 `.env.example` 为 `.env` 并填写 OAuth2 配置。首次启动需要提前准备可离线使用的 Maven、Python 和 npm 依赖包。

```bash
docker compose -f deploy/docker-compose.yml up -d
cd backend && ./mvnw spring-boot:run
cd ai-service && uvicorn app.main:app --host 0.0.0.0 --port 8001
cd frontend && npm run dev
```

默认端口：前端 `5173`，核心后端 `8080`，AI 服务 `8001`。

本地 PostgreSQL 需要预先创建 `lm_platform` 数据库和同名登录用户，并确保根目录 `.env` 中的 `DATABASE_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD` 与实际配置一致。Spring Boot 启动时通过 Flyway 自动创建和升级表结构。

### DeepSeek 对话

开发阶段使用 DeepSeek 的 OpenAI 兼容 API。将密钥写入仓库根目录 `.env`：

```dotenv
AI_DEEPSEEK_API_KEY=your-api-key
```

随后启动 AI 服务和前端：

```powershell
cd ai-service
python -m uvicorn app.main:app --host 127.0.0.1 --port 8001

cd ../frontend
npm run dev
```

访问 `http://127.0.0.1:5173/app` 即可进行流式多轮对话。模型入口位于 `ai-service/config/models.json`；后续切换 vLLM 时，将该文件中的 `provider`、`base_url` 和 `model_name` 替换为本地服务配置，并为 vLLM 配置对应认证策略。

## 开发原则

- API 优先，模型推理服务使用 OpenAI 兼容协议
- 核心业务保持模块化单体，边界成熟后再拆分微服务
- AI 能力通过独立服务隔离，避免 Python 依赖侵入业务平台
- 文件、模型和工具均按最小权限访问并保留审计记录
- 不依赖公网运行，第三方包、镜像和模型必须支持离线导入
