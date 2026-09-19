# 投放分析 P0：续接、Skill 与模型响应边界

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [首批 P0 验证](campaign-plan-p0-2026-09-19.md)。状态：P0 组件门槛通过，新运行器仍未接生产入口。

## 本批改动

- WAITING→READY：从可信账本重建原工具调用及唯一 PENDING 响应，READY 通过新的结果观察提供 Artifact 引用；重复续接不重新提交原查询。复用原生 ReactAgent 与消息替换机制。不同 Assistant 消息可以复用工具 ID，当前配对由 PENDING job 与可信 action 共同校验。
- Skill：使用原生 Scanner／Registry／Hook，按获批版本和实际可读内容摘要固定方法包；摘要对象为原生解析后的 name／description／fullContent，采用 UTF-8 字节长度前缀编码。读取方法不会扩大冻结工具范围，工具派发前重新检查当前权限；不暴露禁用工具、脚本或附属文件读取能力。
- 模型 HTTP：在成功响应反序列化及错误体读取之前逐字节计量；无 Content-Length 也生效，超限关闭流并明确失败。限制 JSON 嵌套深度，拒绝尾随额外内容，完整合法分析不截断。

模型配置沿用 `short-link.agent.deepseek`：`max-response-bytes` 默认 16 MiB，`max-response-nesting-depth` 默认 128，可在外部配置中调整；不改变现有输出 token 配置，不调整 Docker 资源。字节限制控制单次响应读取，并不代表完成了部署内存容量验证。

## 定向验证

2026-09-19 22:17（Asia/Shanghai）完成，以下 **61 项相关用例全部通过，零跳过**。按仓库 [AGENTS.md](../../AGENTS.md)，只运行新增功能及直接受影响的测试，不重复上一批 795 项默认测试。

| 测试类 | 用例 | 验证内容 |
| --- | --- | --- |
| DeepSeekTransportBoundaryTest | 6 | 无长度头的成功／错误体、提前拒绝超长头、UTF-8 边界完整内容、深层 JSON、尾随超长载荷 |
| RunPinnedSkillsTest | 14 | 原生 Agent 挂载、固定内容、版本漂移、限定目录、工具交集与实时撤权 |
| NativeExplorationResumeTest | 9 | 新适配器续接、唯一 PENDING／READY 观察、坏收据／坏配对、写入失败重试、阻止重提及历史 ID 复用 |
| NativeExplorationAdapterTest、NativeExplorationCheckpointTest | 32 | 直接受接口与 Hook 改动影响的既有原生执行和 checkpoint 用例 |

首次定向运行后，只修正续接测试的不可变 checkpoint 夹具及跨消息 ID 校验，再仅重跑 `NativeExplorationResumeTest`；已通过且未受修正影响的另外 52 项不重跑。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=GBK'
mvn.cmd -o -pl services/agent-service '-Dtest=DeepSeekTransportBoundaryTest,RunPinnedSkillsTest,NativeExplorationResumeTest,NativeExplorationAdapterTest,NativeExplorationCheckpointTest' '-Dnet.bytebuddy.experimental=true' test
# 修正后仅复验受影响类
mvn.cmd -o -pl services/agent-service '-Dtest=NativeExplorationResumeTest' '-Dnet.bytebuddy.experimental=true' test
```

## 集成边界

原生组件测试使用脚本模型、内存 saver、可信账本替身与响应流夹具，不启动 Docker、项目服务或真实模型。当前规范续接处理一次未完成 action；P1 仍需实现持久化 Run／Action／Child、Artifact、多次推进与跨服务恢复。Skill pins 也须由该可信运行层保存和恢复；不从模型参数创建。生产权限、派发与共享执行器接入按 P1／P3 验收，组件通过不代表新运行器已上线。
