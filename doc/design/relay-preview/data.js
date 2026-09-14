export const fixtures={
 domain:'s.example',updated:'2026-09-14 14:32',range:['2026-09-08','2026-09-14'],history:['2026-08-16','2026-09-14'],
 groupMetrics:{pv:18642,uv:7921,uip:6305},
 groups:[{id:'default',name:'默认分组'},{id:'autumn',name:'秋日投放'},{id:'docs',name:'产品文档'},{id:'social',name:'社交内容'}],
 links:[
 {id:'link-1',code:'7b3N9kX2q',title:'秋日新品 · 主会场',url:'https://example.com/autumn/launch',groupId:'autumn',created:'2026-09-07 09:20',expires:'',status:'normal',pv:12486,uv:5218,uip:4036,version:3,recycled:false},
 {id:'link-2',code:'B2N5r7YtV',title:'品牌内容 · 公众号',url:'https://example.com/stories/relay',groupId:'autumn',created:'2026-09-06 14:06',expires:'2026-10-01T23:59',status:'normal',pv:3642,uv:1522,uip:1184,version:1,recycled:false},
 {id:'link-3',code:'C8Q1p4WkL',title:'产品文档 · 快速开始',url:'https://example.com/docs/quick-start',groupId:'autumn',created:'2026-09-05 08:30',expires:'',status:'normal',pv:1806,uv:842,uip:709,version:2,recycled:false},
 {id:'link-4',code:'D3R6s9ZaM',title:'周末专场 · 分享页',url:'https://example.com/events/weekend',groupId:'autumn',created:'2026-09-04 11:30',expires:'2026-09-15T23:59',status:'expiring',pv:708,uv:339,uip:376,version:1,recycled:false},
 {id:'link-5',code:'E5T9a2BmN',title:'上一期活动 · 归档',url:'https://example.com/previous-event',groupId:'autumn',created:'2026-08-20 10:12',expires:'2026-08-31T23:59',status:'expired',pv:1260,uv:624,uip:560,version:4,recycled:true},
 {id:'link-6',code:'F8C4x6KdP',title:'旧版内容 · 分享入口',url:'https://example.com/old-content',groupId:'docs',created:'2026-08-16 08:20',expires:'',status:'normal',pv:248,uv:136,uip:122,version:2,recycled:true}
 ]
};
export const pageInfo={
 '/login':['P01','登录'], '/register':['P02','注册'], '/home/space':['P03','短链接'], '/home/recycleBin':['P04','回收站'],
 '/home/analytics':['P05','访问统计'], '/home/agent/campaign-analysis':['P06','投放分析 Agent'], '/home/risk-center':['P07','风险中心'],
 '/home/agent/security-risk':['P08','安全风控 Agent'], '/home/account':['P09','账户中心'], '/design/components':['DS','组件与状态'], '/design/coverage':['QA','设计验收']
};
export const navItems=[{route:'/home/space',label:'短链接',icon:'link'},{route:'/home/risk-center',label:'风险中心',icon:'shield'},{route:'/home/agent/campaign-analysis',label:'投放分析 Agent',icon:'chart'},{route:'/home/agent/security-risk',label:'安全风控 Agent',icon:'brain'}];
export const flows=[['F01','认证与回跳','/login'],['F02','分组管理','/home/space'],['F03','单链创建','/home/space'],['F04','批量创建','/home/space'],['F05','短链编辑与冲突','/home/space'],['F06','复制与二维码','/home/space'],['F07','统计查询','/home/analytics'],['F08','访问记录快照','/home/analytics'],['F09','回收站生命周期','/home/recycleBin'],['F10','投放分析 Agent','/home/agent/campaign-analysis'],['F11','风险中心与安全 Agent','/home/risk-center'],['F12','账户与会话','/home/account']];
