# P4 原生规划候选与首次执行 · 2026-09-20

关联 Issue #62，接续 E44。将可信 typed 需求、原生规划模型、候选持久化及已有计划入口接通；尚不启用生产路由或运行中重规划。

## 实现

服务器固定问题、完整 goals／requirements、InputSet、可见 Artifact 元数据及版本化能力菜单。模型只返回 `steps / coverageBindings / gaps` 和 schemaVersion，不能改写目标、身份或运行版本。菜单可提供原子 Tool、非探索 Skill 和已登记的局部 REACT policy；不是按意图绑定唯一工具。后端按实际目录、DAG、输入端口、参数与输出合同验证组合，required 条件必须覆盖或明确缺口。没有可信能力缺失证明时拒绝 UNSUPPORTED，保留 PLANNING_UNRESOLVED。

复用 Spring AI Alibaba 原生 `ReactAgent.outputSchema`，本次规划无工具、一次模型调用，不增加自定义模型循环。原始请求和公开响应经有界 DTO 校验；供应商错误兜底文本不作为模型结果保存。初次规划发生在 Run 冻结之前，因此独立记录规划请求，不虚构 REACT Step 或模型 Artifact，也不放松已有 REACT 模型账本。

规划请求经历 PREPARED → DISPATCHING → READY → ACCEPTED／REJECTED；未知结果保留 UNKNOWN，禁止同一请求盲目重调。READY 保存实际响应，不代表候选有效。候选解析与语义校验在保存响应之后进行，非法候选明确拒绝；数据库接纳失败保留 READY，可重用同一响应。规划回调必须实际退出后才能接纳，取消 Future 不释放真实工作许可。

接纳使用同一 DataSource 的 REQUIRED 事务，锁定规划来源摘要、登记原 E44 typed 请求、绑定 accepted definitionHash。继续执行复用同一个进程准入 scope 和已有 Coordinator／Graph，不嵌套排队。已 ACCEPTED 的恢复只核对 typed receipt 绑定，再走当前业务权限、原 revision 和 Run 状态校验，不要求已经消费的规划响应继续有效，也不重新调用规划模型。

当前入口接受服务器已经明确的 typed requirements；未实现任意自然语言的需求拆解或语义正确性证明。当前权限和真实 Artifact 元数据在使用前重验，模型提供的描述和可见元数据不能充当授权。

## 最小验证

`CampaignPlanningIntakeTest` 两个方法首次通过，0 失败／错误／跳过；Maven 48.546 秒，日志 `.work/planning-intake-tests.log`。未重复运行已通过的旧用例。

| 方法 | 覆盖窗口 |
| --- | --- |
| `durablePlanningResponseSurvivesAtomicAcceptanceFailureThenExecutesItsDependencyPlanOnceAndPreservesRequiredGaps` | 实际原生规划获得 READY，注入 typed 请求 INSERT 拒绝，同事务回滚后无 typed receipt／Run；取消故障后复用原响应，模型总调用一次。实际 Tool→固定 Skill 原生图 WAIT，接收原 job 后按原依赖继续；规划自然过期不妨碍已接纳业务恢复。原 goals、required 因果缺口、job／产物身份完整保留。另一个 Tool→REACT 候选只验证结构，不宣称执行该探索。 |
| `rejectedCandidatesAndUnknownProviderResultsCreateNoRunOrBusinessCallsAndNeverBlindlyReinvokeThePlanner` | 改写目标、遗漏 required 覆盖、未知执行器、隐藏范围四类候选保存响应后 REJECTED；真实脚本 provider 异常保留 UNKNOWN。均为零 Run／业务调用，原需求不变，同一请求不再触发模型。 |

测试使用真实 H2、原生 ReactAgent 和 Graph；能力为小型注册 Tool／Skill 和脚本网关，不代替已有下降／维度 Skill 的联合生产验收。未启动 Docker、应用、真实模型／数据库、浏览器或前端构建。

风险映射：R05／原身份未知结果协议，由第二方法证明 provider UNKNOWN 同键不重调；R10／required 与因果条件协议，由第一方法证明已执行的数据步骤不删除因果缺口、第二方法证明不能改写目标或遗漏 required；R12／框架复用协议，由两个方法实际经过原生 ReactAgent 证明。本批不据此关闭跨服务未知提交、因果终评或全部原生运行风险。

## 剩余范围

自然语言需求提取、完整／部分／无 Skill 三配置的规划质量、规划未决结果的人工恢复、运行中 REQUEST_REPLAN、版本切换及共享任务 adopt／cancel、逐目标报告与历史仍继续实现。规划 payload 当前有界保留，清理及长期留存策略尚未接入。新入口不注册生产服务，真实 MySQL 方言和锁仍待环境验收。
