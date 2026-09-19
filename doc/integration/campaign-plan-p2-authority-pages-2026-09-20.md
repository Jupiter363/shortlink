# P2 第五批：权威成员分页合同

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。复用现有 Command 分组游标查询，补齐 Agent→Admin 的窄类型合同；没有新增历史快照引擎，新运行入口继续关闭。

## 合同与授权

新增内部 `authorization/group-members-page`，只接受 gid，以及成对的 afterLinkId/ownershipVersion。禁止 URL、显式成员列表或主体字段；游标必须为正整数，首屏不用 0。每页最多 500 个严格递增的正整数 ID，非末页必须恰好 500 个，nextCursor 等于最后 ID。授权空组保留空列表和显式 null 游标。

共享 `group-members-page/v1` 包含 schema、tenant、subject、authVersion、gid、ownershipVersion、afterLinkId、linkIds、nextCursor。主体来自可信 principal/UserContext，Admin 核对当前 owned gid 和上游 tenant、逐项 gid；Agent 再核对响应主体、原 gid、游标及固定版本。未知字段、字符串／浮点 ID、重复／乱序成员和不完整非末页不会作为合法范围返回。

Admin 仍调用原 Command `authorization/resolve`。403 映射 FORBIDDEN，409 映射 QUERY_SCOPE_CHANGED，未支持的协议与坏响应明确拒绝，网络失败保留 REMOTE_UNAVAILABLE。Agent 使用已有有界 HTTP 传输，不回退旧接口、不自动重试或把失败变成空组。旧风险／profile resolve 调用保持原协议。

## 分组版本的含义

当前枚举口径为所有非 DELETED 短链 ID，包含回收站中仍存在的短链。源码核对确认：创建／批量／导入复用 `insertReservedMany`，迁组同时更新两组，永久删除减少引用；这些成员变化与 group.revision 更新同事务。回收和恢复不改变该 ID 集合，故不必单独更新组版本。改名、排序、任务引用也可能保守地使游标过期。

ownershipVersion 是 tenant/authVersion/gid/revision 的摘要，不是可历史重读的 snapshotId。旧 resolve HTTP 方法声明只读 REPEATABLE_READ，成员与版本在同一次请求的事务内读取；每次请求仍独立。它不能证明“响应返回瞬间”的最新授权，后续发布／使用范围仍须重新授权。仓外手工导入工具不在源码审查证明内。

## 验证与继续工作

7 项定向后端测试最终通过，无失败或跳过：共享合同 1 项、Agent HTTP 2 项、Admin MVC/HTTP 3 项、Command 真实业务服务 H2 1 项。共享合同收紧 gid 与空组约束后，只重跑了受影响的共享用例；其余已通过用例未重复执行。日志：`.work/authority-page-agent-tests.log`、`.work/authority-page-admin-tests.log`、`.work/authority-page-command-tests.log`、`.work/authority-page-contract-recheck.log`。

Command 用例调用真实 LinkCommandService 创建 501 个成员，再在事务中真实创建并回滚，证明成员和 revision 同时恢复、旧游标仍可读；提交新成员后旧游标返回 409。只替代 ID 分配与成员注册等待，没有用伪造 SQL 变更代替业务写路径。使用共享合同测试、短生命周期 HTTP fixture 和 Command H2；不启动 Docker、应用、真实模型或 MySQL/ShardingSphere 测试。真实 MySQL 的 RR 并发读窗口没有本次运行证据。

耐久分页 collector、完整 ScopeArtifact、原页未知响应的受控恢复、分片父集合对账和两期全量组合仍待实现。本批只补可信分页边界，不将分页接口视为候选全集已经完整收集。
