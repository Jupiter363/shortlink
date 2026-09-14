# 访问统计专项 UAT

- 时间：2026-09-14T13:30:56.438Z
- 入口：http://127.0.0.1:5174/home/analytics
- 账户：Jupiter
- 方式：隔离 headless CDP；除登录外只执行 GET 查询，没有业务写操作。

## 真实后端验收

| 结果 | 验收项 | 证据 |
|---|---|---|
| PASS | 默认授权分组通过真实 stats/group 返回统计 | `{"status":200,"pv":12,"completeness":"PARTIAL"}` |
| PASS | 默认分组核心指标与真实响应一致 | `{"ui":["12","12","1","0"],"response":[12,12,1]}` |
| PASS | 有数据分组切换后使用真实授权 gid | `{"label":"UAT统计维度样本 · 2 条","gid":"a5368545d24b4cf792c3c874597eb40e","pv":3,"uv":2,"uip":2,"completeness":"PARTIAL"}` |
| PASS | 有数据分组核心指标与真实响应一致 | `{"ui":["3","2","2","0"]}` |
| PASS | 页面披露后端完整度与采集状态 | `{"completeness":"PARTIAL","collection":"UNKNOWN"}` |
| PASS | 统计维度可切换：国家 | `{"rows":1}` |
| PASS | 统计维度可切换：省份 | `{"rows":1}` |
| PASS | 统计维度可切换：24 小时 | `{"rows":24}` |
| PASS | 统计维度可切换：星期 | `{"rows":7}` |
| PASS | 统计维度可切换：高频 IP | `{"rows":2}` |
| PASS | 统计维度可切换：操作系统 | `{"rows":1}` |
| PASS | 统计维度可切换：浏览器 | `{"rows":1}` |
| PASS | 统计维度可切换：设备 | `{"rows":1}` |
| PASS | 统计维度可切换：运营商 / ISP | `{"rows":1}` |
| PASS | 网络维度明确为运营商 / ISP |  |
| PASS | 统计维度可切换：新老访客 | `{"rows":2}` |
| PASS | 新老访客披露保留数据首次观测语义 | `{"backendStatus":"AVAILABLE"}` |
| PASS | 按日趋势支持真实表格视图 |  |
| PASS | 按日趋势支持真实图形视图 |  |
| PASS | 8 天窗口在客户端显示 7 天硬边界并禁用提交 |  |
| PASS | 无效窗口没有发起统计请求 |  |
| PASS | 失效后从首批重新读取真实访问记录 | `{"status":200,"count":3,"snapshotIdPresent":true,"hasNext":false,"completeness":"PARTIAL"}` |
| PASS | 访问记录 UI 保留快照时间且不推断总页数 |  |
| PASS | 单链范围发送 gid 与 fullShortUrl 到真实 stats | `{"label":"Example Domain · 68hlRoGwl","gid":"1ff4df996bf244bdb86865533a92b12c","fullShortUrl":true,"pv":7}` |
| PASS | 单链指标与真实响应一致 | `{"uiPv":"7","responsePv":7}` |
| PASS | 统计详情 1280px 无页面横向溢出 |  |
| PASS | 统计详情 768px 无页面横向溢出 |  |
| PASS | 统计详情 390px 无页面横向溢出 |  |
| PASS | 统计专项 UAT 无未捕获运行时异常 |  |

## 故障注入验收

| 结果 | 验收项 | 证据 |
|---|---|---|
| PASS | 故障注入：快照失效进入明确恢复态 | `{"injectedCode":"SNAPSHOT_EXPIRED"}` |

故障注入只在隔离浏览器内拦截访问记录请求并返回 `SNAPSHOT_EXPIRED`，随后关闭拦截并从首批调用真实接口恢复。

## 观测摘要

```json
{
  "group": {
    "label": "UAT统计维度样本 · 2 条",
    "gid": "a5368545d24b4cf792c3c874597eb40e",
    "pv": 3,
    "uv": 2,
    "uip": 2,
    "completeness": "PARTIAL"
  },
  "dimensions": [
    {
      "label": "国家",
      "selected": "国家",
      "rows": 1
    },
    {
      "label": "省份",
      "selected": "省份",
      "rows": 1
    },
    {
      "label": "24 小时",
      "selected": "24 小时",
      "rows": 24
    },
    {
      "label": "星期",
      "selected": "星期",
      "rows": 7
    },
    {
      "label": "高频 IP",
      "selected": "高频 IP",
      "rows": 2
    },
    {
      "label": "操作系统",
      "selected": "操作系统",
      "rows": 1
    },
    {
      "label": "浏览器",
      "selected": "浏览器",
      "rows": 1
    },
    {
      "label": "设备",
      "selected": "设备",
      "rows": 1
    },
    {
      "label": "运营商 / ISP",
      "selected": "运营商 / ISP",
      "rows": 1
    },
    {
      "label": "新老访客",
      "selected": "新老访客",
      "rows": 2
    }
  ],
  "records": {
    "status": 200,
    "count": 3,
    "snapshotIdPresent": true,
    "hasNext": false,
    "completeness": "PARTIAL"
  },
  "link": {
    "label": "Example Domain · 68hlRoGwl",
    "gid": "1ff4df996bf244bdb86865533a92b12c",
    "fullShortUrl": true,
    "pv": 7
  }
}
```
