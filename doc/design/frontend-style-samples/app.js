/* Three visual studies, sharing real Element Plus controls and illustrative data.
 * No API calls, authentication changes, or production frontend modifications. */
const {createApp,computed,ref}=Vue;
const styles={
  a:{letter:'A',name:'精密蓝白',description:'清晰分层 · 细线栅格 · 高效操作',colors:['#3157F6','#82C8FA','#DDEAFF','#FFFFFF']},
  b:{letter:'B',name:'明亮几何',description:'编辑排版 · 顶部导航 · 鲜明辨识',colors:['#C8432B','#F2D852','#B6CEF9','#FFFDF9']},
  c:{letter:'C',name:'柔和彩色',description:'轻盈色面 · 列表详情 · 专注上下文',colors:['#087A7A','#BDEFE2','#D7CEFD','#F7FAFF']}
};
const rows=[
 {title:'秋季新品 · 主会场',code:'7kN2mQ8pL',url:'https://example.com/autumn/new-arrivals',status:'生效中',expiry:'永久有效',today:'1,286 / 904 / 872',total:'14,286 / 6,420 / 5,708',created:'2026.09.14',color:'blue',icon:'globe'},
 {title:'小红书 · 种草内容',code:'4rT9wL6cA',url:'https://example.com/autumn/community',status:'生效中',expiry:'2026.10.31',today:'846 / 615 / 584',total:'8,462 / 4,280 / 3,901',created:'2026.09.13',color:'rose',icon:'browser'},
 {title:'会员专享 · 提前购',code:'9hB3vR5xD',url:'https://example.com/autumn/members',status:'即将到期',expiry:'2026.09.16',today:'684 / 492 / 470',total:'6,084 / 3,560 / 3,214',created:'2026.09.12',color:'violet',icon:'user'},
 {title:'公众号 · 上新推文',code:'2cF8nP4yK',url:'https://example.com/autumn/article',status:'生效中',expiry:'永久有效',today:'510 / 380 / 359',total:'5,210 / 2,810 / 2,520',created:'2026.09.11',color:'mint',icon:'code'},
 {title:'线下门店 · 海报入口',code:'6mJ1sW9bE',url:'https://example.com/autumn/stores',status:'生效中',expiry:'2026.10.31',today:'298 / 214 / 205',total:'2,980 / 1,720 / 1,501',created:'2026.09.10',color:'amber',icon:'console'},
 {title:'社群福利 · 限时活动',code:'3pV7aH2zN',url:'https://example.com/autumn/community-offer',status:'生效中',expiry:'2026.09.30',today:'145 / 108 / 102',total:'1,450 / 946 / 874',created:'2026.09.09',color:'sky',icon:'link'}
];
const app=createApp({setup(){
 const style=ref(new URLSearchParams(location.search).get('style')||'a'); if(!styles[style.value])style.value='a';
 const activeGroup=ref(0),order=ref('created'),selectedRow=ref(rows[0]),drawer=ref(false),drawerKind=ref('create');
 const form=ref({url:'',title:'',group:'秋季新品推广',valid:'永久有效',date:null});
 const groups=[{name:'秋季新品推广',count:24},{name:'内容渠道',count:0},{name:'活动落地页',count:0},{name:'默认分组',count:0}];
 const navs=[{icon:'link',label:'短链接'},{icon:'shield',label:'风险中心'},{icon:'chart',label:'投放分析 Agent'},{icon:'brain',label:'安全风控 Agent'}];
 const meta=computed(()=>styles[style.value]);
 const visibleRows=computed(()=>activeGroup.value===0?[...rows].sort((a,b)=>order.value==='created'?b.created.localeCompare(a.created):parseInt(b[order.value].replaceAll(',',''))-parseInt(a[order.value].replaceAll(',',''))):[]);
 const drawerTitle=computed(()=>({create:'创建短链接',edit:'编辑短链接',batch:'批量创建短链接',stats:'访问统计',qr:'短链二维码'}[drawerKind.value]));
 const drawerSize=computed(()=>window.innerWidth<600?'100%':'480px');
 function notify(message){ElementPlus.ElMessage({message,duration:2500});}
 function switchStyle(key){style.value=key;history.replaceState(null,'','?style='+key);document.title='ShortLink · '+styles[key].name;}
 function selectRow(row){selectedRow.value=row;}
 function rowClass({row}){return style.value==='c'&&row.code===selectedRow.value.code?'selected-row':'';}
 function openDrawer(kind,row){drawerKind.value=kind;if(row)selectedRow.value=row;form.value={url:kind==='edit'?row.url:'',title:kind==='edit'?row.title:'',group:groups[activeGroup.value].name,valid:'永久有效',date:null};drawer.value=true;}
 function openStats(row){selectedRow.value=row;openDrawer('stats',row);}
 async function copy(row){try{await navigator.clipboard.writeText('https://s.example/'+row.code);notify('已复制示例短链接');}catch{notify('示例地址：https://s.example/'+row.code);}}
 function submitPreview(){if(!/^https?:\/\//.test(form.value.url)){notify('请输入 http:// 或 https:// 开头的目标链接');return;}notify('样例提交完成，未写入真实业务');drawer.value=false;}
 return {styles,style,meta,groups,navs,activeGroup,order,visibleRows,selectedRow,drawer,drawerKind,drawerTitle,drawerSize,form,notify,switchStyle,selectRow,rowClass,openDrawer,openStats,copy,submitPreview};
}});
app.component('app-icon',{props:['name'],template:'<span class="icon" aria-hidden="true" v-html="svg"></span>',computed:{svg(){return window.SHORTLINK_ICONS[this.name]||window.SHORTLINK_ICONS.link;}}});
app.use(ElementPlus);app.mount('#app');
