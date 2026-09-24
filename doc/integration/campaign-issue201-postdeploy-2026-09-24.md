# 投放分析 Agent · Issue #201 部署后验收补记（2026-09-24）

本记录接续 [Issue #201 补充验收](campaign-issue201-followup-2026-09-24.md)。前文记录的是 PR #203 部署前状态；本记录只更新合并和本地部署后实际复验的事实，不改写旧报告版本。

## 交付版本与现场范围

- [PR #203](https://github.com/Jupiter363/shortlink/pull/203) 已于 2026-09-24 05:21:42 UTC 合并到 `main`，合并提交 `e98c09a25ac9296c446bdb5170b75afe05ee2044`。
- 仅刷新本地 `shortlink-local-dev-agent-1` 的已合并 Agent jar；停止前 public、planning、child、step、synthesis 的活跃回调总数为 0。旧 jar 已保留在 ignored 本地目录供回滚。新 `/app/service.jar` SHA-256 为 `3AFD64E88619777FFC84848DFC33A30D6E470EFB4246DE091A09E175233D3C73`。启动后容器 `running/healthy`，重启次数 0，`OOMKilled=false`，cgroup `oom=0`。本次只动本地 Agent，没有重建其他容器或数据库。
- 容器刷新后单点内存读数为 472.6 MiB／4 GiB；这不是并发容量测量，没有据此提高并发或放宽单实例写入边界。

## 真实撤组后的报告读取

在新隔离账号和空分组上，真实公共 NEW → 规划／统计 → 报告链路完成：`campaign-run-6c181a973e0a022397fed8b91fd7d3dd32c932bbc4186586d8c7d1fda726c1c9`，报告 `report-17856dbd685880f622ad59eec228eacb343925165851f544b5d35449e9a76027` 修订版 1。撤组前指定报告、完整结果分页、Markdown 导出和历史均 HTTP 200，历史有 1 项。2026-09-24 05:26:10 UTC 删除该账号自己的测试组后：

| 读取 | 实际响应 |
| --- | --- |
| 指定报告修订版 | HTTP **403**、`REMOTE_403`，无报告数据 |
| 指定块完整结果分页 | HTTP **403**、`REMOTE_403`，无分页数据 |
| 同版 Markdown 导出 | HTTP **403**、`REMOTE_403`，无导出正文 |
| 当前 session 历史 | HTTP 200、空列表，不暴露旧标题或状态 |

撤组前后对同一 Run 的数据库只读快照均为 public request 1、child 1、唯一 child request 1、report revision 1、活跃 child callback 0；没有因拒绝读取创建新的查询或报告版本。隔离账号的两个测试分组均已由本人通过受支持 API 删除，账号已自禁用；审计 Run／报告按产品机制保留。本次证明已完成报告的**当前权限读取**和 PR #203 修复的现场 403 传输状态，不能证明在途统计 job 撤权或迟到回调隔离。脱敏本地证据为 `.work/issue201/postdeploy-auth/summary.md`、`events.json`、`facts.json` 和 `db-counts.json`。

## 在途撤权补充尝试

使用两个新隔离账号、各自空分组和各一个真实统计请求做了有界尝试，均没有改动共享服务或既有用户数据。第一次只读观察脚本因本机 PowerShell 版本和 SQL 传参失败，真实 job 在账号禁用前 39 秒已完成，不能构成在途撤权。修正观察器后，第二次在 2026-09-24 05:43:17.287 UTC 观察到原 child `WAITING`、同一 Analytics job `QUEUED`；但撤权脚本不接受观察器输出的 7 位小数时间戳，随后按清理路径删除自己的组并禁用账号。该 job 最终为 `FAILED/UNAVAILABLE`、child `WAITING`、无 artifact；Analytics `updated_at` 只有整秒精度，不能与 05:43:17.398 的删组成功及 05:43:17.694 的禁用成功建立可靠先后关系。这两次**均不计在途撤权通过，也不能据此判定产品失败**。两个账号均已禁用，自有分组已清空；脱敏证据见本地 `.work/issue201/live-inflight/summary.md` 与 `attempt2/summary.md`。

另以**完整 Spring Agent + 独立 MySQL + 可变授权 HTTP fixture** 做了受控回调实验：原 job 的状态 HTTP 请求挂起后，fixture 将当前 principal／组权限切为 403，再放行原响应。新增定向用例 **1/1 通过**：原 request、wire、job 保持不变，提交 1 次、状态读取 1 次、分页／release 均 0 次，授权 403 共 3 次；child `UNRESOLVED`、callback 已退出，artifact／release／report 行均为 0，应用内再次解析当前 principal 和推进请求也被拒绝。专用 MySQL 已关闭；证据在本地 `.work/issue201/spring-pair/case-13829a2d16d640fb8913fac8399fd8d4/revocation/result.json`。这证明 Agent 客户端在真正执行中的回调后遵循**实时授权接口合同**，不证明真实 Admin 撤组与真实 Analytics job 的同一现场竞态，也不覆盖该 FIXED 查询之外的业务报告生成。

若仍要求真实服务的在途窗口，需要先用整数毫秒统一观察 marker，再在隔离的实际 Admin／Analytics 集成环境设置受控屏障，分别证明待处理 job 和执行中回调的撤权顺序；不能继续靠共享服务的一秒自然窗口推断竞态。

## 完整 Spring 顺序故障接管

新增 `CampaignSpringProcessFailoverIntegrationTest` 的一个定向用例在**全新独立 MySQL 8.0.34** 上启动两个完整 `ShortLinkAgentApplication` 子进程，均装配 `production,campaign-plan-v2`、Tomcat、`MysqlSaver` 和调度器。A 在原 job 的 HTTP 状态查询已持有持久回调时受控退出，**确认 A 死亡后**才启动 B。B 沿同一冻结定义、request、wire 和 job 恢复，原请求只提交 **1** 次，状态查询 **2** 次，结果页读取 **1** 次，模型调用 **0** 次；step 和 artifact 达到 READY，旧回调 token 被拒绝，审计记录 `PROCESS_ABSENT`。该用例 **1/1 通过、BUILD SUCCESS**；专用 MySQL 已关闭。脱敏证据在本地 `.work/issue201/spring-pair/case-6d9ed79cd2a748a3a25c66deb87694ce/result.json` 和 `cleanup.json`。

这是同机、同 PID namespace 的**顺序单写入**接管；统计和授权由本地 HTTP fixture 提供，不是两个同时 active 的独立容器、真实 Analytics 网络或报告生成链。为了在专用原生 MySQL 初始化已有表，测试夹具将 `V20260920_21` 中唯一的 H2 `CLOB NOT NULL` 声明精确转换为与已部署 MySQL 表一致的 `LONGTEXT NOT NULL`；没有修改历史迁移文件。此适配**不证明未经适配的旧迁移能直接初始化全新 MySQL**。该测试类在未配置专用 MySQL URL 的普通环境中自动跳过，单类检查 2 个方法均 SKIP、BUILD SUCCESS。

## 尚待实测的边界

同机 H2 双调度器、两活 JVM 与受控 `halt`、并发报告发布的定向用例在前一记录中已有通过证据；本记录补足了完整 Spring 的顺序接管，但仍未覆盖跨容器 active-active 或真实 Analytics 网络的故障窗口。完成报告的 403 现场结果加上受控 Spring 回调测试分别覆盖读取端和 Agent 客户端授权边界，但不能合称真实 Admin／Analytics 的同一在途故障窗口。单实例写入边界保持不变。

浏览器下载与同版服务端导出的字节、长度、SHA-256 一致，真实 UNKNOWN 地域样本的 PV 11 与原始 UNKNOWN 状态、以及叙述安全降级等结果详见前一记录。真实 UNKNOWN 报告中模型漏掉必填证据引用，服务器按协议保留 PARTIAL，而未把缺少证据的解读当成已证实分析；这不等于真实模型叙述质量已全面通过。
