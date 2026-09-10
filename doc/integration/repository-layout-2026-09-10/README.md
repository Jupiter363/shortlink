# 仓库结构整理验收记录

跟踪 [Issue #6](https://github.com/Jupiter363/shortlink/issues/6)，方案见[整理计划](../../plan/仓库结构整理/01-现状分析与整理计划.md)。原始基线为 `896e2ceffab926b7986ebbd986738fde12a98b71`。

## R1：入口与路径准备

受测代码提交：`2ea9415af1abee61a668904cac9290b6fdc7389a`。这一阶段模块仍在根目录，改用稳定的 Maven artifactId 选择器、显式依赖构建，以及可识别仓库根的测试文件定位。

| 检查 | 结果 |
| --- | --- |
| Java 路径单测 | Analytics API 13 + Agent 13 = 26；失败、错误、跳过均为 0 |
| PowerShell 语法与控制流 | 17 项通过；使用精确脚本副本和假 Maven，未启动真实服务 |
| 真实 Maven 选择器 | 按 Command/Redirect/Gateway/Admin/Agent artifactId 加 `-am validate`，成功解析 reactor |
| 当前新增/修改文档 | 79 个本地链接有效 |
| 独立复核 | 未发现路径准备的阻断问题；原业务断言、隔离授权和 E2E 标签保留 |

详情见 [prep-validation.json](prep-validation.json)。Java 测试运行期间源码不变；完成后只去除了四个辅助文件末尾多余空行，收据保留受测与最终文件哈希及格式等价检查。单测在既有 Java 17 / Maven / 依赖缓存中执行。

这一阶段没有运行真实数据库集成、完整 Agent/短链 E2E 或压测。目录迁移后的干净构建、制品和路径验收另行记录，历史报告不作为本批次结果。

[返回文档目录](../../README.md) · [当前开发指南](../../development/repository-layout.md)
