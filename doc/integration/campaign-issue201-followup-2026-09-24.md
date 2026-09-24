# 投放分析 Agent · Issue #201 补充验收（2026-09-24）

本记录接续[公共链最终验收](campaign-final-acceptance-2026-09-24.md)。各项结论只适用于下述实际版本、数据和故障窗口；旧报告修订版不会因本次代码更新而重写。运行证据保存在本地 ignored 目录 `.work/issue201/`，未把账号凭据、原始模型响应或现有用户会话提交到仓库。

| 条款 | 本轮结果 | 边界 |
| --- | --- | --- |
| 浏览器文件导出 | 隔离临时账号在 Issue #201 工作树前端（5175）点击指定 revision 的“导出本版”，捕获真实 Markdown 文件。`report-30093f50…ea40d` revision 1 的文件名与授权导出一致；下载和服务端均为 **1332 UTF-8 bytes**、SHA-256 `cbe29523e20bd0eac71af3f016d3d4a11d71c159d30d339c5e452dbd4f6616ce`。独立再读同版 API 后字节仍一致。 | 验证的是该隔离账号的空结果报告，不把旧 `report-a54e…` 的服务端哈希冒充浏览器文件证据。见本地 `browser/acceptance-result.json`。 |
| 真实权限变化 | 另一隔离账号在授权时取得 1/1 ANSWERED 报告，报告详情、分页、历史和导出均 200；删除自己的分组后历史为 200／空列表，其他三项拒绝读取；禁用自身账号后旧会话、再次请求以及四项读取均为 401 `INVALID_SESSION`。禁用前后第二隔离 Run 的 child 行、唯一 request 与报告修订计数均保持 1。 | 旧 Agent 运行包将撤组后的 `SecurityException` 错误映射成 503 `REMOTE_503`。本 PR 将报告 Controller 的拒绝响应改为无资源信息的 403，定向 MVC 用例通过；**旧运行包尚未部署，不能宣称现场 403 已复验**。第二 Run 的 child 在撤权前已是 READY／callback=false，没有捕获真实在途统计回调；H2 在途撤权测试不能冒充该现场窗口。见 `live-auth/summary.md`。 |
| 多实例与故障 | 同一 H2 上两个调度器的准备阶段重领竞态已修正：另一个实例的活跃回调只等待并退避，UNKNOWN 仍阻断，旧 claim 不能覆盖新状态。准备阶段到期时只把 due work 转为 BLOCKED／人工处理，不清除 callback/attempt，也不重派模型。受控同机子 JVM 在已提交证据后 `halt`，恢复复用原证据、旧 token 失效；双恢复者竞争仅一个取得所有权。新增真实父子 JVM 同时连接专用 H2：A 持有已确认的 ASYNC job 时 B 被 ALIVE 证明阻断；A 受控退出后 B 沿原 request/wire/job 身份对账，旧 callback 无法清理或发布，外部 fixture 提交计数保持 1。同一报告修订版的两个独立事务竞写只有一个完整版本可见。 | 同机且同 PID namespace 的死亡证明与跨容器不同；外部统计提交由专用 fixture 记录，不是生产 Analytics 网络的 exactly-once 证明。当前仍不支持把两个独立容器作为完整 active-active writer，不能因这些定向测试放宽单实例写入边界。见 `multi-instance/same-host-fault-acceptance-2026-09-24.md` 和 `multi-instance/live-owner-report-acceptance-2026-09-24.md`。 |
| 真实 UNKNOWN 地域 | 授权的真实 ClickHouse 统计结果返回 `device=Desktop`、`province={state:UNKNOWN,value:null}`，报告维度行和图表 **PV 11**。新前端显示“设备：Desktop／省份：未知”，提示 UNKNOWN 不是零，并保留原始字段。用该真实报告的 CHART/TABLE 块在隔离组件中验收了 1600px 与 390px，均无页面级横向溢出。 | 这不是已登录完整报告页面的复验。该真实报告为 PARTIAL：模型漏掉必填 `evidenceArtifactIds`，服务端正确拒绝解读；DATA/DELIVERY 仍 MET，原始指标与限制保留。见 `unknown-visual/README.md`、`narrative/unknown-response-schema-diagnostic.json`。 |
| 模型叙述质量 | 新调用提示区分“两期对比”和卡方检验、观察相关与因果、零值行与空结果、分页完整与采集完整。服务端按块拦截已知“两组卡方”、无证据的检验/因果/完整性断言；合法“并不证明因果”或未来检验建议保留，数据块不被删。 | 定向脚本模型用例通过；未重跑真实模型，也不修改历史 `READY` 修订版。文本规则不是任意自然语言真实性证明。上述 UNKNOWN 样本缺证据引用字段而被严格拒绝，未猜测补齐或重调模型。 |
| 容量 | 没有提高进程、模型或查询并发，也没有开启双 writer。现行 Agent 容器上限 **4 GiB**；一次只读采样为 562.7 MiB，cgroup 历史峰值 594,870,272 bytes，`oom=0`、`oom_kill=0`。 | 单点/历史峰值不是负载下内存与完成率试验；未来提高并发前仍须测量队列、退避、JVM heap/native、cgroup 峰值和完成率。见 `capacity/observed-agent-memory-2026-09-24.md`。 |

## 修改与定向验证

- 报告权限拒绝的传输状态修正；真实撤组前后接口事实与 `CampaignReportControllerTest`（2 个方法／12 个 MVC 请求）对应，后者全部返回 403、无资源正文。现场旧包仍为 503，需部署后再验。
- 调度器活跃回调 `WAIT` 与提交竞态二次检查；授权/依赖/调度连接点的 **8/8** 后端用例通过。到期收敛后仅重跑受影响的 `CampaignPublicWorkSchedulerTest`，**5/5** 通过（含公开请求与关联规划分别到期的两例）；不重复其他已通过类。另选同机进程恢复、并发接管和 OS 活死转换的 **3/3** 用例通过；真实两活 JVM 的已确认 ASYNC job 接管及报告版本并发发布新增 **2/2** 用例通过。
- 叙述质量定向用例覆盖零值与空结果、UNKNOWN 限制、有效否定句、冲突段落隔离和 READY/UNKNOWN 幂等边界；相关用例最终均通过。新前端标签纯函数 **5/5**，局部 ESLint/Prettier 与 Vite 生产构建通过。
- 工作树前端只改图表 UNKNOWN 标签、原始字段入口、表格口径提示及导出提示；下载仍由真实浏览器文件与同版服务端字节比较验收。没有为不同提示词增加专属页面。

当前未完成的现场证明是**真实在途统计 job 中途撤权的旧回调**、**两个完整 Spring writer/跨容器故障接管**以及**部署本 PR 后的 403 复验**。在获得相应证据前，单实例写入边界保持不变，不将 Issue #201 全部标为完成。

浏览器临时账号已从 UI 退出，但账户本身仍存在。自动审批拒绝了隔离测试目录的递归清理和明确凭据单文件的删除（仅返回 `blocked by policy`）；已将凭据文件覆写为不含密码／令牌的状态记录，隔离 profile/tmp 仍留在本地。没有绕过审批删除文件，也不把退出登录写成账号删除。
