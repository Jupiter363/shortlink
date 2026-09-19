# P1 第九批：真实统计查询固定执行器

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [PR #71](https://github.com/Jupiter363/shortlink/pull/71) 的分页接收与 [PR #72](https://github.com/Jupiter363/shortlink/pull/72) 的接收进度。此批补齐首次统计提交的业务适配器，继续保持新运行入口关闭。

## 冻结查询与稳定身份

`statistics_query_job@1` 是一个固定 Tool 执行器，输入为冻结的 `scope / periods / query`，输出为具名 `pages`。`StatisticsJobQuery(v1)` 是服务端确认后写入既有 `FrozenInputSet` 的普通输入值，包含范围／期间引用、gid、可选短链、查询类型、明确自然日日期及业务时区。恢复只读 Run 中原始描述，不根据最新聊天、当前日期或当前分组列表重新解释。

`FrozenStatisticsJobQuery` 校验闭合字段、原计划 INPUT 绑定、类型、范围和期间引用、日期及查询条件。当前支持既有 METRICS、ACCESS_RECORDS、LINK_METRICS、DIMENSION_BREAKDOWN 四种单次查询；维度条件复用 `DimensionQuery`。这里的范围模式为 CURRENT_GROUP，结果证据为原任务的 CURRENT_QUERY；没有实现或暗示 P2 的冻结成员全集、跨期间可比性和分片证明。

action／child／Artifact 的身份固定到 Run、revision 和步骤调用位置，与重试次数或参数变化无关。requestId 再包含冻结查询与执行器版本摘要。重建会得到同一请求；在同一位置改变请求会与已登记 ChildSpec 冲突，不能靠生成新 child 绕过幂等检查。

## 首次提交和恢复

执行器复用 `CampaignStepExecution.child()`：先耐久登记实际请求，再允许 PREPARED child 派发。专用 `submitStatisticsJob` 只请求固定统计 POST，严格验证可信主体、封闭请求、`code=0` 和合法任务回执；不接受只有 `success=true` 的旧式成功判定，不回退到通用 POST。失败保留受控代码和固定消息，不回显远端原始正文。

QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED 的合法回执均先保存原 job 身份，尚不发布分析结果。提交响应丢失时 child 保留 SUBMISSION_UNRESOLVED，步骤保留可恢复的 STEP_RESULT_UNKNOWN；后续只走 recover-existing。READY 返回原 Artifact，WAITING 保持等待，未知状态不再 submit。

可信装配使用同一个适配器的 `registration()` 和 `resultTargets()`，把固定执行器交给 `PersistentPlanDriver`，把原结果绑定交给 `CampaignRecoveryCoordinator.Runtime`。`authorized()` 与必需的 `QueryAuthorizer` 校验真实冻结请求是否对应当前获准的范围和期间，必须接到 Driver／Coordinator 每次实际调用前的授权门。身份匹配或引用字符串非空不能替代业务授权。

页完整接收后，输出策略核对 Artifact 所有者、producer、版本、原 child/action、范围、期间和 requestHash，以及 manifest 的完整页数、行数和链式校验和。原质量为 PARTIAL／UNKNOWN 时仍原样保留；完成收集不等于目标已经回答。

## 验证与启用边界

**9 项最小必要后端测试首次全部通过，无失败、无跳过**：`FrozenStatisticsJobQueryTest` 3 项、`StatisticsJobFixedExecutorTest` 3 项、`StatisticsJobSubmitContractTest` 3 项。只运行这一选择器一次，没有重复前批已通过的测试。

实际覆盖首次 HTTP 派发前冻结 ChildSpec、501 行两页接收后依赖步骤推进、READY 后零网络请求、ACK 丢失及 recover-existing 暂不可用后找回原请求、零行汇总页、派发前撤权／接收中撤权，以及提交期间取消后只保留晚到 job 身份。H2 验证同一槽位的参数或版本变化被持久化冲突拒绝；两种现有 HTTP 实现通过实际短生命周期 fixture 验证严格回执和错误处理。

只使用 H2、真实原生 Graph、脚本化统计响应和短生命周期 HTTP fixture，没有启动 Docker、应用服务或真实模型；未验收实际 MySQL、生产统计队列或前端。

仍未把新运行器注册为生产入口，没有变更旧投放路径或风险 Agent 工具。生产范围解析与授权装配、同步工具适配、客户端兼容以及后续 P2–P5 按计划继续；本批不宣称整个 P1 完成。
