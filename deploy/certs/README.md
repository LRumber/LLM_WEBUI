# TLS certificates

Place the matching certificate and private key here before deployment:

- `sitp.ac.cn.pem`
- `sitp.ac.cn.key`

The files are mounted read-only by the gateway and are ignored by Git. On Ubuntu, protect the private key with:

```bash
chmod 600 deploy/certs/sitp.ac.cn.key
```
