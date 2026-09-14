# Relay Console 真实后端合同审计（2026-09-14）

> 本文保留联调开始时的审计快照，路径与行号对应整改前状态。整改、部署与最终验收结果见 [本轮验收报告](README.md)，不能将本文的“当前缺失”作为最终实现状态。

## 启动与流量入口事实

- 当前运行中的 Compose 项目为 `shortlink-local-dev`；Docker 标签指向生成文件 `.work/local-dev/compose.yaml`，前端容器把 `frontend/console-vue/dist` 只读挂到 `/usr/share/nginx/html`，把 `.work/local-dev/nginx.conf` 只读挂到 `/etc/nginx/nginx.conf`。仓库受版本控制的 `deploy/` 下没有生产 Compose 或前端 Nginx 文件，只有测试 Compose。
- 当前本机入口是 `http://127.0.0.1:5174`（Nginx）与 `http://127.0.0.1:19080`（APISIX）。Nginx 对 `/` 使用 SPA fallback，对 `/api/` 原样代理 `apisix:9080` 并强制 `Host: admin.local.test`（`.work/local-dev/nginx.conf:12-29`）。Vite 开发代理同样指向 `127.0.0.1:19080` 并设置该 Host（`frontend/console-vue/vite.config.js:22-30`）。
- APISIX 公开管理路由只匹配 `/api/short-link/admin/v1/*`、`/api/short-link/v1/user`、`/api/short-link/v1/user/*`，上游 `${ADMIN_UPSTREAM_HOST}:8002`；Agent chat 有 50 秒专用 read timeout，其他管理请求 10 秒（`deploy/apisix/apisix.yaml:18-83`）。Redirect 使用独立 Host 与 8003（同文件 `84-112`）。Admin 生产配置固定业务端口 8002、Command 默认 8001、Analytics 默认 8004、Agent 默认 8010（`services/admin/src/main/resources/application-production.properties:30-31,54-58`）。
- 旧前端 HTTP 客户端 `baseURL=/api/short-link`，每次请求发 `Token` 与 `Username`，60 秒超时；401 会清除本地会话（`frontend/console-vue/src/api/axios.js:28-52`）。这是后端真实会话头合同，不是 `Authorization: Bearer`。Admin 入站过滤器读取 `username`/`token`，token 格式为 `[A-Za-z0-9_-]{20,256}`（`services/admin/src/main/java/com/jupiter/shortlink/admin/common/biz/user/UserTransmitFilter.java:108-120`）。

## 公网端点矩阵

所有 JSON 业务响应默认是 `Result<T> = {code,message,data,requestId,success}`，成功码字符串为 `"0"`（`services/admin/src/main/java/com/jupiter/shortlink/admin/common/convention/result/Result.java:12-35`）。导出端点是文件流例外。

| 领域 | method + path | query / body | 成功 data / HTTP | 首要验证 |
|---|---|---|---|---|
| 初始化 | GET `/api/short-link/admin/v1/user/initialization` | 无 | `{state,groupId,nextRetryAt,reason}` | 登录前是否允许访问；非 READY 时 UI 阻断业务 |
| 初始化重试 | POST `/api/short-link/admin/v1/user/initialization/retry` | 无 | 同上 | 是否必须已登录、幂等与冷却时间 |
| 登录 | POST `/api/short-link/admin/v1/user/login` | `{username,password}` | `{token}` | 真实 Jupiter 用户；随后所有保护请求同时带 Username/Token |
| 用户 | POST `/admin/v1/user`; PUT `/v1/user`; GET `/v1/user/{username}`; GET `/v1/user/has-username` | 注册/更新 body；其余 path/query | 用户或空 | APISIX 两种前缀均已开放；不要擅自改成 Bearer |
| 组 | GET/POST/PUT/DELETE `/api/short-link/admin/v1/group`; POST `/api/short-link/admin/v1/sort` | GET 无；save `{name}`；update `{gid,name}`；delete `gid`; sort 数组 `{gid,sortOrder}` | 列表或空 | 默认组不可删、非空组删除、排序完整性 |
| 短链 | GET `/api/short-link/admin/v1/page` | `gid,current,size,orderTag,statsSnapshotId,statsEnd` | MyBatis `Page<ShortLinkPageRespDTO>` | `linkId` 可能超 JS 安全整数；统计排序需要快照 |
| 创建/更新 | POST `/api/short-link/admin/v1/create`; POST `/update` | create `{requestId,domain,originUrl,gid,createdType,validDateType,validDate,describe}`；update 另含 `{expectedVersion,fullShortUrl,originGid}` | 创建详情/空 | `requestId` 幂等；更新和回收必须回传版本 |
| 批量创建 | POST `/api/short-link/admin/v1/create/batch` | `{requestId,domain,originUrls,describes,gid,createdType,validDateType,validDate}` | inline 为 HTTP 200；异步含 `jobId` 时 HTTP 202，`resultUrl=/batches/{jobId}/rows` | 前端不能把 202 当失败，也不能假定总是 inline；DTO 见下 |
| 批任务 | POST `/api/short-link/admin/v1/batches/imports`; GET `/{job}`; GET `/{job}/rows`; POST `/{job}/cancel` | import `{requestId,gid,object:{bucket,key,version,sha256,bytes}}`; rows `after=0&limit=100` | import 202；status/rows envelope | 这是对象存储导入，不是 JSON URL 数组；rows 是 cursor 式 `after` |
| 回收站 | POST `/recycle-bin/save`; GET `/page`; POST `/recover`; POST `/remove`（均 admin/v1） | mutation `{expectedVersion,gid,fullShortUrl}`；page `gidList,current,size` | 空 / `Page<ShortLinkPageRespDTO>` | 恢复/删除使用当前 gid 与 expectedVersion，避免旧行操作 |
| 单链统计 | GET `/api/short-link/admin/v1/stats` | `gid,fullShortUrl,startDate,endDate` | `StatsEnvelope` | link 作用域要求 gid + fullShortUrl |
| 组统计 | GET `/api/short-link/admin/v1/stats/group` | `gid,startDate,endDate` | `StatsEnvelope` | 最多 500 条授权短链且最长 7 天 |
| 访问记录 | GET `/stats/access-record` 或 `/group` | 上述 scope + `current,size`; 第 2 页起还须 `snapshotId,cursor` | `StatsEnvelope` | `current>1` 缺 snapshot/cursor 会直接 ClientException（`ShortLinkStatsController.java:90-92`） |
| Agent | GET `/api/short-link/admin/v1/agent/health`; POST `/agent/chat` | chat `{sessionId,agentType,message}` | 透传 `Result<Object>` | chat 入口 50 秒，但旧 Axios chat 设 120 秒，实际网关先在 50 秒断开 |
| 风控 | GET `/risk/commands/{id}`, `/current-policies?linkId&cursor`, `/groups/{gid}/overview`, `/groups/{gid}/short-links`, `/short-links?gid&domain&shortUri`, `/events?...`; POST `/reviews`, `/policies/{id}/disable` | review `{eventId,targetType,gid,domain,shortUri,fullShortUrl,reviewAction,reviewer,reviewNote}`；disable `{gid,reviewer,reason,traceId,commandId,linkId}` | typed DTO/Map envelope | command 返回可能是异步未知态；cursor 与 review/disable 的幂等恢复必须验证 |
| 导出 | GET `/api/short-link/admin/v1/create/batch/export?requestId=...`; GET `/api/short-link/admin/v1/batches/{job}/export` | query/path | XLSX / CSV 文件流 | 不可按 JSON envelope 解码；job 必须终态，否则 409；容量满 429 |

Controller 证据：`UserController.java:27-88`、`GroupController.java:27-63`、`ShortLinkController.java:31-67`、`RecycleBinController.java:32-60`、`BatchJobController.java:21-42`、`BatchExportController.java:50-97`、`ShortLinkStatsController.java:22-92`、`AgentController.java:28-48`、`RiskCenterController.java:31-80`，均位于 `services/admin/src/main/java/com/jupiter/shortlink/admin/controller/`。

## DTO 与统计 envelope

- 初始化：`state, groupId, nextRetryAt, reason`；READY 时 `nextRetryAt=null`（`AccountInitializationStatus.java:4-11`）。登录仅返回 `token`（`UserLoginRespDTO.java:13-17`）。
- 页面行：`linkId,routeVersion,targetRevision,ownershipVersion,enableStatus,title,metadataStatus,id,domain,shortUri,fullShortUrl,originUrl,gid,validDateType,validDate,createTime,describe,favicon,todayPv,todayUv,todayUip,totalPv,totalUv,totalUip`（`ShortLinkPageRespDTO.java:11-71`）。
- 批量创建：`jobId,state,resultUrl,total,baseLinkInfos`（`ShortLinkBatchCreateRespDTO.java:15-24`）。批任务 status 为 `jobId,state,totalRows,validRows,invalidRows,succeededRows,failedRows,error,checksum,actualBytes`，row 为 `row,state,linkId,result,error`（`BatchCommandRemoteService.java:13-27`）。
- StatsEnvelope 只有三个松类型顶层字段：`metrics: Map<String,Object>`, `items: List<Map<String,Object>>`, `meta: Map<String,Object>`（`dto/resp/analytics/StatsEnvelope.java:7-8`）。Admin 不枚举、校验或补齐具体统计字段，只给 item 补 `gid,domain,shortUri,fullShortUrl`，给 meta 补 `tenantId`（`AgentAnalyticsFacade.java:125-144`）。
- `metrics` 的窗口摘要可包含 `pv,uv,uip,denied,daily,hourStats,weekdayStats,browserStats,osStats,deviceStats,topIpStats,countryStats,localeCnStats,networkStats,uvTypeStats,repeatVisitRatio` 及 share 字段；这是超过“10 维”的动态 Map 合同，前端必须容忍缺失。字段生成证据在 `services/shortlink-analytics-api/src/main/java/com/jupiter/shortlink/analytics/api/MetricDimensions.java:88-112,142-186,224-230`。
- `meta.completeness` 是 `COMPLETE|PARTIAL`；`meta.dimensionQuality` 按维度给质量。UV 新老访客质量还会含 `status,historyStart,unknownUv,unknownCount,coverage,reason`（`AnalyticsQueryService.java:415-430`; `VisitorHistory.java:193-229`）。旧前端适配会保留 envelope，并把 `metrics.requested` 摊平；不会用页长伪造 total（`frontend/console-vue/src/utils/analyticsStats.js:27-50,53-86`）。
- 访问记录新合同使用 `items`，其 item 至少可能含 `linkId,occurredAt,visitorHash,ipHash,kind,status,browser,os,device,country,province,city,network,geoStatus,geoVersion,uvType,refererDomain`，再由 Admin 补短链身份；旧 `ShortLinkStatsAccessRecordRespDTO` 的 `ip,user,createTime` 已不是可靠新合同。分页位置是 `meta.snapshotId` 与 `meta.nextCursor`；前端限制 cursor 非空且最多 8192 字符并固定 snapshot（`analyticsStats.js:54-85,147-164`）。
- 在线统计边界由 Admin `AgentAnalyticsFacade.query` 强制：`pageSize 1..500`、时间正且不超过 7 天、授权 scope 不超过 500 links 且不能存在资源解析 `nextCursor`（`AgentAnalyticsFacade.java:85-102`）。同步 metrics controller 固定 pageSize=500；访问记录取请求 size（`ShortLinkStatsController.java:22-87`）。离线统计 job 可到 180 天，但目前只有内部 Agent tool 暴露，没有公网 `/api/.../statistics/jobs` controller（`AgentAnalyticsFacade.java:148-200`）。

## 确定合同缺口（TOP 10）

1. 新 Relay 的 `frontend/console-vue/src/relay/api/http.js` 当前不存在；若独立 preview/新 UI 不复用 `src/api/axios.js`，会缺少 Username/Token、BigInt 安全解析和 401 清理。
2. 新 UI 若使用 `Authorization: Bearer` 会认证失败；真实合同是两个自定义头。登录响应也没有 username/user profile，必须由输入值持久化并另查用户。
3. 初始化状态端点未出现在旧 `src/api/modules/user.js`；新 UI 若登录后立即加载组，可能把尚未 READY 的默认组初始化当空数据或失败。
4. 批量创建存在 200 inline 与 202 job 两条成功路径（`ShortLinkController.java:55-67`）；旧 `addLinks` 只发请求，没有 job status/rows/cancel/export API，无法闭环大批次。
5. 两个导出端点返回 XLSX/CSV 流而非 Result envelope；通用 JSON 适配会损坏文件。已提交结果最多 500 行、响应读取预算 2 MiB；job export 每页 500、总计最多 5,000,000 行且 5 分钟（`BatchExportController.java:106-139,143-180`）。
6. 旧前端仅声明短链/组同步 stats 与访问记录 API，没有公开风险端点、批任务端点、初始化端点；Relay 必须新增适配，不能从旧模块名推断已覆盖。
7. StatsEnvelope 是动态 Map，Admin 只验证 `meta/items`，甚至不要求 `metrics` 非空（`AgentAnalyticsFacade.java:125-144`）；每个维度必须按 `dimensionQuality` 呈现 COMPLETE/PARTIAL/UNKNOWN，不能用 0 填缺失。
8. 在线统计同时有 7 天与 500 links 硬边界；所谓“500+ inline job”不会自动降级为 job，而是 `TOO_LARGE`。公开 controller 又没有统计 job 端点，所以大组/长区间目前对普通 UI 是后端未暴露能力。
9. 访问记录不是 offset 随机翻页：第 2 页必须携带上一页 `snapshotId+nextCursor`，且不能跳到未缓存页。旧表格页码交互若直接请求 `current=N` 会失败。
10. 本地 Nginx 配置位于生成的 `.work/local-dev/nginx.conf`，不是可发布源文件；生产部署材料没有前端镜像/静态站点 Nginx 合同。当前 `5174` 可用于本地验收，不能据此断言正式 Host/静态部署已就绪。

## 最小端点验收顺序

| 步骤 | 请求 | 断言 |
|---|---|---|
| 1 | 登录 | HTTP 200，`code="0"`，token 满足格式；保存 username/token |
| 2 | 初始化状态，必要时 retry 后轮询 | `state=READY` 且 `groupId` 可用；失败原因可展示 |
| 3 | 用户信息 + group list | 会话头被接受；组归属当前用户 |
| 4 | create（固定 requestId）后 page | 重试不重复创建；页面 `linkId` 保持字符串精度；版本字段存在 |
| 5 | update → recycle save → page → recover/remove | 每一步用最新 expectedVersion；跨组/旧版本失败且不误改 |
| 6 | batch 小批与大批 | 同时覆盖 HTTP 200 inline 和 202 job；轮询 status，用 after 读取 rows；终态才导出 |
| 7 | 单链与组 stats（1 天、7 天、超过 7 天） | 1/7 天成功，超 7 天明确失败；展示 completeness 与每维质量，不补零 |
| 8 | access records 两页 | 第 2 页沿用 snapshotId、发送 nextCursor；重复/缺 cursor 明确失败 |
| 9 | 500 与 501 links 的组统计 | 500 内成功；501/仍有资源 nextCursor 返回 TOO_LARGE，UI 给可行动提示 |
| 10 | Agent health/chat + risk 查询/审核 | chat 在 50 秒网关预算内；异步 command 可轮询；审核失败不伪装成功 |
| 11 | 两种 export | 使用 blob/stream，校验 Content-Type/Disposition；非终态 job 得 409，容量耗尽得 429 |
| 12 | Host 边界 | 仅管理 Host 访问管理 API；短链 Host 只接受 redirect；客户端伪造 `x-shortlink-*` 不影响身份 |
