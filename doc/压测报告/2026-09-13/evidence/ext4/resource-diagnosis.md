# ext4 7k 失败：现有资源窗口诊断

当前证据最明显的变化是 **EDGE 发送推进变慢并触发队列拒绝**，尚未定位到编码、metadata、网络、Kafka ACK 或调度中的某一步。新 run `20260912T165811Z-a308ae92` 的 7k 正式阶段只运行 **10.228 秒**：70,123 个正式 HTTP 正确，全部阶段 176,623 个 HTTP 正确，正式 P99 523.309ms、丢弃到达 1,474，EDGE rejected 增量 3,260；原始 verdict 保持 `CAPACITY_STAGE_FAILED`。此前一次 7k/60s PASS 现在不能表述为可复现的稳定平台。

## 比较窗口与主要差异

原7k窗口为 UTC 16:37:08.495–15.027（6.532s，正式起点前0.547s至后5.985s）；新7k为17:02:22.403–29.037（6.633s，正式起点后0.889–7.523s）；原control8k为16:50:21.225–27.882（6.658s，正式起点后0.281–6.939s）。这些是相近早期负载窗口，不是完全同相位的因果对照。阶段采样顺序读取，新7k最后一次采样花费2.498s；JSON保留了每次时长与最近1s系统采样的偏差。

| 指标 | 原7k PASS窗口 | 新7k失败窗口 | 原control8k失败窗口 |
| --- | ---: | ---: | ---: |
| Redirect分配率（MiB/s） | 216.93 | 218.14 | 245.59 |
| GC次数增量 | 43 | 44 | 41 |
| GC累计pause增量 | 0.114s | 0.128s | 0.103s |
| GC pause / 窗口时长 | 1.75% | 1.93% | 1.55% |
| GC max gauge（窗口末） | 10ms | 10ms | 8ms |
| EDGE完整send均值（累计差/attempt差） | 2.951ms | 9.375ms | 4.986ms |
| EDGE queued（起→止） | 230→15 | 182→1699 | 62→510 |

新7k EDGE active senders由6→8，pending由347→1955，queued由182→1699，send最大值由90ms→252ms，拒绝从0→1405；之后最终拒绝总增量达到3260。完整send均值约为原7k的 **3.18倍**。这个计时包含编码、metadata、网络和调度，不是独立ACK时延；累计max仅表示峰值抬升，不能把max相减当区间峰值。当前能确认的是队列推进能力发生变化，不能据此直接归为Kafka broker容量不足。

分配率与GC累计停顿没有数量级增长。GC max是已有窗口gauge，不能排除未覆盖时刻的暂停或GC并发CPU成本；约218MiB/s分配仍是后续可优化对象，但这组对照没有显示它是新一轮恶化的主要变化。

## CPU、Kafka和等待证据

主CPU比较采用约1s系统采样；100%表示一个逻辑CPU，不是整机利用率。与上述阶段窗口最近的系统端点跨度分别为6.999s、6.002s、6.998s。

| 对象 | 原7k | 新7k | 原control8k |
| --- | ---: | ---: | ---: |
| Redirect CPU | 268.05% | 260.75% | 262.20% |
| APISIX四worker CPU范围 | 70.44–74.58% | 74.64–77.97% | 73.44–81.73% |
| Kafka CPU | 59.44% | 62.98% | 59.87% |
| 生成器CPU | 181.89% | 185.94% | 188.76% |
| Kafka进程write_bytes速率 | 11.825MiB/s | 11.875MiB/s | 13.381MiB/s |

新7k guest整体busy约58.2%，原7k约60.3%；记录到的cgroup `nr_throttled`和`throttled_usec`增量均为0。新7k所取6.002s系统窗口的CPU some PSI增加1,017,241µs（约16.95%），原7k6.999s增加1,255,852µs（约17.94%）；内存PSI增量均0。I/O some PSI分别增加16,484µs与19,122µs。低平均占用或零配额节流不排除短暂停顿、单worker热点、宿主抢占或guest观察不到的等待，不能据此宣布硬件上限。

新7k窗口末，Java click/result Kafka客户端 request-latency-avg约1.60ms，max为33/25ms，record-queue-time-avg约4.84/4.95ms；bufferpool等待、重试、错误累计均为0。它们是Java生产者原生窗口指标，不能代替Lua EDGE的发送观测，也不能排除共享broker的短时波动。Kafka `write_bytes`仅提供进程I/O字节计数，不包含磁盘服务时延；`rchar/wchar`还包含socket操作，不能当物理磁盘吞吐。

## 剩余边界与下一步

现有guest归档没有CPU频率、宿主功耗/温度或物理磁盘时延。主线事后观察到Windows其他进程占用CPU，只能证明需要同步宿主观测；不能倒推此前失败由这些进程引起。WSL的cpuset也不等于独占宿主物理CPU。

下一轮应将宿主与guest CPU/调度、EDGE逐worker队列首次增长、send各步骤耗时和Kafka请求响应时间放在同一负载窗口观测。先形成资源干扰与发送步骤证据，再判断应调整架构、实现还是扩容；本次不能把ext4与失败的先后关系当因果。

来源SHA、逐窗口原计数与完整限制见[resource-diagnosis.json](resource-diagnosis.json)。仅Windows解析既有metrics/system日志及随后获准读取的result；没有重测、请求服务或改动已冻结文档。STOP。
