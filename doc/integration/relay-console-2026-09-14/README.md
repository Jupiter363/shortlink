# JUPITER RELAY 前后端联调验收 · 2026-09-14

新主题已接入真实后端，并替换 `frontend/console-vue/src/main.js` 的运行入口。本地 Docker 前端为 [http://127.0.0.1:5174](http://127.0.0.1:5174)，浏览器通过 Nginx → APISIX → Admin 访问业务服务。设计原型仅在 `doc/design/` 留档，5189 预览服务已停止。

本轮关联 [前端重构 Issue #27](https://github.com/Jupiter363/shortlink/issues/27)。联调中发现的两项后端问题独立修复并合并：[冷跳转预算 PR #30](https://github.com/Jupiter363/shortlink/pull/30)、[日期与数据库时区 PR #31](https://github.com/Jupiter363/shortlink/pull/31)。

最终审查的退出会话合同由 [PR #34](https://github.com/Jupiter363/shortlink/pull/34) 独立修复；新前端、列表范围修复与全部设计/联调档案归入 [PR #32](https://github.com/Jupiter363/shortlink/pull/32)。

## 验收结果

| 范围 | 实际验证 | 证据 |
| --- | --- | --- |
| 登录、账户、分组 | 真实注册、登录、READY 初始化与刷新恢复；资料更新、校验当前密码、改密后旧 token 失效；分组增删改与排序 | [产品 UAT](product-uat.md) |
| 短链生命周期 | 创建、编辑、真实版本冲突、回收、恢复、永久删除一次性样本；北京时间到期时间输入/API/列表/编辑回显一致 | [产品记录](product-uat.json) |
| 批量创建与文件 | 12 行同步创建及真实 XLSX；501 行异步任务（2 行有效、499 行无效）、游标追加、完整 CSV、真实取消终态；超过 1 MiB 的输入通过代理 | [产品 UAT](product-uat.md) |
| 真实跳转 | 三个不同的新短链，每个只请求一次、不重试；经公网网关均返回 302 和正确 Location，分别约 751 / 57 / 31 ms | [跳转复验](redirect-uat.json) |
| 异步统计 | 上述三次跳转随后可查询到 PV=3、UV=3、UIP=1；分组/单链查询、十类维度、访问记录和快照恢复 | [统计 UAT](analytics-uat.md)、[新点击入库](redirect-uat.json) |
| 双 Agent | 投放分析和安全风控真实 chat 均返回 HTTP 200，并显示完成结果；会话独立、请求中锁定导航、页面切换后回看 | [Agent 链路记录](analysis-uat.json) |
| 风险中心 | 真实概览、档案、事件、详情、当前策略查询；脱敏证据、UNKNOWN 恢复态、抽屉焦点与窄屏 | [风险 UAT](risk-uat.md) |
| 最终构建 | 30 项烟测通过；登录/注册及 7 个受保护路由支持直接进入、硬刷新恢复；移动端分组切换与风险刷新使用真实 GET 200；无未捕获浏览器异常 | [最终烟测](final-smoke.json) |
| 审查后增量回归 | 15 项功能及 11 项移动端复验通过；实际退出 URL 不含 token，HTTP 200 后旧 token 被拒绝，重新登录可用；切组失败清旧元数据，撤销故障注入后实际刷新恢复；390px 长分组名完整可读 | [退出与分组复验](logout-scope-uat.md) |

前端业务入口不使用原型内存账户、模拟任务推进、样例统计或假导出。缺失指标保持未知；统计快照按实际授权范围复用，创建时间分页不会携带另一页范围的快照。

## 自动检查

| 检查 | 结果 |
| --- | --- |
| 前端 `npm.cmd run test` | 69 项通过，含最终审查新增的退出请求与列表范围回归 |
| 前端 `npm.cmd run lint` | 通过 |
| 前端 `npm.cmd run build` | 通过，Vue SFC 与懒加载路由实际编译 |
| Redirect 预算、配置绑定、Bloom 与过期权威凭据回归 | 32 项通过 |
| Command 日期合同、UTC JDBC 配置与实际本地 MySQL 连接池验收 | 25 项通过，其中 1 项为真实数据库只读集成检查，未跳过 |
| Admin 退出会话与管理鉴权合同 | 50 项通过：过滤器 35 项、MVC 15 项 |
| Nginx | `nginx -t` 通过，已部署相同配置 |

前端检查在 `frontend/console-vue` 执行。后端定向检查在仓库根目录执行：

```powershell
mvn.cmd -pl services/shortlink-redirect -am "-DskipTests=false" "-Dtest=RedirectPropertiesBindingTest,RedirectControllerTest,BloomRedirectControllerTest,ProofBudgetRedirectTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" test
mvn.cmd -pl services/shortlink-command -am "-DskipTests=false" "-Dtest=LinkCommandControllerWireContractTest,UtcJdbcConfigurationTest,UtcJdbcSessionIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" test
mvn.cmd -pl services/admin -am "-DskipTests=false" "-Dtest=ManagementIdentityTest,UserLogoutMvcTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dnet.bytebuddy.experimental=true" test
```

本机 JDK 21 的 Mockito/Byte Buddy 组合需要上述兼容开关。`UtcJdbcSessionIT` 显式读取 `SHORTLINK_UAT_DB_URL`、`SHORTLINK_UAT_DB_USERNAME`、`SHORTLINK_UAT_DB_PASSWORD`，缺少环境时会跳过；本次已向本地 MySQL 执行，确认连接会话为 `+00:00`、`NOW()` 与 `UTC_TIMESTAMP()` 差为 0。凭据只从忽略的本地运行配置载入，未纳入报告或 Git。

## 修复与原因

1. 写操作后列表复用旧统计范围，导致创建成功但刷新报错。写后重建快照；普通创建时间分页按当前页查询，只有全组指标排序续用同范围快照。真实第二页和返回第一页均复验通过。
2. 新短链冷请求的路由查询与策略查询串行，却共用 500 ms 总预算。拆分为每次权威 IO 500 ms、整体 900 ms，仍严格小于权威有效期并保留最终有效性校验；APISIX read timeout 调整为 1.5 s。没有绕过 Bloom 或策略失败关闭。见 [原因与回归证据](redirect-investigation.md)。
3. UTC 数据库时间被格式化为无时区字符串，再由管理端按北京时间解释，导致显示相差 8 小时。Command 明确按 UTC 解释存储时间、按 Asia/Shanghai 输出，并强制实际 JDBC 会话为 UTC；拒绝冲突的非 UTC 配置。前端不做补偿加时。
4. 二维码函数生成 PNG，原型下载名仍是 SVG。按真实 MIME 选择后缀，已检验文件签名；本地无协议地址仅在 host 完全匹配时应用 HTTP origin 配置。
5. 统一真实会话恢复、64 位 ID、业务错误码、取消/超时、未知写入结果核实；Nginx 配置 8 MiB 请求边界和 Agent 等待预算，访问日志不记录含旧 logout token 的 query。
6. 最终审查补充修复：新范围加载失败时清除上一分组的数量与统计覆盖状态，未知显示「—」；同范围翻页失败仍保留已确认页码和快照。退出改为仅使用已验证会话头，避免 token 进入 URL；旧 query 参数只在完整匹配时兼容。相关鉴权合同单独跟踪 [Issue #33](https://github.com/Jupiter363/shortlink/issues/33)。

## 验证边界与后续项

- 本轮是功能联调，没有压测，三个跳转耗时不能作为吞吐量或延迟分位数结论。
- 统计实际返回 `completeness=PARTIAL`、采集质量 `UNKNOWN / PRODUCER_SAMPLE_STALE`。新点击确实进入统计查询，但未声明全量采集完整性达标。
- 投放分析已观察到实际统计/记录工具和回答；安全风控样本验证了真实 Graph 返回与前端呈现，未将缺少证据时的正常结束算作已执行全部工具或 LLM 推理。
- 风险人工审核写入、策略停用及传播确认没有执行真实 UAT。相关 API/领域状态有单元覆盖，不能替代写入验收；账户停用也未实际执行。
- 风险概览与列表目前可能来自不同批次窗口，扫描数不能直接对齐；建议后续 DTO 披露批次与窗口。没有修改样本或推算统一数量。
- 访问记录与风险列表的真实数据不足第二页；快照过期和 UNKNOWN 使用了明确标记的浏览器故障注入。批量结果第二页则是实际后端游标验证。
- 二维码已验证目标地址、渲染、MIME 和字节签名，未进行独立扫码解码。
- 新应用是当前唯一构建入口；旧前端未引用源码、旧依赖及两份后端静态联调页尚待清理，所以 Issue #27 保持打开。

## 归档说明

`contract-audit.md` 是整改前审计快照；`uat-first.json` 和产品记录中的初验失败保留用于溯源，以后续复验和 `final-smoke.json` 判断最终状态。JSON 检查项有交叉覆盖，不叠加成独立业务场景总数。

产品写入使用专用 UAT 账户和一次性样本；Jupiter 账户仅登录和查询，未改密或停用。浏览器脚本、导出文件和专用凭据位于忽略的 `.work/relay-uat/`，Git 仅保存脱敏结论与截图。所有本轮隔离验收浏览器已关闭，Docker 项目保持运行。

[前端开发与部署说明](../../development/frontend-console.md) · [设计档案](../../design/README.md) · [返回文档入口](../../README.md)
