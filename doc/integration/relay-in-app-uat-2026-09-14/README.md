# 内置浏览器 CDP UAT · 2026-09-14

本轮按用户要求，使用 Codex 右侧内置浏览器的原有标签页，对本地 Docker 中的新前端与真实后端进行验收。入口为 `http://127.0.0.1:5174`，短链通过 `http://localhost:19080` 的 APISIX 访问。没有启动独立无头浏览器，没有执行压测。

基线为 main 的 `103e2a19bce3fde6048dccc988bd31dd793361dc`。本轮发现的缺陷由 [Issue #35](https://github.com/Jupiter363/shortlink/issues/35) 跟踪，分支为 `jupiter/fix-in-app-uat`。本报告与[上一轮独立浏览器联调](../relay-console-2026-09-14/)分开计数。

最终记录 34 项检查通过，包含修复后的复验，不等于全部首次请求均成功。[观测摘要](observations.json)与下文保留原生下载未验证、服务更新后单次 Redis 超时、统计 PARTIAL 及风险写入未覆盖的边界。

## 已实际执行的业务流程

| 范围 | 实际结果 | 证据 |
| --- | --- | --- |
| 认证 | Jupiter 登录、READY 初始化、硬刷新恢复、真实退出、退出后直达工作区被拦截，再次登录 | [检查记录](checks.json)、[脱敏网络摘要](network.json) |
| 分组与创建 | 创建独立 UAT 分组；无效 URL 被拦截；有效创建、复制完整地址、生成可加载的 PNG 二维码 | [创建](screenshots/02-created.png)、[二维码](screenshots/03-qr.png) |
| 真实跳转 | 新短链 `1sXqGAjVw` 在额外的内置浏览器标签页中访问一次，抵达 `https://example.com/?shortlink-uat=iab-20260914` | [目标站截图](screenshots/04-real-redirect.png) |
| 短链生命周期 | 修改描述和北京时间到期时间；回收后在回收站可见；恢复回原分组 | `update`、`recycle-bin/save`、`recycle-bin/recover` 真实响应与 UI 回读 |
| 同步批量 | 两行有效输入均创建成功；XLSX 非空、ZIP/XML 可解析，含表头及两行业务数据 | [同步结果](screenshots/05-batch-sync.png)、[真实 XLSX](downloads/created-links.xlsx) |
| 异步批量 | 501 行进入任务接口，仅 1 行有效、500 行无效；部分完成终态；真实游标从 20 行追加至 40 行；CSV 包含全部 501 行业务结果 | [任务截图](screenshots/13-async-batch.png)、[完整 CSV](downloads/async-results.csv) |
| 统计回流 | 本次浏览器点击进入分组统计，PV=1、UV=1、UIP=1；访问记录对应 `CLICK`、HTTP 302 | [统计](screenshots/06-new-click-statistics.png)、[记录](screenshots/07-access-record.png) |
| 统计边界 | ISP 口径不冒充 Wi-Fi/4G/5G；新老访客披露保留范围；超过 7 天的查询被禁用 | 页面控件与真实统计响应 |
| 投放 Agent | 真实 `agent/chat` 200；`get_group_stats` 与 `get_group_access_records` 完成；回答与本次 PV/UV/UIP=1 一致并披露 PARTIAL | [投放回答及轨迹](screenshots/09-campaign-agent.png) |
| 安全 Agent | 真实 Graph 与同名统计/记录 Tools 完成；新分组无候选风险画像，正常返回缺少风险证据 | [风控回答及轨迹](screenshots/10-security-agent.png) |
| 风险中心 | 默认分组概览、历史事件、证据及当前策略可读；当前策略明确为观测时无有效限制、revision 0 | [当前策略](screenshots/08-risk-current-policy.png) |
| 窄屏 | CDP 390×844 视口下，工作区无页面横向溢出，导航可达账户中心并读取资料 | [工作区](screenshots/11-mobile-workspace.png)、[账户](screenshots/12-mobile-account.png) |

## 缺陷与复验

### 新分组无画像被错误返回为 503

新建且已授权的分组首次读取风险概览时，Agent 日志在北京时间 22:32:23 记录 `IllegalStateException: Group risk profile is not available`，Admin 随后将上游 500 映射为 `REMOTE_503`。同组的卡片和事件接口支持空列表，默认分组的已有画像也正常。

无画像是异步生成之前的业务状态。修复合同为 `profileStatus=NOT_EVALUATED`、画像计数和分数为 `null`、风险等级 `UNKNOWN`；已有画像为 `READY`。鉴权失败和真实存储故障仍传播，不在查询过程中补造画像或触发同步统计。

### 本地访问地址与 canonical identity 混用

访问记录及风险卡片直接显示上游 `https://localhost:19080/...` 身份串，而实际公开入口只提供 HTTP。修复仅在展示层复用现有 `absoluteShortUrl` 和受信 `VITE_SHORTLINK_PUBLIC_ORIGIN`；只有 host（含端口）完全相同时应用配置协议。保留原始 `fullShortUrl`、linkId 和授权请求参数。

前端构建后的真实浏览器复验已确认：风险卡片、事件和访问记录均显示 `http://localhost:19080/...`，访问记录仍对应原 `CLICK / HTTP 302`。见[风险地址](screenshots/15-risk-http-display.png)与[访问记录](screenshots/16-record-http-display.png)。

更新本地 Admin 与 Agent 后，新分组 overview 返回 200、`NOT_EVALUATED`、`UNKNOWN`，分数和扫描数为显式 `null`，真实审核计数为 0；界面显示「—」及[尚未生成风险画像](screenshots/17-risk-not-evaluated.png)。默认分组仍返回 `READY`、真实风险分数 0、扫描数 1，已有画像没有被误清空。

服务更新后的第一批请求中，short-links 曾单次返回 `SESSION_OR_AUTHORITY_UNAVAILABLE`。Admin 22:57:00 日志明确是身份读取的 `RedisConnectionFailureException / RedisCommandTimeoutException`，并未进入 Agent 短链档案查询。随后通过页面正常刷新，概览、档案及事件三项均 200；默认分组的同组三项也全部 200。本轮保留鉴权依赖异常时拒绝访问的行为，没有放宽权限或超时预算。该次依赖超时和恢复同时保留在网络摘要，不算作首次请求通过，也不据此声明重启期间可用性达标。

### 自动检查

| 检查 | 结果 |
| --- | --- |
| 前端完整测试 | 73 项通过；保留原产品回归，新增空画像、独立审核事实和 canonical selector 边界 |
| 前端完整 lint、构建 | 通过 |
| Agent Service 风险中心 | 5 项单元 + 13 项 H2/MockMvc 集成检查通过 |
| Admin DTO | 3 项 JSON 契约检查通过，验证 null 不变 0、真实零值和长整型计数保留 |
| Admin + Agent 打包及本地更新 | 通过；重建两个已有容器后均健康，浏览器实际复验 |

后端定向检查使用 Java 17，`MAVEN_OPTS=-Xmx384m`，没有启动额外中间件：

```powershell
mvn.cmd -pl services/agent-service '-Dtest=RiskCenterServiceTest,RiskCenterInternalControllerTest' '-DargLine=-Xmx512m' test
mvn.cmd -pl services/admin '-Dtest=RiskGroupOverviewDtoContractTest' '-DargLine=-Xmx512m' test
```

`READY` 仅表示分组画像记录已生成，不表示统计采集完整。

本机第一次打包因 Java 21 读取 Java 17 生成的中文路径增量缓存失败；统一使用测试时的 Java 17 后打包成功，没有因此修改业务代码。打包阶段跳过的是已执行完毕的测试重复运行。

## 文件导出的验证边界

内置浏览器的 `download` 事件未在等待期内返回，CDP `Network.getResponseBody` 对 XLSX 也曾返回空串。因此没有把 HTTP 200 或页面“已导出”提示当成保存成功。

通过 CDP 临时观测前端 `URL.createObjectURL` 实际收到的 Blob，确认 XLSX 为约 3.6 KB、正确 MIME、`PK` ZIP 签名。随后从同一 UI 导出的实际 Blob 留档，校验为有效工作簿，工作表有 3 行（含表头）、12 个单元格。CSV 同样来自前端实际收到的 Blob，共 502 行（含表头），22,543 字节。观测函数随即恢复，没有改写请求或伪造响应。

这证明文件从真实后端到达了内置浏览器前端，也能校验文件内容；**不代表内置浏览器原生文件保存流程已经验收通过**。留档文件由测试观测保存。

## 范围与保留数据

- 本轮使用 Jupiter 正常登录，在其账户下新建 `内置浏览器 UAT 0914` 分组，保留 4 条 UAT 短链；未改密码、资料或既有短链，未停用账户。保留数据便于复查。
- 安全 Agent 无风险候选时的成功返回，不能证明已调用模型解释或执行自动限流；本轮没有执行人工审核、策略停用或传播确认。后续应使用隔离的风险样本验收写入。
- 统计元数据实际为 `PARTIAL`、采集质量 `UNKNOWN / PRODUCER_SAMPLE_STALE`；本地非公网 IP 的地域与 ISP 未知属于可解释缺口。未声明全量统计完整性已经达标。
- 访问记录本轮仅有一条，没有用它冒充真实第二页验收。真实游标追加验收来自异步批量结果。
- 真实跳转的目标站与服务端点击记录共同构成证据；没有捕获该跳转标签页的线级 302 响应，不将记录中的 HTTP 302 写成 CDP 直接抓包结果。
- CDP 观测缓冲在一次刷新前后出现截断，网络摘要不承诺覆盖所有事件；逐项验收使用当次 UI、响应及截图。摘要不保存凭据、请求头、查询参数或完整访客/IP 哈希。
- 二维码验证了 PNG 加载和尺寸，本轮未执行独立扫码解码。异步取消、永久删除、账户停用不在本轮实测范围。

结束时已恢复桌面视口、移除临时文件观测函数并关闭 CDP Network/Runtime 观测；只保留用户原来的内置浏览器标签页，已登录并停留在 [UAT 分组工作区](screenshots/19-final-workspace.png)。Docker 项目保持运行。

[返回文档目录](../../README.md)
