# 投放分析运行架构：P0 后端验证

日期：2026-09-19。关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [架构方案](../plan/campaign-agent-architecture-2026-09-19.md)。P0 指第 0 实施阶段；本记录随实现更新，不表示 P1–P5 已交付。

状态：**首批实现及默认后端回归通过；完整 P0 门槛仍未关闭**。当前仍使用既有投放执行入口；新增计划／探索／容量组件没有注册为生产运行器，不对外声明持久化恢复或新图文报告已经可用。

以上为首批验收快照。后续已补齐续接、Skill 与模型响应入口的组件验证，见 [P0 补充验收](campaign-plan-p0-recovery-2026-09-19.md)；下方测试数量与边界保留为首批记录。

## 范围

- 将 Plan、步骤、绑定、目标条件及服务端能力版本落实为可校验的 Java 合同。
- 使用固定 Spring AI Alibaba 1.1.2.3 的真实 ReactAgent／Graph，配合脚本化模型、工具替身与内存 saver 验证控制点；不自写模型循环。
- 验证同步工作实际退出与 Future 超时／取消的区别，提供可配置进程准入组件；不修改部署容量。
- 修复 DeepSeek 响应中缺失／重复工具 ID 被默认值替代、坏条目被跳过的问题；在任何 callback 执行前拒绝整个不合法批次。

## 实测发现与设计落点

1. **原生 checkpoint 存在真实的 Jackson 二进制不兼容。** Spring Boot 父依赖管理解析到 2.14 系列，但 graph-core 1.1.2.3 在原生消息反序列化时调用 `ObjectMapper.treeToValue(TreeNode, TypeReference)`，触发 `NoSuchMethodError`。仅在 Agent 子模块导入 Jackson 2.19.4 BOM，与该固定版本 graph-core 的已发布 POM 对齐；不升级 Spring AI Alibaba 或其他模块。
2. **`asNode(includeContents=false)` 不隔离所有父状态。** 原生反例证明父级任意字段仍可能进入子图 checkpoint。因此选择显式输入／输出投影的薄节点调用原生 ReactAgent；模型／工具循环、路由及 checkpoint 仍由框架提供。
3. **串行工具执行不等于每轮只调用一个工具。** AFTER_MODEL 检查完整批次，多调用批次零派发，并按原 ID 配对拒绝消息。修复次数进入可信账本；BEFORE_MODEL 独立检查推进资格。两种 Hook 分开，显式消费旧 `jump_to`，避免原生条件边重复解释过期跳转。
4. **原生工具 Future 超时不证明回调退出。** 超时会清除 native state update，还可能让排队中的 `CompletableFuture` supplier 根本不执行。响应 Promise 与实际 worker 分离，取消后每个内部请求重新检查派发资格；运行计数由真实退出释放。普通异常与 `Error` 都先撤销推进资格，避免原生错误消息重新放行模型。
5. **停止后的重复推进也需在图入口拦截。** 仅有模型前 Hook 会阻止模型，但 START 已可能追加输入；等待／阻断／候选态在调用原生图之前返回账本投影，避免重复“继续”累积无效上下文。
6. **声明证据端口不能自动覆盖任意目标。** 计划校验同时检查证据生产步骤明确关联对应目标；两份示例补齐共享选中对象步骤的目标关联。每个目标必须有 DELIVERY 条件，但静态通过并不代表实际交付已完成。
7. **取消不能留下不计数的底层队列项。** 包装层若立即归还尚未运行任务的许可，连续取消会在底层线程池积累 Runnable。交接状态单独计数；ThreadPoolExecutor 确认移除任务后才归还许可，通用 Executor 则保留名额直到实际消费该 Runnable。关闭也遵守此规则，禁止静默丢弃任务的执行器策略。

工具异常同样属于对话载荷边界。返回给原生工具节点的异常仅保留固定错误码与必要取消／超时类别，不把任意长的原始异常文本或 cause 写入工具消息；生产诊断仍需独立接入。

## 测试记录

| 范围 | 实际结果 | 证明边界 |
| --- | --- | --- |
| DeepSeekSpringAiChatModelTest、ChatModelToolCallTest | 27 项通过 | 有效工具 ID 保留；缺失／重复／畸形条目整批失败；真实 ChatClient 下工具零执行；不调用线上模型 |
| ProcessCapacityExecutorTest、BoundedHttpTransportTest | 21 项通过（18＋3） | 多维原子准入、短引用排队、取消不提前归还运行许可；无 Content-Length 的超限响应被拦截，重复失败不泄漏 HTTP 许可 |
| NativeSkillsContractTest | 2 项通过 | 原生 Registry／Hook 的显式目录、关闭自动刷新及未知文件不可读；未证明生产版本锁定和权限管理 |
| Jackson 对齐后的现有投放／风控／持久化等定向回归 | 93 项通过 | 与上列部分测试重叠，不累计为独立测试总数 |
| PlanValidatorTest、PlanContractTest | 54 项通过 | 类型／版本／依赖／参数／逐目标覆盖拒绝；冻结输入；两份真实方案示例通过 |
| NativeExplorationAdapterTest、NativeExplorationCheckpointTest | 32 项通过（19＋13） | 实际原生循环与 saver；等待、批次修复、超时排队清理、Error、状态投影及执行身份隔离 |
| 全 Agent 默认测试 | 87 个 suite，795 项：794 通过、1 跳过，零失败／错误 | 2026-09-19 21:17（Asia/Shanghai），Maven BUILD SUCCESS；上述定向用例均包含在本次全量中 |

唯一跳过项为既有 `RepositoryFilesTest.rejectsTargetSymlinkOutsideTheRepository`，由 Windows 平台条件禁用，本轮未新增跳过。容量的 18 项用例包含连续 1000 次取消与两种交接竞争；原生探索的 32 项用例包含 200 KB 异常文本在 RuntimeException／AssertionError 下均不进入任何 checkpoint。另检查 10 份 Markdown 的 116 个本地文件链接，全部存在；两份计划示例由后端合同测试实际解析并验证。

本机命令使用离线 Maven：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=GBK'
mvn.cmd -o -pl services/agent-service '-Dnet.bytebuddy.experimental=true' test
```

GBK 仅用于兼容本机既有 Maven inputFiles.lst 编码，源码仍是 UTF-8。Java 21 编译 release 17；依赖调整仅为上述 Agent 子模块 Jackson 对齐，未改容器。默认 test 生命周期排除集成／E2E 测试，不访问真实 MySQL、Redis 或统计服务。

## 后续门槛

计划校验只证明静态合同成立，不授予对象权限、不证明数据齐全、不直接生成 ANSWERED。内存账本只验证原生扩展点；P1 才接持久化 Run／Action／Child、Artifact 及 Admin→Analytics 的独立恢复入口。

内存 saver 和受控并发测试不能证明真实 MySQL 恢复或部署堆容量安全。完整 WAITING→READY 消息重建、真实 HTTP 模型响应在 DTO 反序列化前的分配边界、原生 Skill 方法包版本锁定、生产客户端状态与图文呈现仍按后续验收逐项记录。本轮体积校验发生在 ChatModel 返回后、native state／saver 写入前，不能代替模型 HTTP 入口的字节限制。

原生 SkillsAgentHook 同时提供读取／搜索／禁用工具；本轮只验证其接口行为，生产接入仍需显式选择获准能力。DispatchScope 也只有在真实 Admin／HTTP 子调用都经过该接口时才能覆盖整条业务链路。

未启动 Docker、项目服务或真实模型；本地 HTTP 测试仅使用临时随机回环端口的后端夹具。Issue #62 保持开放，P0 的本批基础实现和后续阶段分别验收，不能据此标记 R01–R19 全部关闭。
