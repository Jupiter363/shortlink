# 首次正式启动 TLS

`config.yaml` 和仓库中的 `apisix.yaml` 是隔离 HTTP 组件测试输入。正式入口按
[ETCD.md](ETCD.md) 使用 traditional + etcd；`render-tls-config.py` 生成的 `apisix.yaml`
作为 HTTPS Admin API 导入的公共资源清单，仅映射宿主 `443` 到 APISIX `9443`。
容器 `9080`、Admin API、控制和指标端口不对公网发布。TLS 仅允许 1.2/1.3。

从证书管理系统挂载证书链和私钥，设置以下环境变量后运行 Python 3：

```sh
export APISIX_TLS_CERT_FILE=/run/secrets/shortlink-fullchain.pem
export APISIX_TLS_KEY_FILE=/run/secrets/shortlink-privkey.pem
export APISIX_TLS_SNIS=admin.example.com,s.example.com
export MANAGEMENT_HOST=admin.example.com
export SHORTLINK_HOST=s.example.com
python3 deploy/apisix/render-tls-config.py --output /run/shortlink-apisix/public
```

生成器检查 PEM/私钥匹配，不输出证书私钥内容；运行目录限当前账户访问。
生成的 `apisix.yaml` 含私钥，必须作为秘密文件管理，禁止提交、日志打印或复制到公开制品。
证书 SAN 必须涵盖全部 SNI；使用证书签发机构可信链验证实际 HTTPS 握手和主机名，不能以
`curl -k` 作为正式验收。自定义短域名必须同步证书、`ssls.snis`、对应 route.hosts 和 Redirect allowed-hosts。

正式容器挂载 ETCD.md 生成的 config.yaml；此处生成的 public/apisix.yaml 仅供导入器读取。
隔离 standalone TLS 组件测试才挂载这里生成的 config.yaml 和 apisix.yaml 两个文件到
`/usr/local/apisix/conf/`，并挂载 `deploy/apisix/plugins` 到 `/opt/shortlink`。
Standalone 容器或正式导入环境设置 `KAFKA_HOST`、`APISIX_INSTANCE_ID`、两个 Host，以及
`ADMIN_UPSTREAM_HOST`、`REDIRECT_UPSTREAM_HOST`；上游端口固定 8002/8003。每节点 hostname 唯一。
使用已验证的 `apache/apisix:3.11.0-debian` 制品，仅发布 `443:9443`。
Admin、Redirect 等 Java 内部服务保持服务网络可达，不能绕过 APISIX 直接公开。

轮换证书时重新生成受保护清单，正式模式通过 HTTPS Admin API 更新对应 ssls，并验证新证书链、
到期时间、SNI、GET/HEAD 和 Host 拒绝行为。文件生成不代表执行部署，本文件也不新增审批步骤。
