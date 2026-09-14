"""Typeset the ShortLink architecture as a standalone, outlined SVG poster."""
from pathlib import Path
import sys, json, html, itertools, re, os
ROOT=Path(__file__).resolve().parents[3]
WORK=ROOT/'.work/architecture-bloom'
WORK.mkdir(parents=True,exist_ok=True)
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from generic_icons import GENERIC_LIGHT, GENERIC_REGULAR
from brands import BRANDS

# Preserve every external icon frame; refine symbols inside those accepted frames.
ICON_OVERRIDES={
 'redis-icon':'redis', 'route-db-icon':'mysql', 'business-db-icon':'mysql',
 'agent-state-icon':'mysql', 'membership-db-icon':'mysql', 'statistics-control-icon':'mysql',
 'admin-icon':'console', 'query-admin-icon':'console', 'agent-admin-icon':'console',
 'leaf-icon':'stack', 'worker-icon':'cpu', 'archive-store-icon':'archive',
 'outbox-publisher-icon':'outbox',
}
OPTICAL_SCALE={'browser':1.05,'brain':.92,'robot':.96,'gear':.98,
               'shield':.97,'user':.98,'link':.97}

def icon_fragment(kind,size,instance):
    if kind in BRANDS:
        body=BRANDS[kind]
    else:
        library=GENERIC_LIGHT if size>=44 else GENERIC_REGULAR
        body=library[kind]
        scale=OPTICAL_SCALE.get(kind,1)
        if scale!=1:
            body=f'<g transform="translate(24 24) scale({scale}) translate(-24 -24)">{body}</g>'
    # Official brand gradients can repeat at multiple service nodes.
    for ref in re.findall(r'\bid="([^"]+)"',body):
        body=body.replace(f'id="{ref}"',f'id="{instance}-{ref}"')
        body=body.replace(f'url(#{ref})',f'url(#{instance}-{ref})')
        body=body.replace(f'href="#{ref}"',f'href="#{instance}-{ref}"')
    return body

SPACING_CUTS={'analytics': [[1853, 16], [1925, 12], [1968, 8], [2000, 10], [2026, 8], [2052, 8], [2147, 8], [2213, 14], [2265, 10], [2300, 10], [2330, 8], [2359, 12]], 'agent': [[834, 12], [909, 12], [942, 10], [978, 8], [1003, 10], [1054, 8], [1106, 10], [1148, 8], [1227, 8], [1266, 8], [1308, 8], [1344, 8], [1509, 12], [1550, 12], [1578, 12], [1583, 10], [1589, 10], [1632, 10], [1658, 12]]}
EXTRA={k:sum(delta for at,delta in v) for k,v in SPACING_CUTS.items()}
TOP_H=1000
DATA_Y=198+TOP_H+30
DATA_H=726+EXTRA['analytics']
MIDDLE_Y=DATA_Y+DATA_H+30
MIDDLE_H=1640
FOOTER_Y=MIDDLE_Y+MIDDLE_H+18
W,H=2800,((FOOTER_Y+166+19)//20)*20
C={'ink':'#17324d','muted':'#4c6279','blue':'#326cd5','teal':'#108980',
   'purple':'#7852bb','orange':'#b8671b','line':'#dce5ee','white':'#ffffff'}
TINT={'blue':'#edf4ff','teal':'#eaf8f4','purple':'#f2edfb','orange':'#fff4e6'}
WIRE={'blue':'#7899c4','teal':'#64a59f','purple':'#a28ac4','orange':'#bb9158'}
# User-directed taste redesign: vivid blue, teal, orange and violet classification.
# Saturated headings and icons, clear tinted headers, white service cards.
PALETTE={
 'redirect':dict(bg='#ffffff',accent='#2563eb',heading='#1d4ed8',border='#c0d5ff',soft='#f8fbff',wash='#f0f6ff',badge='#dbeafe',header='#e4edff'),
 'analytics':dict(bg='#ffffff',accent='#008c7d',heading='#007568',border='#a5e7d8',soft='#f8fefb',wash='#ecfcf6',badge='#cdf5e8',header='#d9f7ee'),
 'command':dict(bg='#ffffff',accent='#df500a',heading='#c2410c',border='#ffd1ae',soft='#fffaf5',wash='#fff6ec',badge='#ffe5cd',header='#ffeddc'),
 'agent':dict(bg='#ffffff',accent='#7c3aed',heading='#6d28d9',border='#dbc4ff',soft='#fcf9ff',wash='#f7f0ff',badge='#eadbff',header='#eee4ff'),
 'leaf':dict(bg='#ffffff',accent='#d66a00',heading='#c2410c',border='#f6cc8b',soft='#fffcf7',wash='#fff6e7',badge='#ffebc7',header='#fff0d8'),
}

def theme_key(id=''):
    if SPACING_ZONE=='command' and id.startswith(('leaf','allocation','current','next','reserve-range','committed-range','id-to-codec')): return 'leaf'
    return SPACING_ZONE if SPACING_ZONE in PALETTE else 'redirect'
def paint(color,id=''):
    p=PALETTE[theme_key(id)]
    if id.startswith('zone-') and id.endswith('-num'): return '#ffffff'
    if id=='title': return PALETTE['redirect']['heading']
    if id.endswith('-title') and color=='ink': return p['heading']
    if id in {'leaf-title','allocation-name'}: return p['heading']
    if color in {'blue','teal','purple','orange'}:
        if id.endswith('-icon') or id=='brand-symbol': return p['accent']
        if theme_key(id)=='leaf': return p['heading']
        if id in {'tools-title','actions-title'}: return p['heading']
        return C['muted']
    return C.get(color,color)

def frame_paint(id,fill,stroke,layer):
    p=PALETTE[theme_key(id)]
    if layer=='panels': return p['bg'],p['border']
    if SPACING_ZONE=='agent' and id=='actions': nf=p['header']
    elif fill in {'white','#ffffff'}: nf='#ffffff'
    elif id in {'command','business-db','agent-service','maintenance-lane','query-lane'}: nf=p['soft']
    else: nf=p['wash']
    return nf,('none' if stroke=='none' else p['border'])

fonts={}
for name,file,index in [('zh-r','msyh.ttc',0),('zh-b','msyhbd.ttc',0),('en-r','segoeui.ttf',-1),('en-b','seguisb.ttf',-1)]:
    ft=TTFont(str(Path(os.environ.get('ARCHITECTURE_FONT_DIR','C:/Windows/Fonts'))/file),fontNumber=index)
    fonts[name]={'font':ft,'glyphs':ft.getGlyphSet(),'cmap':ft.getBestCmap(),'upm':ft['head'].unitsPerEm,'metrics':ft['hmtx'].metrics}
layers={k:[] for k in ['background','panels','parents','edges','nodes','icons','text']}
layout={'canvas':{'width':W,'height':H},'boxes':[],'texts':[],'icons':[],'edges':[],'regions':[]}
defs={}; bounds={}
# Deliberate whitespace bands: move glyphs/icons without scaling them.
SPACING_ZONE=None
def spread(y):
    # Rebuilt sections use a direct grid; retain accepted spacing in sections 02/04.
    if SPACING_ZONE in {'redirect','command'}: return y
    return y+sum(delta for at,delta in SPACING_CUTS.get(SPACING_ZONE,[]) if y>=at)

def f(v): return f'{v:.5f}'.rstrip('0').rstrip('.') if isinstance(v,float) else str(v)
def glyph(ch,bold=False):
    family=('en' if ord(ch)<128 else 'zh')+('-b' if bold else '-r')
    ft=fonts[family]; name=ft['cmap'].get(ord(ch))
    assert name and name!='.notdef',(ch,ord(ch),family)
    key=family+':'+name
    if key not in defs:
        p=SVGPathPen(ft['glyphs']); ft['glyphs'][name].draw(p)
        b=BoundsPen(ft['glyphs']); ft['glyphs'][name].draw(b)
        defs[key]=(f'g{len(defs)}',p.getCommands()); bounds[key]=b.bounds
    return key,ft['metrics'][name][0],ft['upm']
def measure(s,size,bold=False): return sum(a/u*size for k,a,u in [glyph(ch,bold) for ch in s])
def text(id,s,x,y,size=26,color='ink',bold=False,anchor='start',parent=None):
    y=spread(y)
    specs=[glyph(ch,bold) for ch in s]; tw=sum(a/u*size for k,a,u in specs)
    pos=x-(tw/2 if anchor=='middle' else tw if anchor=='end' else 0)
    uses=[]; bs=[]
    for key,advance,upm in specs:
        gid,d=defs[key]; scale=size/upm
        if d:
            uses.append(f'<use href="#{gid}" transform="translate({f(pos)} {f(y)}) scale({f(scale)} {f(-scale)})"/>')
            x0,y0,x1,y1=bounds[key]; bs.append((pos+x0*scale,y-y1*scale,pos+x1*scale,y-y0*scale))
        pos+=advance*scale
    bb={'x':min(b[0] for b in bs),'y':min(b[1] for b in bs)}
    bb['width']=max(b[2] for b in bs)-bb['x']; bb['height']=max(b[3] for b in bs)-bb['y']
    layers['text'].append(f'<g id="{id}" aria-label="{html.escape(s,quote=True)}" fill="{paint(color,id)}"><title>{html.escape(s)}</title>{"".join(uses)}</g>')
    layout['texts'].append(dict(id=id,text=s,x=x,y=y,fontSize=size,bold=bold,bbox=bb,parent=parent,advanceWidth=tw))
def rect(id,x,y,w,h,fill='white',stroke='#dce5ee',radius=20,parent=None,layer='nodes',shadow=False):
    y,h=spread(y),spread(y+h)-spread(y)
    fill,stroke=frame_paint(id,fill,stroke,layer)
    st=f' filter="url(#card-shadow)"' if shadow else ''
    layers[layer].append(f'<rect id="{id}" x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{C.get(fill,fill)}" stroke="{stroke}" stroke-width="1.5"{st}/>')
    layout['boxes'].append(dict(id=id,x=x,y=y,width=w,height=h,parent=parent,layer=layer))
def group(id,x,y,w,h,parent=None):
    y,h=spread(y),spread(y+h)-spread(y)
    layout['boxes'].append(dict(id=id,x=x,y=y,width=w,height=h,parent=parent,layer='logical'))
def icon(id,kind,x,y,size=64,color='blue',parent=None,badge=0):
    kind=ICON_OVERRIDES.get(id,kind)
    y=spread(y+size/2)-size/2
    if id in {'redis-icon','route-db-icon','policy-command-icon','query-admin-icon','query-api-icon','agent-admin-icon','llm-icon','redirect-icon','agent-state-icon','changes-kafka-icon','invalidate-icon','tools-icon','actions-icon'}:
        frame=next(b for b in layout['boxes'] if b['id']==parent)
        y=frame['y']+(frame['height']-size)/2
    if badge:
        pad=(badge-size)/2
        theme=theme_key(id)
        surface='brand' if kind in BRANDS else 'function'
        # Fine inset keyline and soft top light, without changing badge geometry.
        layers['icons'].append(f'<rect x="{f(x-pad+.6)}" y="{f(y-pad+.6)}" width="{f(badge-1.2)}" height="{f(badge-1.2)}" rx="{f(badge*.25-.6)}" fill="url(#badge-{surface}-{theme})" stroke="{PALETTE[theme]['border']}" stroke-width="1.2"/>')
        layers['icons'].append(f'<rect x="{f(x-pad+2)}" y="{f(y-pad+2)}" width="{f(badge-4)}" height="{f(badge-4)}" rx="{f(badge*.25-2)}" fill="none" stroke="#ffffff" stroke-opacity=".8" stroke-width=".85"/>')
    body=icon_fragment(kind,size,id)
    layers['icons'].append(f'<g id="{id}" aria-hidden="true" color="{paint(color,id)}" transform="translate({f(x)} {f(y)}) scale({f(size/48)})">{body}</g>')
    pad=max(0,(badge-size)/2)
    layout['icons'].append(dict(id=id,kind=kind,x=x-pad,y=y-pad,width=size+2*pad,height=size+2*pad,parent=parent))
def edge(id,pts,color='blue',dashed=False,source=None,target=None,meaning='',arrow=True):
    pts=[(x,spread(y)) for x,y in pts]
    if id in {'follow-location','query-api-call','invalidate-change'}:
        frame=next(b for b in layout['boxes'] if b['id']==source)
        center=frame['y']+frame['height']/2
        pts=[(x,center) for x,y in pts]
    d='M '+' L '.join(f'{x} {y}' for x,y in pts)
    key=theme_key(id)
    mark=f' marker-end="url(#arrow-{key})"' if arrow else ''
    dash=' stroke-dasharray="9 8"' if dashed else ''
    layers['edges'].append(f'<path id="{id}" d="{d}" fill="none" stroke="{PALETTE[key]['accent']}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"{dash}{mark}/>')
    layout['edges'].append(dict(id=id,points=pts,source=source,target=target,meaning=meaning,dashed=dashed))
def label(id,s,x,y,color='muted',size=24,anchor='middle'):
    text(id,s,x,y,size,color,anchor=anchor)
def rule(x1,y,x2,color='#e4ebf2',layer='parents'):
    y=spread(y)
    if SPACING_ZONE: color=PALETTE[theme_key()]['border']
    layers[layer].append(f'<path d="M{x1} {y}H{x2}" stroke="{color}" stroke-width="1.4"/>')
def panel(id,num,title,sub,x,y,w,h,color):
    rect(id,x,y,w,h,stroke='#dbe5ee',radius=28,layer='panels')
    # A shallow header wash carries classification; the service field stays white.
    top=spread(y)+1.5; left=x+1.5; right=x+w-1.5; bottom=spread(y)+120
    band=f'M{x+28} {top} H{x+w-28} Q{right} {top} {right} {top+26.5} V{bottom} H{left} V{top+26.5} Q{left} {top} {x+28} {top}Z'
    layers['panels'].append(f'<path d="{band}" fill="{PALETTE[theme_key(id)]['header']}"/>')
    layers['panels'].append(f'<rect x="{x+28}" y="{spread(y+28)}" width="56" height="56" rx="17" fill="{PALETTE[theme_key(id)]['heading']}"/>')
    text(id+'-num',num,x+56,y+66,28,color,True,'middle',id)
    text(id+'-title',title,x+104,y+66,40,'ink',True,parent=id)
    text(id+'-sub',sub,x+104,y+109,25,'muted',parent=id)
def tile(id,x,y,w,h,title,sub,kind,color='blue',parent=None,stack=False,size=30):
    rect(id,x,y,w,h,parent=parent,shadow=True)
    if stack:
        icon(id+'-icon',kind,x+w/2-27,y+(22 if SPACING_ZONE=='redirect' else 13),54,color,id,66)
        text(id+'-title',title,x+w/2,y+h-(53 if SPACING_ZONE=='redirect' else 43),size,'ink',True,'middle',id)
        if sub: text(id+'-sub',sub,x+w/2,y+h-(18 if SPACING_ZONE=='redirect' else 10),24,'muted',anchor='middle',parent=id)
    else:
        icon(id+'-icon',kind,x+27,y+h/2-28,56,color,id,68)
        text(id+'-title',title,x+108,y+h/2-7,size,'ink',True,parent=id)
        if sub: text(id+'-sub',sub,x+108,y+h/2+26,24,'muted',parent=id)
def entry(id,cx,y,title,sub,kind,parent):
    group(id,cx-132,y,264,146,parent)
    icon(id+'-icon',kind,cx-26,y+12,52,'blue',id,64)
    text(id+'-title',title,cx,y+101,29,'ink',True,'middle',id)
    text(id+'-sub',sub,cx,y+132,24,'muted',anchor='middle',parent=id)


# Move complete semantic regions without scaling typography or changing geometry.
def begin_shift(dy):
    global shift_start
    shift_start=(dy,{k:len(v) for k,v in layers.items()},{k:len(layout[k]) for k in ['boxes','texts','icons','edges']})
def end_shift():
    dy,ls,ms=shift_start
    for k in layers:
        section=layers[k][ls[k]:]
        if section: layers[k][ls[k]:]=[f'<g transform="translate(0 {dy})">'+''.join(section)+'</g>']
    for k in ['boxes','icons']:
        for v in layout[k][ms[k]:]: v['y']+=dy
    for t in layout['texts'][ms['texts']:]:
        t['y']+=dy; t['bbox']['y']+=dy
    for e in layout['edges'][ms['edges']:]: e['points']=[(x,y+dy) for x,y in e['points']]

# Layout refresh: preserved architecture, denser landscape composition.
# DESIGN_VARIANCE=4, MOTION_INTENSITY=0 (static artifact), VISUAL_DENSITY=5.
# Three reading bands: public redirect; analytics; command + agent.
layers['background'].append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
icon('brand-symbol','link',66,55,68,'blue',badge=92)
text('title','ShortLink 全局架构',176,102,62,'ink',True)
text('subtitle','真实跳转 · 布隆防穿透 · Leaf 发号 · 异步统计与 Agent',178,149,28,'muted')
rule(64,175,2736,'#dce5ee','background')

SPACING_ZONE='redirect'
# 01: separate public exchange, in-process control flow and background synchronization.
panel('zone-01','01','接入与真实跳转','公开入口、本地 Bloom 防穿透与浏览器 302 跳转',48,198,2704,TOP_H,'blue')
text('stage-request','请求短链',90,347,26,'ink',True,parent='zone-01')
text('same-browser','同一浏览器收到 302 后，按 Location 访问目标',1860,347,25,'muted',parent='zone-01')
tile('browser-in',90,372,250,166,'Browser','短链访客','browser',parent='zone-01',stack=True)
tile('apisix',490,372,355,166,'APISIX','TLS · 可信头重写 · 限流','apisix',parent='zone-01',stack=True)
rect('redirect',995,372,590,166,parent='zone-01',shadow=True,stroke='#adc7ee')
icon('redirect-icon','link',1028,408,70,'blue','redirect',88)
text('redirect-title','Redirect',1140,429,36,'ink',True,parent='redirect')
text('redirect-l1','路由解析 · 策略执行',1140,470,26,'muted',parent='redirect')
text('redirect-policy','内部流程见下方展开',1140,507,26,'muted',parent='redirect')
tile('browser-next',1860,372,330,166,'Browser','收到 302 后','browser',parent='zone-01',stack=True)
tile('target',2425,372,280,166,'目标网站','浏览器访问','globe',parent='zone-01',stack=True)
edge('request',[(340,455),(490,455)],source='browser-in',target='apisix',meaning='请求短链')
label('request-label','请求短链',415,437)
edge('forward',[(845,455),(995,455)],source='apisix',target='redirect',meaning='转发请求')
label('forward-label','转发请求',920,437)
text('return-path','302 + Location 沿原链路返回：Redirect → APISIX → Browser',90,574,25,'muted',parent='zone-01')
edge('follow-location',[(2190,455),(2425,455)],source='browser-next',target='target',meaning='浏览器随后访问目标网站')
label('follow-location-label','访问目标地址',2307,437)

# Shift the expanded process and background together below the response caption.
begin_shift(26)
rect('redirect-flow',80,578,2640,184,'#edf4ff','none',20,'zone-01','parents')
text('redirect-flow-title','Redirect 进程内 · 条件查询顺序',104,613,27,'ink',True,parent='redirect-flow')
for id,x,w,title,sub,kind in [
    ('l1-step',104,350,'① L1 本地缓存','命中则复用路由','archive'),
    ('bloom',574,390,'② Bloom 预检','Guava · 默认 OFF','shield'),
    ('l2-step',1104,360,'③ 查询 L2','由 Redirect 读取','database'),
    ('origin-step',1604,430,'④ 限额回源','仅 L2 未命中时','database'),
    ('policy-step',2174,522,'⑤ 策略校验','找到路由后统一执行','gear')]:
    tile(id,x,636,w,102,title,sub,kind,parent='redirect-flow',size=28)
for id,x1,x2,src,dst,label_text in [
    ('l1-miss',454,574,'l1-step','bloom','未命中'),
    ('bloom-continue',964,1104,'bloom','l2-step','可能 / 未知'),
    ('l2-miss',1464,1604,'l2-step','origin-step','未命中'),
    ('origin-found',2034,2174,'origin-step','policy-step','找到路由')]:
    edge(id,[(x1,687),(x2,687)],source=src,target=dst,meaning='Redirect 进程内条件流程：'+label_text)
    label(id+'-label',label_text,(x1+x2)/2,668,size=24)
# These are dependencies of Redirect methods, not calls from one data store to another.
tile('redis',1104,820,360,92,'Redis','路由 L2 / 限流','database',parent='zone-01',size=29)
tile('route-db',1604,820,430,92,'业务 MySQL','路由事实 / 受限回源','database',parent='zone-01',size=29)
tile('policy-command',2174,820,522,92,'Command','权威策略 / 状态校验','gear',parent='zone-01',size=29)
for id,cx,src,dst in [('cache-read',1284,'l2-step','redis'),('route-proof',1819,'origin-step','route-db'),('policy-proof',2435,'policy-step','policy-command')]:
    edge(id,[(cx,738),(cx,820)],source=src,target=dst,meaning='Redirect 读取对应存储或权威服务')
text('redirect-hit-note','任一层命中路由后，统一执行策略校验。',104,818,25,'muted',parent='zone-01')
text('redirect-negative-note','可靠阴性重验后 404；不写负缓存、不产生 CLICK。',104,858,25,'muted',parent='zone-01')
text('redirect-unknown-note','租约失效 / UNKNOWN：按原查询链路降级。',104,898,25,'muted',parent='zone-01')
rect('membership-sync-lane',80,954,2640,128,'#edf4ff','none',20,'zone-01','parents')
for id,x,w,title,sub,kind in [('membership-db',104,650,'业务 MySQL · 地址登记','持久成员 / 租约控制','database'),('membership-sync',978,780,'Redirect 后台同步','主库分页补齐 · 追齐当前版本才领租','gear'),('membership-view',2160,530,'本地 Bloom 视图','同一视图 · 最长 1s 租约','shield')]:
    group(id,x,970,w,96,'membership-sync-lane')
    icon(id+'-icon',kind,x+14,988,50,'blue',id,62)
    text(id+'-title',title,x+100,1010,30,'ink',True,parent=id)
    text(id+'-sub',sub,x+100,1050,24,'muted',parent=id)
edge('membership-authority',[(754,1018),(978,1018)],source='membership-db',target='membership-sync',meaning='返回持久登记；校验完整版本后授予短期租约')
edge('membership-publish-view',[(1758,1018),(2160,1018)],source='membership-sync',target='membership-view',meaning='原子发布本地完整视图与有效租约')
label('membership-publish-view-label','原子发布视图',1959,1000,size=24)
text('membership-background-note','后台同步独立于每次跳转；Kafka 仅唤醒同步。正常 302 不等待统计落库，也不调用 Leaf。',104,1138,25,'muted',parent='zone-01')

end_shift()
SPACING_ZONE='command'
begin_shift(MIDDLE_Y-710)
# 03: one downward publication sequence; each database role aligns with its caller.
panel('zone-02','03','管理与业务写入','单条 / 批量复用 Leaf，登记完整地址后再发布路由',48,710,1640,MIDDLE_H,'teal')
for a in [('management',244,'管理调用方','运营 / 开发','user'),('write-apisix',654,'APISIX','统一接入 / 限流','apisix'),('admin',1064,'Admin','会话 / 授权 / 预算','browser'),('command-entry',1474,'Command','单条 / 批量创建','gear')]:
    entry(a[0],a[1],849,*a[2:],'zone-02')
for id,x1,x2,src,dst in [('management-entry',280,618,'management','write-apisix'),('admin-entry',690,1028,'write-apisix','admin'),('admin-command',1100,1438,'admin','command-entry')]:
    edge(id,[(x1,887),(x2,887)],'teal',source=src,target=dst,meaning='管理业务请求')
rect('command',80,1022,900,944,'#f7fcfa','#b8dcd5',22,'zone-02','parents')
text('command-flow-title','Command · 进程内创建流程',104,1065,29,'ink',True,parent='command')
group('business-db',1180,1022,476,944,'zone-02')
icon('business-db-icon','database',1200,1031,42,'teal','business-db')
text('business-db-title','业务 MySQL · 同一主库',1260,1065,28,'ink',True,parent='business-db')

rect('leaf',104,1100,852,235,'white','#a8d8c8',18,'command',shadow=True)
icon('leaf-icon','gear',129,1125,52,'teal','leaf',64)
text('leaf-title','美团 Leaf Segment',211,1140,34,'ink',True,parent='leaf')
text('leaf-origin','受控源码适配 · id-generator（进程内）',211,1180,26,'muted',parent='leaf')
text('leaf-entry','单条 / 批量：reserveRanges(n)',128,1220,26,'ink',parent='leaf')
rect('current',128,1240,368,46,'#eaf8f4','none',10,'leaf')
rect('next',520,1240,412,46,'#eaf8f4','none',10,'leaf')
text('current-text','Current 当前号段',312,1272,26,'teal',True,'middle','current')
text('next-text','Next 异步预取',726,1272,26,'teal',True,'middle','next')
text('leaf-note','内存取号 · 有界异步预取 · 双号段切换',128,1319,25,'muted',parent='leaf')
rect('allocation',1200,1100,432,235,'#eaf8f4','#b4dcd0',18,'business-db')
text('allocation-name','t_id_alloc',1416,1149,31,'teal',True,'middle','allocation')
text('allocation-watermark','全局号段高水位',1416,1201,26,'ink',anchor='middle',parent='allocation')
text('allocation-lock','锁行预留号段',1416,1253,25,'muted',anchor='middle',parent='allocation')
text('allocation-tx','独立事务提交',1416,1305,25,'muted',anchor='middle',parent='allocation')
edge('reserve-range',[(956,1212),(1200,1212)],'teal',source='leaf',target='allocation',meaning='独立事务预留号段')
label('reserve-range-label','申请 / 预留',1078,1193,'teal',25)
edge('committed-range',[(1200,1284),(956,1284)],'teal',source='allocation',target='leaf',meaning='返回已提交 ID 区间')
label('committed-range-label','已提交 ID 区间',1078,1323,'teal',25)

rect('codec',104,1375,852,110,'white','#d4e4de',18,'command')
icon('codec-icon','code',129,1402,50,'blue','codec',62)
text('codec-title','ShortCodeCodec · 项目编码器',211,1417,30,'ink',True,parent='codec')
text('codec-note','52 位 / 八轮 Feistel → 固定 9 位 Base62',211,1460,26,'muted',parent='codec')
for id,y,title,sub,kind in [
    ('registration-step',1525,'地址登记','规范化地址 · 登记成功后再推进','database'),
    ('publication-barrier',1677,'异步发布屏障（每发布批次）','≥ 1.25s · 不持有事务或行锁','shield'),
    ('route-publication',1829,'路由发布','提交前核验登记与维护代次','outbox')]:
    tile(id,104,y,852,112,title,sub,kind,'teal','command',size=30)
for id,y1,y2,src,dst,note in [
    ('id-to-codec',1335,1375,'leaf','codec','唯一 ID 区间'),
    ('codec-to-register',1485,1525,'codec','registration-step','规范化短链地址'),
    ('register-barrier',1637,1677,'registration-step','publication-barrier','登记独立提交后'),
    ('barrier-publication',1789,1829,'publication-barrier','route-publication','屏障完成后')]:
    edge(id,[(530,y1),(530,y2)],'teal',source=src,target=dst,meaning='Command 进程内顺序：'+note)
    label(id+'-label',note,676,y1+29,'teal',24)

for id,y,title,sub in [('registration-record',1525,'成员登记 + Outbox','独立事务提交'),('facts-outbox',1829,'业务事实 + Outbox','同一业务事务提交')]:
    rect(id,1200,y,432,112,'white','#d9e4ed',18,'business-db')
    text(id+'-title',title,1416,y+44,29,'ink',True,'middle',id)
    text(id+'-sub',sub,1416,y+88,25,'muted',anchor='middle',parent=id)
edge('registration-write',[(956,1581),(1200,1581)],'teal',source='registration-step',target='registration-record',meaning='登记与登记 Outbox 在独立事务持久化')
label('registration-write-label','登记写入',1078,1562,'teal',25)
edge('business-commit',[(956,1885),(1200,1885)],'teal',source='route-publication',target='facts-outbox',meaning='核验登记后，业务事实与 Outbox 同事务提交')
label('business-commit-label','核验并提交',1078,1866,'teal',25)
text('separate-transactions','登记与路由分别提交',1416,1698,25,'muted',anchor='middle',parent='business-db')
text('reserved-retry','批量重试保留原 ID',1416,1740,25,'muted',anchor='middle',parent='business-db')

# Reference committed Outbox by its local heading instead of perimeter-sized return wires.
text('outbox-fanout-title','Outbox 异步通知',104,2020,29,'ink',True,parent='zone-02')
text('outbox-source','读取同库已提交意图 · 两类 Topic 复用同一 Kafka 集群',540,2020,24,'muted',parent='zone-02')
rect('outbox-publisher',104,2070,355,212,'#fff6ec','#ffd1ae',18,'zone-02')
icon('outbox-publisher-icon','outbox',253,2096,56,'teal','outbox-publisher',68)
text('outbox-publisher-title','Outbox 发布器',281.5,2200,30,'ink',True,'middle','outbox-publisher')
text('outbox-publisher-sub','领取已提交意图',281.5,2244,25,'muted',anchor='middle',parent='outbox-publisher')
for id,x,y,w,title,sub,kind in [
    ('changes-kafka',720,2060,420,'Kafka 路由变更','路由 / 策略失效提示','kafka'),
    ('membership-kafka',720,2190,420,'Kafka 登记提示','独立 membership Topic','kafka'),
    ('invalidate',1270,2060,362,'Redirect','使对应缓存失效','link'),
    ('sync-wakeup',1270,2190,362,'Redirect','只唤醒后台同步','gear')]:
    tile(id,x,y,w,92,title,sub,kind,'teal','zone-02',size=29)
edge('outbox-fanout',[(459,2176),(574,2176)],'teal',True,'outbox-publisher',None,'已提交 Outbox 分类发布',arrow=False)
edge('publish-change',[(574,2176),(574,2106),(720,2106)],'teal',True,'outbox-publisher','changes-kafka','异步路由或策略变更提示')
edge('publish-membership-hint',[(574,2176),(574,2236),(720,2236)],'teal',True,'outbox-publisher','membership-kafka','异步地址登记提示')
edge('invalidate-change',[(1140,2106),(1270,2106)],'teal',True,'changes-kafka','invalidate','异步缓存失效提示')
edge('wake-membership-sync',[(1140,2236),(1270,2236)],'teal',True,'membership-kafka','sync-wakeup','Kafka 仅唤醒主库补读，不授予否定权限')
text('write-note','登记完整性与否定租约由主库校验；Kafka 不承担权限授予。',104,2324,25,'muted',parent='zone-02')

end_shift()
SPACING_ZONE='agent'
begin_shift(MIDDLE_Y-710)
# 03: align the agent with the write-side levels; keep action and query bands adjacent.
panel('zone-03','04','Agent 分析与风控','基于 Spring AI Alibaba Graph',1716,710,1036,MIDDLE_H-EXTRA['agent'],'purple')
tile('agent-admin',1748,841,384,106,'Admin','会话入口','browser','purple','zone-03')
tile('llm',2336,841,384,106,'LLM','解释与归纳','brain','purple','zone-03')
rect('agent-service',1748,1000,972,352,'#faf8fe','#d6c9e9',22,'zone-03','parents')
icon('agent-service-icon','robot',1776,1020,60,'purple','agent-service',74)
text('agent-service-title','Agent Service',1874,1039,36,'ink',True,parent='agent-service')
text('agent-service-sub','独立服务 · 分析编排与执行约束',1874,1077,26,'muted',parent='agent-service')
edge('agent-session',[(1940,947),(1940,1000)],'purple',source='agent-admin',target='agent-service',meaning='会话请求')
label('agent-session-label','会话请求',2040,980,'purple',25)
edge('llm-request',[(2440,1000),(2440,947)],'purple',source='agent-service',target='llm',meaning='推理请求')
label('llm-request-label','推理请求',2366,980,'purple',25)
edge('llm-response',[(2640,947),(2640,1000)],'purple',source='llm',target='agent-service',meaning='解释结果')
label('llm-response-label','解释结果',2558,980,'purple',25)
for id,x,w,kind,title1,title2,sub in [('campaign',1776,440,'chart','Campaign','Analysis','投放分析 Graph'),('security',2240,452,'shield','Security','Risk','安全风控 Graph')]:
    rect(id,x,1103,w,141,'white','none',16,'agent-service')
    icon(id+'-icon',kind,x+25,1129,60,'purple',id)
    text(id+'-a',title1,x+113,1138,29,'ink',True,parent=id)
    text(id+'-b',title2,x+113,1175,29,'ink',True,parent=id)
    text(id+'-sub',sub,x+113,1217,25,'muted',parent=id)
rule(1776,1262,2692,'#e5dcf1')
text('agent-harness','Harness · Tools · Checkpoint · Trace',1776,1296,27,'ink',parent='agent-service')
text('agent-rules','确定性规则 · 人工审核 · 受控动作',1776,1333,26,'muted',parent='agent-service')
rect('agent-state',1748,1407,972,85,'white','#dce5ee',18,'zone-03')
icon('agent-state-icon','database',1778,1426,48,'blue','agent-state',60)
text('agent-state-title','Agent MySQL',1866,1458,31,'ink',True,parent='agent-state')
text('agent-state-sub','画像 / 审计 / 状态',2350,1458,26,'muted',parent='agent-state')
edge('agent-persistence',[(2140,1352),(2140,1407)],'purple',source='agent-service',target='agent-state',meaning='状态持久化 / 查询')
label('agent-persistence-label','状态读写',2240,1386,'purple',25)
for id,y,color,kind,title,sub in [('tools',1506,'purple','chart','统计 Tools → Admin → Analytics API','复用统计快照 / 查询 Job；沿用 Admin 授权'),('actions',1586,'orange','shield','审核 / 策略动作 → Admin → Command','人工审核 · 受控动作 · Command 策略仲裁')]:
    rect(id,1748,y,972,75,TINT[color],'none',14,'zone-03')
    icon(id+'-icon',kind,1771,y+17,42,color,id)
    text(id+'-title',title,1842,y+33,27,color,True,parent=id)
    text(id+'-sub',sub,1842,y+64,24,'muted',parent=id)

rule(1776,1726,2692,'#e5dcf1')
text('agent-bloom-boundary-title','与防穿透链路的边界',1776,1790,30,'ink',True,parent='zone-03')
text('agent-bloom-boundary-a','Bloom 只服务于 Redirect 路由预检。',1776,1850,26,'muted',parent='zone-03')
text('agent-bloom-boundary-b','统计 Tools 继续复用既有 Analytics API。',1776,1903,26,'muted',parent='zone-03')
text('agent-bloom-boundary-c','拒绝请求不计为成功 CLICK / PV。',1776,1956,26,'muted',parent='zone-03')
text('agent-bloom-boundary-d','不在 Agent 服务中维护独立 Bloom。',1776,2009,26,'muted',parent='zone-03')

end_shift()
SPACING_ZONE='analytics'
begin_shift(DATA_Y-1700)
# 04: one online row, two clearly grouped maintenance/query areas below.
panel('zone-04','02','异步统计与数据服务','在线聚合在上，归档补算与统一查询在下；后台与 Agent Tools 共用统计服务',48,1700,2704,726,'blue')
rect('pipeline',80,1837,2640,211,'#f6f9fd','none',20,'zone-04','parents')
group('event-sources',98,1850,377,165,'pipeline')
icon('edge-event-icon','apisix',110,1859,38,'blue','event-sources')
text('edge-event','APISIX · EDGE 请求结果',165,1890,25,'ink',True,parent='event-sources')
icon('redirect-event-icon','link',110,1921,38,'blue','event-sources')
text('redirect-event','Redirect · 点击',165,1952,25,'ink',True,parent='event-sources')
text('business-event','BUSINESS 请求结果',165,1993,25,'muted',parent='event-sources')
for id,cx,w,kind,title,subs,bordered in [('raw-kafka',665,250,'kafka','原始 Kafka',['原始事件主题'],True),('flink',1070,260,'flink','Flink',['校验 / 明细分支','去重聚合'],False),('derived-kafka',1490,286,'kafka','派生 Kafka',['明细 / 聚合主题'],True),('connect',1940,380,'clickhouse','ClickHouse',['Kafka Connect','官方连接器 · 至少一次'],False),('clickhouse',2470,456,'clickhouse','ClickHouse',['明细 / 聚合 / 版本化结果'],True)]:
    x=cx-w/2
    if bordered: rect(id,x,1850,w,157,'white','#e0e8f1',17,'pipeline')
    else: group(id,x,1850,w,185,'pipeline')
    icon(id+'-icon',kind,cx-27,1862,54,'blue',id,68)
    text(id+'-title',title,cx,1955,31,'ink',True,'middle',id)
    for n,s in enumerate(subs): text(id+f'-sub-{n}',s,cx,1989+n*29,25,'muted',anchor='middle',parent=id)
edge('events-ingest',[(475,1899),(540,1899)],'blue',True,'event-sources','raw-kafka','EDGE / 点击 / BUSINESS 原始事件')
edge('raw-consume',[(790,1899),(1036,1899)],'blue',True,'raw-kafka','flink','原始事件消费')
edge('derive',[(1104,1899),(1347,1899)],'blue',True,'flink','derived-kafka','校验 / 明细 / 去重聚合结果')
edge('connect-consume',[(1633,1899),(1906,1899)],'blue',True,'derived-kafka','connect','派生事件消费')
edge('connect-write',[(1974,1899),(2242,1899)],source='connect',target='clickhouse',meaning='至少一次写入')
text('kafka-same-system','原始 / 派生主题同属一个 Kafka 系统',98,2080,25,'muted',parent='zone-04')

# These backgrounds group responsibilities, without adding false deployment boundaries.
rect('maintenance-lane',80,2144,1280,267,'#f7fafc','none',18,'zone-04','parents')
text('maintenance-title','归档与补算',104,2181,28,'ink',True,parent='maintenance-lane')
tile('archive-store',104,2210,340,153,'不可变对象存储','原始归档 / 补算输入','database',parent='maintenance-lane',stack=True,size=29)
rect('worker',662,2210,669,153,'#f8fbff','#c4d7eb',20,'maintenance-lane',shadow=True)
icon('worker-icon','gear',686,2235,56,'blue','worker',70)
text('worker-title','Analytics Worker',776,2254,33,'ink',True,parent='worker')
text('worker-tasks','归档 · 覆盖证明 · 补算 · 恢复',776,2295,26,'muted',parent='worker')
text('worker-publish','任务执行与版本发布',776,2336,25,'muted',parent='worker')
edge('independent-archive',[(665,2007),(665,2115),(880,2115),(880,2210)],'blue',True,'raw-kafka','worker','原始事件独立订阅')
label('independent-archive-label','独立订阅',978,2178,'blue',25)
edge('archive-write',[(662,2247),(444,2247)],source='worker',target='archive-store',meaning='归档写入')
label('archive-write-label','归档写入',553,2230,'blue',25)
edge('archive-input',[(444,2334),(662,2334)],source='archive-store',target='worker',meaning='归档数据 / 补算输入')
label('archive-input-label-a','归档数据',553,2290,'blue',25)
label('archive-input-label-b','补算输入',553,2320,'blue',25)
edge('rebuild-write',[(1240,2210),(1240,2106),(2470,2106),(2470,2007)],source='worker',target='clickhouse',meaning='写入版本化补算结果')
label('rebuild-write-label','写入版本化补算结果',1810,2087,'blue',25)

rect('query-lane',1404,2144,1316,267,'#f9f8fd','none',18,'zone-04','parents')
text('analytics-shared','统一统计查询',1428,2181,28,'ink',True,parent='query-lane')
text('analytics-query-boundary','查询不进入逐点击处理链路',1804,2181,25,'muted',parent='query-lane')
tile('query-admin',1428,2210,350,93,'Admin','后台 / 统计 Tools','browser',parent='query-lane')
tile('query-api',1910,2210,453,93,'Analytics API','快照 / 持久化查询 Job','chart','purple','query-lane',size=31)
edge('query-api-call',[(1778,2256),(1910,2256)],source='query-admin',target='query-api',meaning='统计 / 查询 Job 请求')
label('query-api-call-label','查询',1844,2235,'blue',25)
edge('query-clickhouse',[(2363,2256),(2734,2256),(2734,1942),(2698,1942)],source='query-api',target='clickhouse',meaning='统计查询')
label('query-clickhouse-label','统计查询',2524,2235,'blue',25)
rect('statistics-control',1790,2330,906,65,'white','#dce5ee',12,'query-lane')
icon('statistics-control-icon','database',1806,2348,32,'blue','statistics-control')
text('statistics-control-title','统计控制 MySQL',1857,2372,26,'ink',True,parent='statistics-control')
text('statistics-control-sub','查询任务 / 租约 / 构建清单',2240,2372,25,'muted',parent='statistics-control')
edge('query-job-state',[(2135,2303),(2135,2330)],source='query-api',target='statistics-control',meaning='查询任务状态读写')
label('query-job-state-label','查询任务状态',2435,2322,'blue',24)
edge('worker-control',[(1331,2320),(1378,2320),(1378,2352),(1790,2352)],source='worker',target='statistics-control',meaning='任务 / 租约 / 构建清单读写')
label('worker-control-label','任务 / 租约 / 构建清单',1578,2341,'blue',24)

end_shift()
SPACING_ZONE=None
begin_shift(FOOTER_Y-2442)
edge('legend-solid',[(80,2471),(150,2471)],arrow=True)
text('legend-solid-text','实线：调用 / 存储访问 / 框内流程',173,2480,25,'muted')
edge('legend-dashed',[(748,2471),(818,2471)],dashed=True)
text('legend-dashed-text','虚线：异步事件 / 变更通知',841,2480,25,'muted')
text('embedded-modules','进程内公共模块：event-contract · id-generator · risk-core · route-membership',80,2530,25,'muted')
text('logical-roles','按链路分区展示；同名组件表示同一服务，存储节点表示逻辑职责。双向交互分别标明各方向含义。',80,2574,25,'muted')
end_shift()
layout['regions']=[dict(id='01-redirect',x=48,y=198,width=2704,height=TOP_H),dict(id='02-analytics',x=48,y=DATA_Y,width=2704,height=DATA_H),dict(id='03-command',x=48,y=MIDDLE_Y,width=1640,height=MIDDLE_H),dict(id='04-agent',x=1716,y=MIDDLE_Y,width=1036,height=MIDDLE_H),dict(id='05-title',x=48,y=30,width=2704,height=150),dict(id='06-footer',x=48,y=FOOTER_Y,width=2704,height=160)]

def inside(a,b,pad=0):
    return b['x']>=a['x']+pad and b['y']>=a['y']+pad and b['x']+b['width']<=a['x']+a['width']-pad and b['y']+b['height']<=a['y']+a['height']-pad
def overlap(a,b):
    return min(a['x']+a['width'],b['x']+b['width'])>max(a['x'],b['x'])+.1 and min(a['y']+a['height'],b['y']+b['height'])>max(a['y'],b['y'])+.1
boxes={b['id']:b for b in layout['boxes']}; problems=[]
for b in layout['boxes']:
    if b['parent'] and not inside(boxes[b['parent']],b,6): problems.append(['box-parent',b['id'],b['parent']])
for t in layout['texts']:
    if t['parent'] and not inside(boxes[t['parent']],t['bbox'],6): problems.append(['text-parent',t['id'],t['parent']])
for a,b in itertools.combinations(layout['texts'],2):
    if overlap(a['bbox'],b['bbox']): problems.append(['text-text',a['id'],b['id']])
for i in layout['icons']:
    if i['parent'] and not inside(boxes[i['parent']],i,5): problems.append(['icon-parent',i['id'],i['parent']])
    for t in layout['texts']:
        if overlap(i,t['bbox']): problems.append(['icon-text',i['id'],t['id']])
for a,b in itertools.combinations(layout['boxes'],2):
    if a['parent']==b['parent'] and overlap(a,b): problems.append(['box-box',a['id'],b['id']])
for e in layout['edges']:
    for (x1,y1),(x2,y2) in zip(e['points'],e['points'][1:]):
        bb=dict(x=min(x1,x2)-1.3,y=min(y1,y2)-1.3,width=abs(x2-x1)+2.6,height=abs(y2-y1)+2.6)
        for t in layout['texts']:
            if overlap(bb,t['bbox']): problems.append(['edge-text',e['id'],t['id']])
        for i in layout['icons']:
            # A connector may touch its icon badge port; only crossings into the badge count.
            inner=dict(x=i['x']+2,y=i['y']+2,width=i['width']-4,height=i['height']-4)
            if overlap(bb,inner): problems.append(['edge-icon',e['id'],i['id']])
validation=dict(textCount=len(layout['texts']),uniqueCharacters=len(set(''.join(t['text'] for t in layout['texts']))),minimumFontSize=min(t['fontSize'] for t in layout['texts']),missingGlyphs=0,iconCount=len(layout['icons']),outputPixels=[W*2,H*2],issues=problems)
layout['validation']=validation
definitions=['<filter id="card-shadow" x="-20%" y="-30%" width="140%" height="160%"><feDropShadow dx="0" dy="5" stdDeviation="7" flood-color="#35424a" flood-opacity="0.07"/></filter>']
for key in PALETTE:
    definitions.append(f'<marker id="arrow-{key}" markerWidth="11" markerHeight="9" refX="9.8" refY="4.5" orient="auto" markerUnits="userSpaceOnUse"><path d="M0 0L10 4.5L0 9Z" fill="{PALETTE[key]['accent']}"/></marker>')
    p=PALETTE[key]
    definitions.append(f'<linearGradient id="badge-function-{key}" x1="0" y1="0" x2="0" y2="1"><stop stop-color="{p["soft"]}"/><stop offset="1" stop-color="{p["badge"]}"/></linearGradient>')
    definitions.append(f'<linearGradient id="badge-brand-{key}" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#ffffff"/><stop offset="1" stop-color="{p["soft"]}"/></linearGradient>')
definitions += [f'<path id="{id}" d="{d}"/>' for id,d in defs.values() if d]
icon_license=(Path(__file__).parent/'LICENSE.phosphor.txt').read_text(encoding='utf-8')
svg=f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{W*2}" height="{H*2}" viewBox="0 0 {W} {H}" role="img" aria-labelledby="diagram-title diagram-desc">
<title id="diagram-title">ShortLink 全局架构</title>
<desc id="diagram-desc">真实跳转、本地布隆过滤与持久登记租约、Leaf 号段发号、Agent 分析与风控及异步统计。文字使用真实字体轮廓，图标为矢量形状。Phosphor Icons 许可和来源见同目录 architecture-icons-NOTICE.md。</desc>
<metadata>{html.escape(json.dumps(validation,ensure_ascii=False))}</metadata>
<metadata id="phosphor-icons-license">{html.escape(icon_license)}</metadata>
<defs>{''.join(definitions)}</defs>
{''.join(''.join(layers[k]) for k in layers)}
</svg>'''
svg='\n'.join(line.rstrip() for line in svg.splitlines())+'\n'
(ROOT/'doc/images/shortlink-global-architecture-leaf-hd.svg').write_text(svg,encoding='utf-8')
(WORK/'layout.json').write_text(json.dumps(layout,ensure_ascii=False,indent=2),encoding='utf-8')
(WORK/'palette.json').write_text(json.dumps(PALETTE,indent=2),encoding='utf-8')
(WORK/'validation.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(validation,ensure_ascii=False,indent=2))
sys.exit(2 if problems else 0)
