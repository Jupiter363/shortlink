# fanout-r8000 离线失败取证

原始 **CAPACITY_STAGE_FAILED 保留**。完整 native 785,077,114B 已经受限流式解析并与 result 的 SHA 核对，600,699 个已发请求全部收到 HTTP：600,580 个302、119个504。601,500个计划到达 =600,699个已发+801个未发，取消0。schema2覆盖601,500/遗漏0、逐请求和owner归还守恒通过。native `conservationPassed=true`；`measurementComplete=false` 与 `scheduling_complete=false` 是独立结果，不应把原复合门禁 `nativeConservationVerified=false` 解读成原始证据丢失。

119个异常响应均为客户端 `HTTP_STATUS_OTHER` / HTTP504，全部复用连接，连接请求序号292–293。它们在elapsed89,598.385466–89,768.998ms开始，RT262.824870–438.545075ms，完成于13:41:03.439102–03.486810 UTC。没有逐ID的BUSINESS事件或响应业务reason，因此不能称为Java `AUTHORITY_UNAVAILABLE`。

根代理随后提供的既有、有身份绑定的错误日志已离线核SHA：13:41:03.438940973–03.488935016 UTC恰有119条 `while connecting to upstream` 超时，全部来自容器内worker PID50；没有其他worker的该类记录。计数和时间与119个504吻合，且Java click/BUSINESS均未新增失败请求对应事件，支持故障落在APISIX→Redirect上游建连阶段。错误日志没有native request ID，不能宣称119条逐ID一一对应，也不能据此决定内核、accept队列、事件循环或keepalive阈值中的具体根因。PID50是日志中的容器命名空间PID，不直接等于宿主采样PID。

| 到达类型 | 数量 | 时间与含义 |
| --- | ---: | --- |
| SOURCE_BUSY | 716 | 实际attempt/decision elapsed89,851.247883–89,989.250417ms，全部早于native停止；sourceIdle全0、globalActive1846–2048。这是实际来源槽占满的证据。 |
| DISPATCH_WINDOW_CLOSED | 85 | 计划due89,989.375–89,999.875ms；没有实际attempt（NONE/null）；统一decision90,001.294849ms，全部晚于native停止和90s计划截止。它们是窗口关闭归类，不能称WORKER_LATE。 |

| 阶段边界（UTC） | 证据 |
| --- | --- |
| 13:39:33.449395253 | native单调计时原点 |
| 13:40:03.449395253 | 正式窗口开始 |
| 13:41:03.439093122 | 第一504触发native停止；elapsed89,989.697869ms |
| 13:41:03.449395253 | 原计划90s截止 |
| 13:41:03.696303左右 | 最后一个实际HTTP完成 |
| 13:41:03.696379599左右 | native runtime.totalElapsedMillis=90,246.984346，对应请求wait完成取时 |
| 13:41:29.807217 | 记录的client进程区间结束；相对wait完成约26.111s |
| 13:42:15.484934 | stage导出/核账全部结束，result落盘时间 |

严格实际正式窗为 **59.989697869s**，该窗内477,176个正确完成，约7,954.30QPS。原result按完整60s窗口统计477,185个正确完成/7,953.08QPS，另有9个正确响应在native停止后、计划截止前完成；两者计时口径不同，均不能当作该轮容量验收通过。计划截止后仍有1,999个HTTP完成，其中1,908正确、91个504；尾请求完成与汇总/导出耗时必须分开。原 `STAGE_WALLCLOCK_BUDGET_EXCEEDED` 和native退出码2保留；进程晚退出不能代表还在持续施加8k业务负载。阶段deadline的准确实现边界由根代理的独立源码审计交接，未伪造未记录的具体SIGINT时刻。

同代producer身份和本地守恒已核，所有before/after数值均保留，不把缺series补0：

| 链路 | attempted增量 | delivered增量 | failed/rejected增量 |
| --- | ---: | ---: | --- |
| click | 600580 | 600580 | 0 / 0 |
| BUSINESS/result | 600580 | 600580 | 0 / 0 |
| EDGE | 600699 | 600699 | 0 / 0 |

Kafka broker、topic ID和partition集合前后相同；click.raw增600,580，gateway.request增1,201,279，总 **1,801,859**。它精确等于三路delivered总数，也等于 `3×600580成功302 + 119额外EDGE事件`。原成功HTTP×3公式为1,801,740，差119由这种聚合事件形态解释，不能据此称Kafka消息丢失。旧Java503的每错误两条事件模型在此不适用；主分析JSON故意保留该候选公式及其false比较，最终summary明确本轮一条EDGE的模型。聚合吻合仍不证明每个ID恰好一次，也不能排除抵消的丢失/重复。

Java GET400/403既有series增量0；前后未见504series，缺series按null/不可作差处理。没有新的Kafka查询、HTTP、容器或资源操作。已有root日志仅离线读取，原始文件未修改。

证据：`failure-analysis-native.json` SHA `7a348380dc620874ca23ca1b8ccddeb94f4cc1000a32e63af7834b0bfd77cba7`；精简结论 `failure-analysis-summary.json` SHA `fa0218d09c2cbb613de213944ea7082944c7c17b4685388a97d3e9b561adf0c4`；85条关闭决策证据在 `failure-analysis-drop-decisions.json`。主脚本SHA `17b5a418264ea2196453d45ac2d15a3c3d182da2b7f8f7ae55c6ac7b1d90823e`。

复用原SHA固定的受限JSONStream（单对象32KiB、单数组1m、单native2GiB），主解析21.514s、命令退出0；补充决策读取使用4MiB块并再次核完整nativeSHA，6.216s、命令退出0。该lane以真实全量守恒、schema覆盖和SHA验收，未新增模拟单元测试。命令均为：

```text
wsl.exe -d shortlink-refactor-it -u root --exec taskset -c 6-7 python3 -B /mnt/d/学习/Project/马哥Project/shortlink/.work/real-fanout-20260909/failure-analysis.py
wsl.exe -d shortlink-refactor-it -u root --exec taskset -c 6-7 python3 -B /mnt/d/学习/Project/马哥Project/shortlink/.work/real-fanout-20260909/failure-analysis-drop-decisions.py
```

两命令已完成，输出独占创建，不能覆盖重跑。所有计算已经停止；没有改变原FAIL、预算、产品或冻结工具。
