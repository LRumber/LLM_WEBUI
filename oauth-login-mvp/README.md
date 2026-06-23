# SITP 统一登录 MVP

这个目录是独立测试程序，不依赖当前 LM Web 项目、数据库或第三方 Python 包。它按照现有内网代码的真实请求方式完成：

1. 浏览器访问统一认证授权地址。
2. 认证中心携带 `code` 回调本程序。
3. 使用 POST 请求 Token 接口，参数放在 URL 查询字符串中。
4. 使用 `access_token` 查询参数请求用户信息。
5. 页面展示常用字段和完整 `userInfo` JSON。

## 内网测试

确认测试机器使用认证中心已登记的 IP 和端口。当前样例回调地址为 `http://192.168.88.103:8505`。

```powershell
Copy-Item config.example.json config.json
notepad config.json
python oauth_mvp.py
```

在 `config.json` 中填入已登记的 `client_id` 和 `client_secret`，然后浏览器打开：

```text
http://192.168.88.103:8505
```

Linux 启动方式：

```bash
cp config.example.json config.json
python3 oauth_mvp.py
```

如果测试机器 IP 不是 `192.168.88.103`，需要先让统一身份认证中心登记新的完整回调地址，再同步修改 `redirect_uri`。防火墙需要允许入站 TCP 8505。

## 安全说明

- `config.json` 已被本目录的 `.gitignore` 排除，不要提交客户端密钥。
- 页面不展示 Access Token，服务日志也不会记录回调参数。
- 会话仅保存在进程内存中，重启后自动失效。
- 为兼容现有真实代码，默认不发送 `state`。确认认证中心支持原样返回 `state` 后，可在配置中增加 `"send_state": true`。
