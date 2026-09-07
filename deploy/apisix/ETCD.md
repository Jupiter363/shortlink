# 正式运行：APISIX 3.11 traditional + etcd

正式部署使用 etcd 保存路由、全局插件和公网 TLS 资源。`config.yaml` 的 standalone
模式保留作组件验收；不能把一次 standalone 验证替代正式 etcd 初始化。
`config-etcd.template.yaml` 是覆盖模板，生成器合并同一 `config.yaml` 的插件清单、
8MB body 边界、连接超时和指标配置。资源导入读取同一 `apisix.yaml`，不维护第二份路由。

管理面使用 APISIX 原生 HTTPS Admin API、独立 API key 和 IP 白名单。其 9180 端口
只绑定/接入管理网络，防火墙只允许部署主机访问；不映射到公网。公网只发布 `443:9443`。
etcd 2379/2380 也只在管理网络，生产使用三个成员、持久卷、HTTPS 和账户授权；成员间 TLS
由 etcd 部署负责。这里的单成员隔离测试不代表验证了生产 etcd 故障容量。

## 配置生成与首次导入

Python 3 环境安装本目录固定依赖 `pip install -r deploy/apisix/requirements.txt`。
证书和密码从秘密系统挂载，以下均是示意路径。Admin 证书 SAN 必须覆盖导入工具使用的
管理域名；etcd CA 必须验证所有 endpoint 的域名，禁止关闭 TLS 校验。

```sh
# 在执行生成器的受保护部署主机上；这些文件不进入版本库或公开制品。
export APISIX_ADMIN_KEY_FILE=/run/secrets/apisix-admin-key
export APISIX_ENCRYPTION_KEY_FILE=/run/secrets/apisix-encryption-key
export APISIX_ETCD_PASSWORD_FILE=/run/secrets/apisix-etcd-password
export APISIX_ADMIN_ALLOWED_CIDRS=10.20.1.10/32
export APISIX_ADMIN_BIND_IP=10.20.2.10
export APISIX_ETCD_ENDPOINTS=https://etcd-1.internal:2379,https://etcd-2.internal:2379,https://etcd-3.internal:2379
export APISIX_ETCD_USERNAME=shortlink-apisix
# 下列三项是 APISIX 容器内只读秘密挂载路径。
export APISIX_ETCD_CA_FILE=/run/secrets/etcd-ca.pem
export APISIX_ADMIN_CERT_FILE=/run/secrets/admin-fullchain.pem
export APISIX_ADMIN_TLS_KEY_FILE=/run/secrets/admin-privkey.pem
python3 deploy/apisix/render-etcd-config.py --output /run/shortlink-apisix/traditional
```

管理 key 至少 32 字符且随机生成。APISIX 数据加密 key 必须是独立的随机 16 字节 ASCII
秘密；它用于 etcd 内敏感字段加密，必须持久保管，不能每次启动随机生成。etcd 账户仅授予
`/shortlink/apisix` 前缀读写权限，不能用 root 作为 APISIX 运行账户。
创建账户、成员集群、证书和管理网络是正式环境准备；生成器只生成本机受保护配置文件。

先按 [TLS.md](TLS.md) 的证书输入和 `render-tls-config.py` 生成带 `ssls` 的公共资源清单，
保留生成的 `apisix.yaml` 作为本次导入输入。**传统容器只挂载 etcd 生成的 config.yaml** 到
`/usr/local/apisix/conf/config.yaml`，以及 `plugins` 到 `/opt/shortlink` 和所需秘密文件；
不挂载 standalone `apisix.yaml`。使用 `apache/apisix:3.11.0-debian`，为每个实例配置稳定且
互不相同的容器 hostname；容器网络场景可绑定 0.0.0.0，但必须仅由管理网络接入 9180，
不能因为设置了 API key 就发布公网端口。

```sh
export KAFKA_HOST=kafka.internal
export APISIX_INSTANCE_ID=shortlink-edge-prod
export MANAGEMENT_HOST=admin.example.com
export SHORTLINK_HOST=s.example.com
export GATEWAY_UPSTREAM_HOST=shortlink-gateway.internal
export REDIRECT_UPSTREAM_HOST=shortlink-redirect.internal
python3 deploy/apisix/bootstrap-etcd.py \
  --manifest /run/shortlink-apisix/public/apisix.yaml \
  --admin-url https://apisix-admin.internal:9180 \
  --ca-file /run/secrets/admin-ca.pem \
  --key-file /run/secrets/apisix-admin-key \
  --report /run/shortlink-apisix/bootstrap-report.json
```

导入器通过 HTTPS 按稳定 ID 依次 PUT global_rules、ssls、routes，逐项 GET 核对；报告仅有
资源名与清单摘要，不写私钥或 API key。APISIX 返回的加密私钥不做明文比较，最终必须再做
可信链/SNI 握手验证。每次导入最多 1000 资源、清单 2MB、每次请求 5 秒；失败返回非零。
它不清空 etcd、不删除其他资源、不直接写 etcd，也不会把一次部分成功报告为完成。
首次初始化期间保持公网流量未接入，全部导入和路由验收完成后才开放入口；失败可修复后
重跑同一清单。此流程不是跨资源事务，也不承诺正在接流量时原子切换整份配置。

更新只修改同一资源源文件并重新生成、导入与验证；删除旧域名/资源需显式管理操作，
导入器不会自动推断删除。自定义域名同时覆盖 route.hosts、ssls.snis/证书 SAN、Redirect
allowed-hosts。正式 bootstrap 使用的环境变量会成为 etcd 共享资源；`APISIX_INSTANCE_ID`
因此代表部署标签，logger 在事件里追加本机 hostname 和 boot UUID 区分节点与重启。
boot UUID 与指标同处 shared dictionary，worker reload 保留两者，全实例重启重新生成。
原始 requestId 保留；decisionId 使用 `v1:<occurredAt毫秒>:<完整实例标识>:<受控requestId>`，与事件体共享同一次取时。边界未创建 requestId 时，logger 使用独立 UUID。后缀为最多 256 字符的可见 ASCII，重试复用同一事件；统计入口拒绝 ID 与发生时间不匹配的记录。

etcd 快照、APISIX 加密 key、管理秘密和证书恢复材料需共同纳入受保护备份。恢复到隔离环境后
先验证可解密 SSL、Admin HTTPS/鉴权、资源摘要和公开 GET/HEAD，再恢复接入；不能仅恢复 etcd
快照却丢失 keyring。密钥轮换需按 APISIX keyring 兼容流程保留旧 key 至存量资源重写完成，
不能用当前单 key 模板直接覆盖正在使用的 key。

## 服务发现决策

本次可执行生产配置采用受控内网 DNS 服务名，APISIX upstream 使用固定服务端口
Gateway 8000、Redirect 8003。Nacos 属于旧应用服务发现模式；首版新链路不同时维护
Nacos 和 APISIX 两套服务实例目录。DNS/LB 负责服务节点变更，etcd 管理路由/插件/证书；
两者职责分开。当前决定必须同步到总部署文档，不能宣称未接线的 Nacos 已完成生产验收。

## 已运行的隔离组件验证

`scripts/integration/apisix_etcd_component.py` 创建独立 etcd/APISIX 容器，使用临时证书并验证
CA 和主机名，etcd 开启 TLS 与限定前缀账户。固定测试镜像 etcd 3.5.17、APISIX 3.11.0；
Admin/公网测试端口只映射 localhost 19180/19444/19445。脚本只停止自己创建的容器。
它验证重复导入、鉴权拒绝、同一路由边界、重启持久性，以及真实 Kafka 中的多节点/重启身份。
执行方法及结果见 [组件验收记录](../../docs/integration/component-adapters.md)。

配置依据：[APISIX 3.11 Admin API](https://apisix.apache.org/docs/apisix/3.11/admin-api/)、
[3.11.0 原生 HTTPS Admin 配置实现](https://github.com/apache/apisix/blob/3.11.0/apisix/cli/ngx_tpl.lua)、
[etcd 3.5 认证与角色](https://etcd.io/docs/v3.5/op-guide/authentication/)。
