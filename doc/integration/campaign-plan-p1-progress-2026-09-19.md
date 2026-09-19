# P1 第四批：授权进度与可用结果视图

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，基于 [PR #67](https://github.com/Jupiter363/shortlink/pull/67) 的步骤驱动继续实现。组件仅为后端只读能力，没有注册控制器或切换旧客户端。

## 呈现合同

`campaign-progress/v1` 分开表达 Run 状态、实际执行进度和交付评估。每个目标保留原问题、关联步骤、规划缺口及当前可访问的具名结果。

- 步骤状态来自同一 Run revision 的持久化快照。读取不会领取执行权、刷新状态、提交任务或把原生 Graph END 当作完成信号。
- 后继等待或阻断沿依赖关系传播，`blockedBy` 保留直接依赖步骤；实际 `recordedStatus` 与展示用 `workState` 分开，界面无需把 PENDING 猜成零数据或成功。
- 已完成的独立结果继续列出，部分失败不抹除其它目标的可用结果。只有 SUCCEEDED 的具名输出才可公开，未发布的 child Artifact 不作为步骤结果显示。
- 结果引用逐项复核当前主体、授权版本、权限及期限。失效项只公开端口和固定原因码，不公开其 Artifact ID、范围或类型；其它有效项不受影响。访问实际内容时仍需重新授权。
- `EXECUTED` 只表示对应步骤执行完成；`deliveryState` 明确为 `NOT_ASSESSED`，从不输出 ANSWERED、完整报告或自动推断 COMPLETE 质量。逐目标 DELIVERY 验收仍属于 P5。
- 规划缺口按 requirement 的 goalId 关联，缺输入／能力不足／证据不可用等原原因保留。取消后仍可读取授权的既有结果，同时明确 Run 已取消。
- 视图只包含短引用和目标／状态说明，不返回冻结请求正文、步骤参数、推进令牌、完整 Artifact payload 或内部回调标识。自由文本故障不直接当作 reasonCode 输出。

## 一致性边界

`CampaignStepStore.snapshot` 在同一事务中锁定最新 Run 和对应步骤，读取取消状态不需要 writer token。锁等待期间如果 revision 或推进版本与所读定义不一致，明确返回 `PROGRESS_SNAPSHOT_CHANGED`，不拼接新旧状态，也不在服务内忙轮询。

结果授权检查发生在该执行快照之后。响应携带明确 revision，代表这一次读取；它不冻结之后发生的授权或运行变化。历史报告留存与过期证据再分析仍按后续独立协议处理，本视图不绕过复用期限。

## 验证与启用门槛

只运行本批新增的 **7 个 H2 后端用例，全部首次通过，无失败、无跳过**：`JdbcCampaignProgressSnapshotTest` 3 项覆盖当前版本／取消后只读、身份隔离与真实事务竞争；`CampaignProgressServiceTest` 4 项覆盖跨目标等待、执行与交付分离、撤权／过期引用隐藏、取消后的缺口与独立结果保留。

快照 3 项与投影 4 项分别各执行一次，没有重复 PR #67 已通过的 23 项，也没有运行全模块测试。不启动 Docker、服务或真实模型。

P1 的进程死亡确认、恢复协调和生产装配仍待实现。当前视图不是完整报告 Schema，也没有完成旧客户端兼容；新运行入口保持关闭。P2–P5 的固定范围、组合能力、局部探索、混合重规划及图文报告继续按原计划推进。
