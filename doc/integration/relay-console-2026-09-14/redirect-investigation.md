# 新建短链首次跳转 503 调查

调查时间：2026-09-14 21:19（Asia/Shanghai）。范围仅为 Redirect 服务、只读运行时日志/指标和指定 UAT 链接。

## 结论

`H512xqSxG` 的路由与目标均正确，APISIX 也能把请求交给 Redirect。此前两次 503 是冷路径在 Redirect 的 500 ms 全请求预算内未完成，不是 Bloom 否定、域名不匹配、目标非法或服务不健康。冷路径串行执行路由权威读取，再执行 Command 风控策略读取；外层 `.timeout(500 ms)` 取消正在读取的 WebClient response body，于是出现 `ReactorClientHttpResponse body has been released already due to cancellation`。缓存温热后，同一链接和既有链接均返回 302。

本轮已拆分预算：`requestTimeoutMillis=500` 继续约束每次 JDBC/HTTP authority I/O，新增 `endToEndTimeoutMillis=900` 只约束完整跳转链；authority TTL 仍为 1000 ms，PolicyResolver 的 500 ms minimum remaining proof 要求不变。最终 route active/validUntil 与 policy 再评估仍然 fail closed。APISIX redirect read timeout 同步从 1 秒调到 1.5 秒，为 Redirect 返回 503 和传输响应保留余量。

Spring Boot Binder 明确使用加有 `@ConstructorBinding` 的 canonical record 构造器，因此配置缺省值 900 不会误走兼容构造器。旧 18 参数 Java 构造器为较短 TTL 派生 `min(900, authorityTtlMillis - 1)`，避免把原先如 TTL=800、I/O=500 的合法调用无故变成非法；新模型仍要求两个预算之间至少存在 1 ms 的严格间隔。

## 运行证据

- 使用真实短链 Host `localhost:19080` 请求 `GET http://127.0.0.1:19080/H512xqSxG`，得到 `302 Location: https://example.com/?relay-uat=single`。既有 `68hlRoGwl` 同时得到 302。
- APISIX 当前短链 Host 是 `localhost`，Redirect 允许域名是 `localhost:19080`（`.work/local-dev/compose.yaml:219,266`）。使用错误 Host 会得到 APISIX 404，与本次 503 可明确区分。
- Redirect Micrometer 指标显示 `/{shortUri}`：503 count=2、累计约 1.249 秒、max≈0.744 秒；302 count=2、累计约 0.215 秒。membership check 的四次结果全部是 `UNKNOWN`，`MAY_EXIST=0`、`DEFINITELY_ABSENT=0`，因此 Bloom 没有拒绝新链接。
- membership 生产默认关闭，只有显式认证基线后才启用（`services/shortlink-redirect/src/main/resources/application-production.properties:50-56`）。UNKNOWN 会继续进入权威解析。
- Redirect controller 在路由解析与策略判断组成的整个 reactive chain 外设置 `timeout(requestTimeoutMillis)`，任何错误统一返回 503 `AUTHORITY_UNAVAILABLE`（`RedirectController.java:101-118`）。
- `requestTimeoutMillis` 当前是 500 ms，authority TTL 是 1000 ms（`application-production.properties:43-46`）。JDBC route read 自身也使用同一个 500 ms timeout（`JdbcRouteAuthority.java:29-31,35-99`）；HTTP policy read 再使用同一个 500 ms timeout（`HttpPolicyAuthority.java:16-33`）。两次读取是串行的（`RedirectController.java:101-130`）。
- PolicyResolver 要求策略 proof 剩余时间大于该 500 ms 预算（`PolicyResolver.java:97-123`；装配位于 `RedirectRuntimeConfiguration.java:128-140`）。这解释了为什么简单放宽外层截止点会改变安全模型。
- APISIX redirect upstream read timeout 是 1 秒（`deploy/apisix/apisix.yaml:104-111`）。若外层扩到接近两次内部 timeout 之和，网关可能先断开，并再次制造 cancellation。
- 选择性容器日志仅出现一次与失败窗口吻合的 dropped error：response body 因 cancellation 已释放；没有 Redis、Bloom、JDBC、非法目标或 unknown-host 错误。健康探针为 UP，事件 Kafka callback failure 为 0。

## 修复边界

当前性能配置从“每次 I/O 500 ms、整链 500 ms、APISIX 1 s”变为“每次 I/O 500 ms、整链 900 ms、APISIX 1.5 s”。这允许两段约 300 ms 的冷 authority 查询完成；超过 900 ms 仍返回 503，不延长 1000 ms proof TTL，也不跳过 policy。

还应给 `AUTHORITY_UNAVAILABLE` 增加有界 reason 指标（route timeout、policy timeout、origin busy、proof expired），避免目前 controller 把所有异常折叠为同一 503 后只能依赖 dropped-error 日志归因。指标不得包含 domain、shortUri、tenant、token 或 origin URL。

## 验证矩阵

| 场景 | 预期 |
|---|---|
| 正确 Host，新链接，冷 route + 冷 policy | 在约定端到端预算内 302；否则单一 503 且 reason 指标精确 |
| 正确 Host，同一链接温缓存 | 302，Location 完全匹配创建目标 |
| 错误 Host | APISIX 404，不进入 Redirect |
| membership disabled/UNKNOWN | 不做本地 404，继续权威读取 |
| certified DEFINITELY_ABSENT | 404；proof 过期时在原截止点内绕过 Bloom 回源 |
| route 存在、policy authority 超时 | fail closed 503，无 click event |
| route/policy proof 在最终判断前过期 | 503，无 redirect、无 click event |
| APISIX timeout | 必须大于 Redirect 端到端预算；客户端不应先看到网关断开 |

## 离线验证

以下限定测试通过，共 32 项，0 failure、0 error（含 4 项真实 Spring Boot Binder 测试）：

```powershell
mvn -pl services/shortlink-redirect -am "-DskipTests=false" `
  "-Dtest=RedirectControllerTest,BloomRedirectControllerTest,ProofBudgetRedirectTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" test
```

本机使用 Java 21，而仓库当前 Byte Buddy 版本正式支持到 Java 20；不带 `net.bytebuddy.experimental=true` 时 Mockito inline mock 在测试构造阶段失败，业务测试不会执行。这是测试运行时兼容问题，不是 Redirect 断言失败。

新增虚拟时间回归证明：route 300 ms + policy 300 ms 在旧 500 ms 点仍未完成、但在 900 ms 内返回 302；450 ms + 451 ms 超过新整链预算时返回 503 且不产生 click。原有 proof transition/expiry 测试继续验证最终证明过期时拒绝跳转。

## 修复后的真实首次访问验收

2026-09-14 21:40（Asia/Shanghai）已重建并重启 Redirect，APISIX 已成功 reload 通过 YAML 校验。创建三个不同的新 linkId，各自仅发一次经 `localhost:19080` 的 GET，没有预热或失败重试：

| 第一次 GET | HTTP | 耗时 | Location |
| --- | --- | --- | --- |
| 新链 1 | 302 | 750.57 ms | 与创建目标完全一致 |
| 新链 2 | 302 | 56.84 ms | 与创建目标完全一致 |
| 新链 3 | 302 | 31.19 ms | 与创建目标完全一致 |

首个有效冷请求超过原先 500 ms 的整链截止点，在新 900 ms 应用预算内完成。随后同分组真实统计 API 观测到 PV=3、UV=3、UIP=1，证明点击已到达异步统计读链。快照仍为 PARTIAL、采集质量 UNKNOWN（PRODUCER_SAMPLE_STALE），不能由这三个样本宣称全量采集完整。

逐次 HTTP 与统计证据见 [redirect-uat.json](redirect-uat.json)。此次为低频功能联调，不是吞吐量或延迟分位数压测。
