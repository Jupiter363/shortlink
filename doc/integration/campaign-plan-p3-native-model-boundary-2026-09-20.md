# P3 原生模型调用边界 · 2026-09-20

关联 Issue #62，接续模型调用账本 E26。本批继续使用 Spring AI Alibaba 1.1.2.3 的 ReactAgent 和 ModelInterceptor，不新增模型循环。

## 范围

新增可选 `ModelCallBoundary`，旧适配器构造方式保留。服务端为本批边界提供固定轮次的 StepPermit、调用身份和批准后的请求；边界不从 checkpoint、请求 hash 或内存计数器推断下一轮。

实际请求需与冻结请求一致。模型响应先通过大小和公开内容校验，再保存为 MODEL 响应与 READY；只有保存成功后才返回原生 Graph。已保存响应直接回放，不再次请求模型。首次和回放统一由公开 DTO 构造助手消息。

调用边界拒绝外层尚未提交的事务，确保模型请求发出之前，原身份与活跃回调已经提交。实际调用之前重验当前 Step、权限、输入完整性及有效期；正常、失败和取消均由实际回调退出释放资格。

原生 handler 可能将异常消息包装为助手文本而不提供 ChatResponse。新路径须拒绝该回退，不能将异常当作成功回答保存；对外仅暴露固定错误码。旧 P0 路径保持原有行为。

## 验证

`NativeDurableModelBoundaryTest` 两项首次通过：

- `durableResponseSurvivesNativeCheckpointFailureAndReplaysWithoutAnotherModelCall`：真实 H2 保存响应后，真实 MemorySaver 写入失败；使用同一有效 StepPermit 重建 store、adapter 和全新 saver，模型调用累计一次、MODEL child 和响应各一条、工具仅在回放后执行一次。公开文本和 toolCall 保持一致，checkpoint 和响应表不含 provider reasoning/metadata。
- `cancelledLateModelResponseAndUnknownTurnCannotPublishDispatchToolsOrRetryProvider`：真实脚本模型阻塞期间取消 Run，实际退出前 callback 保持活跃；晚到响应不发布，不调用工具或下一轮模型。供应商异常经原生框架回退后仍为 UNKNOWN，同槽重开不重试，敏感异常不进 checkpoint。

集成时补充“外层事务未提交则零派发”的检查，并在第一项中增加真实 TransactionTemplate 反例；随后只重跑该项并通过，未重复无关用例。有效日志：`.work/native-model-boundary-tests.log` 两项通过；`.work/native-model-commit-boundary-test.log` 受影响单项通过。

运行使用原生 ReactAgent、H2 和本地脚本 ChatModel；工具投影仍为已有 P0 测试账本替身。没有启动 Docker、应用或真实模型请求。

## 后续边界

这是固定模型轮次的持久化与回放组件；工具动作仍需耐久接纳、规范消息重建、观察序列与确认，后续还需跨轮次预算和正式输出验收。使用同一有效 StepPermit 的回放不等于完整 Run 跨进程恢复。Driver REACT 与生产入口继续关闭。

本批不验证进入拦截器之前的模型传输反序列化峰值，也不替代真实 MySQL、供应商模型或客户端验收。
