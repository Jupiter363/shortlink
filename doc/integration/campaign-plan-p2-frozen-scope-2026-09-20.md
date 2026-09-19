# P2 第一批：冻结成员与跨服务范围证明

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。承接 P1 的统计固定执行器，为当前授权装配与多期间分析补齐固定对象合同；新运行入口仍关闭。

## 解决的问题

旧授权版本包含整个分组的 revision，即使查询原本只针对 A/B，新增无关 C 也会改变版本。另一个边界是旧接口中空 linkIds 表示不筛选，不能用于表达固定空集合。

新增指定成员授权接口仅绑定当前主体、gid、所选 ID 及各成员 ownershipVersion。新增 C 不改变 A/B；删除、移组或撤销原成员权限则拒绝，不缩小集合继续分析。空集合仍检查当前分组权限，返回空集合，不查询全组。新协议严格拒绝未知字段、重复／乱序 ID、浮点数及数字字符串。

## 实现范围

- 共享 `FrozenQueryScope` 定义闭合的父集合／分片合同及规范成员 hash；500 是单片大小，没有把它变成父集合上限。
- `FrozenCampaignScope` 验证可信完整枚举页的主体、版本和游标连续性，501 成员产生 500＋1 确定性分片。授权空集合不产生查询。它是纯冻结器，不把传入的 owner 字段当作真实权限证明，实际耐久枚举收集器仍待接线。
- Command 新增 `resolve-selected`／`analytics-selected`；Admin 新增 `authorize-scope` 和冻结 jobs／recover-existing；Analytics 新增 `/jobs/frozen` 及专用恢复入口。新请求不支持 fullShortUrl 混用；旧入口拒绝 scope，协议不支持时不回退旧接口。
- 原 scope 纳入 Analytics 请求哈希、持久化正文、执行及后续每次授权。结果 `meta.scopeProof` 绑定原父集合和原分片；`shardComplete=true`、`parentComplete=false`、`groupScopeComplete=false`。单片不证明整个父集合或当前分组已分析完成。
- Agent 固定执行器使用专用提交；丢 ACK 只恢复原冻结请求；接收器核对精确成员、原 proof、期间和质量。完整分页落盘后才发布带 FROZEN_SET provenance 的 Artifact，不能仅凭响应出现 proof 升级旧请求。
- 旧同步查询提前拒绝新 scope，避免使用未绑定范围证明的旧分页合同。原 CURRENT_GROUP 路径及原 JSON 哈希兼容保留。

## 最小后端验证

定向选择 40 项：共享合同 1、Command 3、Analytics 9、Admin 5、Agent 22。覆盖新增 C 不漂移、撤原成员拒绝、空集、501 成员分片、两期间同成员、专用 HTTP 路径、旧服务不回退、恢复零 INSERT、错误 proof 拒收、丢 ACK 后恢复及 501 行分页发布。

首次执行中 39 项通过；一个新测试将 LongNode 与 JSON 读回的 IntNode 直接比较，数值相同但节点类型不同。仅修正该测试的序列化后比较方式并重跑这一项，已通过，没有重复已通过用例。最终 40 项全部通过、无跳过。

只使用 H2、原生 Graph、脚本化网关及短生命周期 HTTP fixture；没有启动 Docker、应用服务或真实模型。未验证真实 MySQL 事务代理／并发、实际部署统计链路或前端。

## 继续推进

P1 生产装配与同步复合工具仍待完成。P2 后续需要耐久枚举和父集合收集对账、结果配额分账／受控释放，再实现全量下降筛选和联合维度变化；此批不宣称父集合完整交付或 P2 完成。完整剩余要求保存在[全计划完成矩阵](../development/campaign-agent-completion-2026-09-20.md)。
