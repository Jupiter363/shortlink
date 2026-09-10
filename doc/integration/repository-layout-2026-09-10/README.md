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

这一阶段没有运行真实数据库集成、完整 Agent/短链 E2E 或压测。以下 R2 独立记录迁移后的候选验收，历史报告不作为本批次结果。

## R2：模块分层与候选验收

对应 [PR #8](https://github.com/Jupiter363/shortlink/pull/8)。受测源码提交为 `30eebff331ff9a9cf3ab750db9c1ccc5d4ffbfc9`，tree 为 `69e62a7c5761d26273aae89b824053fcc6750fa5`。使用 `git -c core.autocrlf=false archive` 导出不含 `.git` 和 `target` 的独立候选，1048 个文件逐项核对 Git blob 身份；构建和脚本测试前后源码哈希相同。受测源码冻结后仅补充文档与本批验收收据，计数不包含随后新增的收据文件。

**G1–G6 已全部通过。** 汇总结果见 [migration-validation.json](migration-validation.json)。本轮临时 Java 进程、4 个依赖容器和专用 Docker 守护进程均已停止，测试数据留存。

候选位于本机 `.work/repository-layout-execution-20260910/candidate/`，构建使用 WSL `shortlink-refactor-it` 的 Java 17.0.20、Maven 3.8.7 和已有依赖缓存。没有借用旧目录的 `target` 或 JAR。

| 门禁 | 验收结果与证据 |
| --- | --- |
| G1 文件守恒 | 基线 1038 个受管文件全部保留，678 个模块文件迁移；450 个生产 Java 文件原始字节不变。逐项映射见 [migration-manifest.json](migration-manifest.json) |
| G2 Maven 闭合 | 根直接聚合 11 个叶子，所有 parent 实指候选根；实际运行根坐标选择加 `-am validate`，以及新叶子目录的 `validate`。见 [path-validation.json](path-validation.json) |
| G3 构建与回归 | 根 `clean package` 成功；Java 856 项、Python 164 项全部通过，失败、错误、跳过均为 0；Node 64 组通过。见 [Java](java-validation.json) 与 [脚本](script-validation.json) 收据 |
| G3 制品 | 7 个 Boot JAR、3 个普通库 JAR、1 个 Flink Shade JAR 全部生成；检查主类、生产配置、内部依赖、许可证和制品 SHA。见 [artifacts.json](artifacts.json) |
| G4 路径与命令 | 10 项新增 Python 路径测试覆盖三个真实入口的候选 JAR 解析、命令与状态哈希；实际编译的两套 Java 定位器从候选根及两个新叶子目录完成 6 次文件定位，SQL/脚本 SHA 匹配。三个 PowerShell 入口另有 R1 的 17 项检查，未重复累计 |
| G5 定点组件启动 | Windows Java 17.0.16 实际启动候选 Command/Gateway/Redirect；Command health/risk ready、Gateway/Redirect liveness/整体 health/Prometheus 均通过，进程和端口已释放。见 [component-validation.json](component-validation.json) |
| G6 文档与来源 | 核对现行 Markdown 本地文件目标、README 原 25 个二级标题、选定架构图；279 个受保护文件以及 Leaf 上游 13 个原件保持。见 [源码与来源](source-audit.json) 和 [文档](documentation-validation.json) 收据 |

实际 Java 默认测试数量如下；默认排除的 Integration/E2E 用例没有混入这些数字：

| 模块 | 执行数 | 模块 | 执行数 |
| --- | ---: | --- | ---: |
| event-contract | 41 | id-generator | 19 |
| risk-core | 101 | shortlink-command | 86 |
| shortlink-redirect | 157 | analytics-flink | 2 |
| analytics-worker | 3 | shortlink-analytics-api | 33 |
| admin | 113 | gateway | 17 |
| agent-service | 284 | 合计 | **856** |

根候选执行命令如下。完整运行版本、时长、每模块 XML 与日志哈希保留在 Java 收据中。

```sh
mvn -o -B -ntp \
  -Dmaven.repo.local=/mnt/c/Users/Jupiter/.m2/repository \
  -Dsurefire.reportNameSuffix=layout-final -fae clean package
python3 -B -m unittest discover -s scripts/performance -v
node scripts/performance/test_workload_diagnostics.js
```

Python 最终在 Linux 执行；首次 Windows 运行的 162 项通过、2 项错误仍保留，原因是既有 Linux 定时器测试依赖 `signal.getitimer`。没有修改或跳过用例来获得通过。Node 使用模拟传输，不启动 k6、发送业务 HTTP 或读取私有配置。

G5 因 JDK 发现方式变化而补测，使用显式 `SHORTLINK_IT_JAVA_HOME`，同时将 `JAVA_HOME` 指向不存在的位置，证明真实执行器选择了明确指定的 Java 17。观测钩子将原候选启动器的调用转发给真实 `Popen` 和 HTTP，核对进程句柄、实际执行路径、参数、JAR SHA 和端口拥有者，没有模拟 Java 进程、健康响应或 Command 依赖。

此次另建隔离 Compose 项目与唯一数据库，使用真实 MySQL/Redis/Kafka/MinIO；Command、Redirect 的初始化可能更新本轮号段、generation 和 Redis marker，不能称为只读检查。只验证 MinIO 配置，未验收对象上传或桶操作。Gateway/Redirect 初始化期间的 503 记录保留，最终等待整体 health UP 后才判通过。Docker 默认地址池不足的初次失败也保留；随后选择无冲突子网，没有清理历史网络或启动历史容器。

## 本地归档与验收边界

本地旧 `project/`、`aggregation/`、Nginx 目录/压缩包、`__MACOSX/` 及三份根日志，共 8 个条目、207 个文件、26,482,277 字节，迁移到 `.work/repository-layout-execution-20260910/local-archive/`。前后 SHA 全部一致，没有删除私有配置或历史数据，归档不提交 Git。恢复方法保留在该归档目录的 README 中。

第一次工作树原始字节比较发现 CRLF/LF 差异，该诊断没有覆盖；最终候选直接核对 Git blob 原始字节，不用规范化哈希替代。迁移还发现原已受管的 Gateway YAML 被通用忽略规则隐藏，已在受测提交中保留原 blob，且验证生产 JAR 仍排除该文件。

本批不重跑容量压测、业务 E2E、完整 Agent/LLM 集成或统计消费链路。目录整理不新增 QPS 结论。原始日志、JUnit XML、候选和归档位于本机 `.work/repository-layout-execution-20260910/`；这里发布可追溯的摘要及哈希，本地路径不是远端下载地址。

[返回文档目录](../../README.md) · [当前开发指南](../../development/repository-layout.md)
