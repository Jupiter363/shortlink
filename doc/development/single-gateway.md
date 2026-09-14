# APISIX 单网关与 Admin 入口

管理请求直接经过 `APISIX → Admin → Command / Analytics API / Agent`，短链请求经过 `APISIX → Redirect`。Spring Cloud Gateway 独立服务已移除，Maven reactor 为 11 个模块、6 个 Spring 常驻服务。

## 职责与信任边界

| 位置 | 职责 | 依据 |
| --- | --- | --- |
| APISIX | TLS、管理域名与短链域名路由、原始路径检查、身份头清洗、速率/连接限流、EDGE 事件 | 入口规则与真实客户端连接 |
| Admin 外部管理入口 | 验证 APISIX 实际连接来源与 Host；读取 Redis Session；复核 MySQL 当前账号、authVersion、禁用/删除状态 | 服务端会话与当前账号事实 |
| Admin 资源保护 | 本节点在途请求额度、普通/批量请求体大小、读体时间与共享内存预留额度 | Admin 实际服务容量 |
| Admin 内部 Agent Tools | 独立内部服务令牌、受信任主体与当前账号授权 | 原有 Agent 内部协议，不向公网开放 |
| Redirect | 跳转解析、路由缓存、策略执行、业务结果与点击事件 | 当前路由与策略事实 |

APISIX 是唯一网关；Admin 的会话校验属于业务服务鉴权。没有 `forward-auth` 子请求，也没有将 Redis 会话结构与账号权限重新实现为 Lua。APISIX 按来源 IP 的入口限流与 Admin 对所有请求的本节点容量保护约束不同资源，不能互相替代。

APISIX 删除客户端提交的 `x-shortlink-*`、`x-agent-*`、旧身份头及内部令牌，保留管理登录凭证 `username` / `token` 给 Admin。Admin 从有效会话建立 `UserContext`，不再信任外部提交的租户或权限版本头。业务服务间的 `X-Internal-Token` 协议继续有效；它不再用于授权 Admin 公网管理请求。

APISIX 的边界插件只清除伪造的转发来源与协议，来源头由 APISIX 原生代理指令统一生成。避免原来的插件写入一次、代理层再追加一次，导致 `X-Forwarded-For` 出现重复客户端 IP。Admin 和 Redirect 仍只信任实际 socket peer 在配置的 APISIX CIDR 内的请求。

## 路由与配置

| 公网入口 | 路径 | 目标 |
| --- | --- | --- |
| 管理域名 | `/api/short-link/admin/v1/*`、`/api/short-link/v1/user`、`/api/short-link/v1/user/*` | `${ADMIN_UPSTREAM_HOST}:8002` |
| 短链域名 | `/{shortUri}`，仅 GET / HEAD | `${REDIRECT_UPSTREAM_HOST}:8003` |

`/internal/**` 和 `/actuator/**` 不加入公网路由。Admin 的健康/指标检查通过绑定 loopback 的管理端口 `8102` 访问；不再部署或监控 Java Gateway 的 `8000/8100`。

Admin 启动仍显式使用 `application-production.properties`，新增入口参数：

- `ADMIN_ALLOWED_HOSTS`：规范化后的管理域名允许列表，和 APISIX 管理路由匹配。
- `APISIX_CIDRS`：Admin 实际看到的 APISIX 连接来源，限定到受控节点或网络；不能把客户端 XFF 当成 peer。
- `ADMIN_UPSTREAM_HOST`：APISIX 使用的稳定 Admin 内网 DNS / LB 地址，替代 `GATEWAY_UPSTREAM_HOST`。

未配置管理 Host / 可信来源时，管理入口拒绝访问。登录、注册与用户名查询也必须经过可信入口；它们仅跳过会话查询，不能跳过入口检查。

## 登录兼容与异步请求

公开管理接口仍是三个精确“方法 + 路径”：

- `POST /api/short-link/admin/v1/user/login`
- `POST /api/short-link/admin/v1/user`
- `GET /api/short-link/v1/user/has-username`

其他管理调用继续提交 `username` / `token` 请求头。`check-login`、`logout` 的查询参数必须和本次已验证会话一致，避免借自己的会话操作另一用户或另一 token；登录态查询复用本次请求的验证结果。会话过期、损坏、权限版本失效或账号禁用返回拒绝，Redis / 权威账号依赖不可用时返回 503。

新签发的登录令牌从登录时起固定有效 **30 天**（一个月按 30 × 24 小时计算）。Redis 会话键保留 30 天，每个令牌同时校验自身 `expiresAt`；后续登录刷新共享键 TTL，不会延长旧令牌的有效期。退出登录、改密和禁用账号仍按原有撤销规则生效。更新前签发的令牌保留原到期时间，重新登录后获得 30 天令牌。前端勾选“记住登录”才会跨浏览器重启保留凭证，否则仅在当前标签页会话内保存；Agent 内部委派凭证及会话授权期限独立管理。

Servlet 异步读体与业务异步响应保留已验证主体，分派时核对原方法/路径；不会把线程局部身份泄露到后续请求。Agent 内部 Tool 继续使用独立入口与授权协议，统计 Tool 仍复用 Admin → Analytics API 链路。

## 保护与超时

APISIX 继续使用 `limit-req` 和 `limit-conn`，保留原有节点/路由/来源 IP 配置。管理 upstream 的读取空闲超时由 5 秒调整为 10 秒，覆盖 Admin 下游 Feign 的 5 秒读取预算；连接 1 秒、发送 3 秒不变。这些是分阶段或空闲超时，不是整个业务执行的总时限。公开 Redirect 的连接/发送/读取预算和 Kafka 发送组织没有随本次迁移调整。

Admin 保留 64 个在途请求、满额立即 429；普通请求体 256 KiB，精确批量创建 POST 8 MiB，共享预留额度 64 MiB，读体总时限 5 秒。按请求上限预留额度，64 MiB 不等于 JVM 实际 RSS 的硬上限。容量额度与读体额度均须在完成、失败、超时及异步终态释放。APISIX 提供外层 8 MiB 上限，Admin 检查实际收到的字节，不只依赖 Content-Length。

Admin 的 Lettuce 客户端使用 150ms 命令等待、1s 建连预算，每连接最多排队 256 条命令；断线时拒绝新增命令，并允许自动重连。参数统一来自 `shortlink.admin.ingress.session-timeout`、`redis-connect-timeout`、`redis-request-queue-size`，避免 Spring Redis 与入口配置各维护一套值。读体期限从入口认证成功、开始接收正文时计算，不包含先前的会话与账号校验耗时。

管理路由使用内建 `proxy-control` 的 `request_buffering: false`，APISIX 将请求体流式转发给 Admin，避免两层完整缓存同一正文，也让 Admin 的绝对读体期限约束慢速上传。限流、头清洗和外层体积限制仍由 APISIX 执行，公开跳转路由没有启用此插件。

## 开发与验收

当前 Maven 集成入口为 [`run-admin-redirect-it.ps1`](../../scripts/integration/run-admin-redirect-it.ps1)，原 Java Gateway 的 Redis 集成测试与安全测试迁移到 Admin。创建/跳转 E2E 仅启动 Command、Admin、Redirect 三个真实 JAR 和 APISIX，不启动 Agent 或 Analytics；具体运行参数见[脚本导航](../../scripts/README.md)。

历史压测与验收保留原始版本、配置和结果。单网关去掉的是管理流量的一跳，公开跳转此前已经直达 Redirect；不能把旧的跳转 QPS 或旧 Gateway 测试记录当作本次迁移后的新实测。

本次独立验收记录见[2026-09-11 单网关迁移](../integration/single-gateway-2026-09-11/README.md)。
