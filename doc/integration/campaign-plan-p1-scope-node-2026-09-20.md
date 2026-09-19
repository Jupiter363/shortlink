# P1：原生 Plan 的权威范围枚举节点

日期：2026-09-20，关联 Issue #62，承接 E16/E23。

## 执行与恢复合同

`scope_collection@1` 冻结 gid、原始期限与稳定收集身份；输入不包含尚未存在的 scopeRef。每个权威请求沿用真实 SYNC child，分页与游标耐久保存，每次实际 I/O 重新核对当前主体、分组权限和步骤资格。

单次读取达到工作批次后，节点返回 `BLOCKED/SCOPE_COLLECTION_PROGRESS`。它没有已受理的异步 job，因此不使用 WAITING。专用恢复门只接受该状态：同一事务内检查当前 Run、实际步骤定义、全部回调退出、依赖成功，逐页验证原请求、连续游标、版本、checksum、READY 凭证、期限和权限后恢复 READY。未知首屏、无效范围和任意失败原因不进入该恢复门。

终页产生实际 ScopeArtifact 后，才经具名 STEP_OUTPUT 交给下游。`inspectPublished` 使用与分片读取相同的来源证明，并允许真实空组；空组不能被索取为一个伪造的成员分片。没有修改原 NativeGraph 或构造第二套调度器。

已发布 ScopeArtifact 的当前授权仍覆盖它代表的完整成员集合，不要求调用者另获内部 PageRef ID 的授权。内部读取逐页限制 exact collection/child/owner，并重新检查最终凭证的当前权限；不能直接读取内部 PageRef 或其他集合。尚未完成的范围没有最终凭证，恢复时仍逐页检查 PageRef 当前授权。

## 验证与交付边界

三项定向后端测试首次通过：`ScopeCollectionFixedExecutorTest` 两项，以及因共享分片验证方法变更而运行的 `CampaignScopeCollectorTest#reopenedCollectionSkipsDurablePagesAndBothPeriodsReadTheSameBoundedShards`。日志 `.work/scope-node-tests.log`；未运行其他回归。

真实 NativeGraph/H2 验证 501 成员分两次扫描（500＋1）、实际 SYNC child/ScopePageRef/ScopeArtifact、具名 STEP_OUTPUT 下游只读消费，原页不重取、重复扫描零 HTTP。覆盖权限撤销、SQL 篡改分页、首屏未知不重读、空组与无成员分片，以及仅最终 ScopeArtifact 授权仍可读取完整成员、直接读取内部 PageRef 被拒绝。所有实际回调退出。

本批只接范围枚举节点和真实 typed 下游读取。`decline_selection/1` 的原冻结范围合同保持不变，完整枚举→筛选→维度链需另以版本化动态范围合同接线；不预填范围 hash 或伪造 Artifact。生产入口、真实 MySQL／跨服务／模型和客户端尚未验收，不启动 Docker 或应用。
