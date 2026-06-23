# Ubuntu 22.04 x86_64 部署

## 1. 主机准备

部署机需要：

- Ubuntu 22.04 x86_64
- Docker Engine 24+ 与 Docker Compose plugin
- 可解析到部署机地址的 `ai.sitp.ac.cn`
- 身份认证平台已按实际 HTTP/HTTPS 入口登记 OAuth2 回调地址
- 能访问已注册的对话、Embedding、可选 Reranker 和可选语音识别 API

除 Docker 外，目标机不需要安装 Java、Node.js、Python、Tesseract 或 LibreOffice。

## 2. 配置

```bash
cp .env.example .env
cp deploy/runtime.env.example deploy/runtime.env
```

修改 `.env` 中的数据库、MinIO、管理员、OAuth2 和模型 API 配置。生产环境必须替换示例密码与客户端密钥。

证书放置为：

```text
deploy/certs/sitp.ac.cn.pem
deploy/certs/sitp.ac.cn.key
```

```bash
chmod 600 deploy/certs/sitp.ac.cn.key
chmod +x deploy/*.sh
```

如启用 HTTPS，证书应覆盖实际访问域名。证书到期前必须替换并重建 `gateway`。

## 3. 在线构建启动

在能访问 Docker Hub、Maven Central、npm registry 和 Python package index 的构建环境执行：

```bash
./deploy/platform.sh up
./deploy/platform.sh status
```

平台仅发布 HTTP/HTTPS 网关端口。PostgreSQL、MinIO、后端和 AI 服务只在 Docker 网络中通信。

## 4. 离线交付

在联网的 Ubuntu 22.04 x86_64 构建机执行：

```bash
./deploy/export-offline.sh
```

生成：

```text
deploy/bundles/lm-platform-images-x86_64.tar
deploy/bundles/SHA256SUMS
```

将仓库、`.env`、证书和 `deploy/bundles` 一并复制到内网目标机，然后执行：

```bash
./deploy/import-offline.sh
```

离线包包含应用镜像及 Compose 使用的 PostgreSQL、MinIO、Redis、备份工具镜像。模型权重或外部模型服务不包含在此镜像包中。

## 5. HTTP/HTTPS 端口

默认入口：

- HTTP `${HTTP_PORT:-80}`
- HTTPS 由部署时的 Nginx 与证书配置决定
- 域名 `ai.sitp.ac.cn`

域名和端口属于部署层配置，不在后台页面动态修改。修改 `.env` 或反向代理配置后，重新执行部署脚本，并同步更新 OAuth2 回调地址。

## 6. 备份恢复

备份 PostgreSQL、MinIO、模型注册、加密密文和加密密钥：

```bash
./deploy/backup.sh
```

备份位于 `deploy/backups/<时间戳>/`，其中 `env.snapshot` 含敏感信息，应保存到受控介质。

恢复：

```bash
./deploy/restore.sh <备份目录名>
```

恢复会短暂停止网关、后端和 AI 服务，不会删除命名卷。

## 7. 运维命令

```bash
./deploy/platform.sh status
./deploy/platform.sh logs backend
./deploy/platform.sh logs ai-service
./deploy/platform.sh restart
./deploy/platform.sh down
```

Redis 由 Spring Session 使用，必须与后端同时运行。不要单独清空 Redis，否则当前 OAuth2 和管理员会话会失效。

## 8. 验证

```bash
curl -I http://ai.sitp.ac.cn
curl -k https://ai.sitp.ac.cn/gateway-health
docker compose --env-file .env --env-file deploy/runtime.env -f deploy/docker-compose.yml ps
```

浏览器访问 `https://ai.sitp.ac.cn`，完成 OAuth2 登录后再验证对话、上传、知识库和团队权限隔离。
