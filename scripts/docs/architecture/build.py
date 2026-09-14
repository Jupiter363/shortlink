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

TOP_H=1000
DATA_Y=198+TOP_H+30
DATA_H=1170
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
    # Direct coordinates keep sibling section boundaries and node spacing predictable.
    return y

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
# 04: equal outer bounds with Command, filled by verified Graph responsibilities rather than empty height.
panel('zone-03','04','Agent 分析与风控','基于 Spring AI Alibaba Graph',1716,710,1036,MIDDLE_H,'purple')
tile('agent-admin',1748,849,384,146,'Admin','会话入口','browser','purple','zone-03')
tile('llm',2336,849,384,146,'LLM','解释与归纳','brain','purple','zone-03')
rect('agent-service',1748,1100,972,758,'#faf8fe','#d6c9e9',22,'zone-03','parents')
icon('agent-service-icon','robot',1780,1135,60,'purple','agent-service',74)
text('agent-service-title','Agent Service',1876,1155,36,'ink',True,parent='agent-service')
text('agent-service-sub','独立服务 · 分析编排与执行约束',1876,1200,26,'muted',parent='agent-service')
edge('agent-session',[(1940,995),(1940,1100)],'purple',source='agent-admin',target='agent-service',meaning='会话请求')
label('agent-session-label','会话请求',2040,1052,'purple',25)
edge('llm-request',[(2440,1100),(2440,995)],'purple',source='agent-service',target='llm',meaning='推理请求')
label('llm-request-label','推理请求',2366,1052,'purple',25)
edge('llm-response',[(2640,995),(2640,1100)],'purple',source='llm',target='agent-service',meaning='解释结果')
label('llm-response-label','解释结果',2558,1052,'purple',25)
for id,x,w,kind,title,sub,steps in [
    ('campaign',1776,440,'chart','Campaign Analysis','投放分析 Graph',[
        '01  解析分组 / 日期',
        '02  查询统计 / 访问记录',
        '03  计算趋势 / 异常洞察',
        '04  模型归纳 / 结果卡片']),
    ('security',2240,452,'shield','Security Risk','安全风控 Graph',[
        '01  画像加载 / 统计取证',
        '02  确定性风险判定',
        '03  模型解释 / 事件留痕',
        '04  受控处置 / 结果返回'])]:
    rect(id,x,1246,w,380,'white','none',18,'agent-service')
    icon(id+'-icon',kind,x+25,1271,48,'purple',id,60)
    text(id+'-title',title,x+101,1288,27,'ink',True,parent=id)
    text(id+'-sub',sub,x+101,1330,25,'muted',parent=id)
    rule(x+24,1360,x+w-24,'#e5dcf1')
    for n,step in enumerate(steps):
        text(id+f'-step-{n+1}',step,x+28,1410+n*56,25,'muted',parent=id)
rect('agent-harness-frame',1776,1672,916,152,'#f7f0ff','none',16,'agent-service')
text('agent-harness-title','统一运行 Harness',1800,1708,29,'ink',True,parent='agent-harness-frame')
text('agent-harness-context','可信上下文 · 有界工具调用',1800,1750,26,'muted',parent='agent-harness-frame')
text('agent-harness-state','会话协调 · Checkpoint · Trace',1800,1789,26,'muted',parent='agent-harness-frame')
rect('agent-state',1748,1918,972,106,'white','#dce5ee',18,'zone-03')
icon('agent-state-icon','database',1780,1941,60,'blue','agent-state',74)
text('agent-state-title','Agent MySQL',1876,1958,31,'ink',True,parent='agent-state')
text('agent-state-sub','画像 · 事件 · 审核留痕 · Checkpoint',1876,1999,24,'muted',parent='agent-state')
edge('agent-persistence',[(2140,1858),(2140,1918)],'purple',source='agent-service',target='agent-state',meaning='状态、画像、风险事件与 Checkpoint 的持久化及查询')
label('agent-persistence-label','状态读写',2240,1898,'purple',25)
for id,y,color,kind,title,sub in [('tools',2060,'purple','chart','统计 Tools → Admin → Analytics API','统计快照 / 查询 Job · 逐次授权'),('actions',2200,'purple','shield','策略命令 → Admin → Command','证据与授权门禁 · 受控限流 · 命令回执')]:
    rect(id,1748,y,972,106,TINT[color],'none',16,'zone-03')
    icon(id+'-icon',kind,1780,y+28,50,color,id,64)
    text(id+'-title',title,1870,y+42,27,color,True,parent=id)
    text(id+'-sub',sub,1870,y+84,24,'muted',parent=id)

end_shift()
SPACING_ZONE='analytics'
begin_shift(DATA_Y-1700)
# 02: three horizontal data paths share one ClickHouse; control storage sits between its callers.
panel('zone-04','02','异步统计与数据服务','在线处理、归档补算与统一查询分层；后台与 Agent Tools 共用统计服务',48,1700,2704,DATA_H,'blue')
rect('pipeline',80,1840,2190,316,'#ecfcf6','none',20,'zone-04','parents')
text('pipeline-title','在线处理',104,1880,28,'ink',True,parent='pipeline')
text('kafka-same-system','原始 / 派生 Topic 同属一个 Kafka 集群',590,1880,25,'muted',parent='pipeline')
group('event-sources',104,1910,365,210,'pipeline')
icon('edge-event-icon','apisix',116,1940,38,'blue','event-sources')
text('edge-event','APISIX · EDGE 请求结果',172,1968,25,'ink',True,parent='event-sources')
icon('redirect-event-icon','link',116,2020,38,'blue','event-sources')
text('redirect-event','Redirect · 点击',172,2048,25,'ink',True,parent='event-sources')
text('business-event','BUSINESS 请求结果',172,2088,25,'muted',parent='event-sources')
for id,x,w,kind,title,subs in [
    ('raw-kafka',590,320,'kafka','原始 Kafka',['原始事件主题']),
    ('flink',1050,290,'flink','Flink',['校验 / 明细分支','去重聚合']),
    ('derived-kafka',1480,320,'kafka','派生 Kafka',['明细 / 聚合主题']),
    ('connect',1940,290,'clickhouse','Kafka Connect',['ClickHouse 官方连接器','至少一次写入'])]:
    rect(id,x,1910,w,210,'white','#a5e7d8',18,'pipeline',shadow=True)
    icon(id+'-icon',kind,x+w/2-27,1940,54,'blue',id,68)
    text(id+'-title',title,x+w/2,2030,29,'ink',True,'middle',id)
    for n,sub in enumerate(subs):
        text(id+f'-sub-{n}',sub,x+w/2,2070+n*34,24,'muted',anchor='middle',parent=id)
for id,x1,x2,src,dst in [
    ('events-ingest',469,590,'event-sources','raw-kafka'),
    ('raw-consume',910,1050,'raw-kafka','flink'),
    ('derive',1340,1480,'flink','derived-kafka'),
    ('connect-consume',1800,1940,'derived-kafka','connect')]:
    edge(id,[(x1,2015),(x2,2015)],'blue',True,src,dst,'异步原始或派生事件传递')

# One storage boundary, with three ports aligned to the services that access it.
rect('clickhouse',2330,1840,390,986,'#ecfcf6','#a5e7d8',20,'zone-04','parents')
icon('clickhouse-icon','clickhouse',2362,1860,52,'blue','clickhouse',64)
text('clickhouse-title','ClickHouse',2442,1898,31,'ink',True,parent='clickhouse')
text('clickhouse-sub','共享分析存储',2525,1939,25,'muted',anchor='middle',parent='clickhouse')
for id,y,title,sub in [
    ('clickhouse-online',1959,'在线明细 / 聚合','Connect 至少一次写入'),
    ('clickhouse-rebuild',2252,'版本化补算结果','Analytics Worker 直写'),
    ('clickhouse-query',2690,'统计查询','快照 / 历史版本')]:
    rect(id,2354,y,342,112,'white','#a5e7d8',16,'clickhouse')
    text(id+'-title',title,2525,y+43,27,'ink',True,'middle',id)
    text(id+'-sub',sub,2525,y+85,24,'muted',anchor='middle',parent=id)
text('clickhouse-shared-a','明细 · 聚合 · 历史版本',2525,2465,25,'muted',anchor='middle',parent='clickhouse')
text('clickhouse-shared-b','同一套 ClickHouse 存储',2525,2507,24,'muted',anchor='middle',parent='clickhouse')
edge('connect-write',[(2230,2015),(2354,2015)],source='connect',target='clickhouse-online',meaning='官方连接器至少一次写入共享 ClickHouse')

rect('maintenance-lane',80,2168,2190,252,'#ecfcf6','none',20,'zone-04','parents')
text('maintenance-title','归档与补算',104,2200,28,'ink',True,parent='maintenance-lane')
rect('archive-store',104,2220,365,176,'white','#a5e7d8',18,'maintenance-lane',shadow=True)
icon('archive-store-icon','archive',259.5,2245,54,'blue','archive-store',68)
text('archive-store-title','不可变对象存储',286.5,2340,29,'ink',True,'middle','archive-store')
text('archive-store-sub','原始归档 / 补算输入',286.5,2377,24,'muted',anchor='middle',parent='archive-store')
rect('worker',590,2220,1130,176,'white','#a5e7d8',18,'maintenance-lane',shadow=True)
icon('worker-icon','cpu',618,2242,56,'blue','worker',70)
text('worker-title','Analytics Worker',712,2266,33,'ink',True,parent='worker')
text('worker-archive','归档 / 覆盖证明',712,2318,27,'ink',True,parent='worker')
text('worker-archive-sub','原始事件独立订阅',712,2360,25,'muted',parent='worker')
text('worker-rebuild','补算 / 恢复',1220,2318,27,'ink',True,parent='worker')
text('worker-rebuild-sub','归档输入 · 版本发布',1220,2360,25,'muted',parent='worker')
edge('independent-archive',[(750,2120),(750,2220)],'blue',True,'raw-kafka','worker','独立于 Flink 订阅原始事件归档')
label('independent-archive-label','独立订阅原始事件',982,2190,'blue',25)
edge('archive-write',[(590,2273),(469,2273)],source='worker',target='archive-store',meaning='归档写入')
label('archive-write-label','归档写入',529.5,2255,'blue',24)
edge('archive-input',[(469,2355),(590,2355)],source='archive-store',target='worker',meaning='读取归档作为补算输入')
label('archive-input-label','补算读取',529.5,2337,'blue',24)
edge('rebuild-write',[(1720,2308),(2354,2308)],source='worker',target='clickhouse-rebuild',meaning='Worker 直接向共享 ClickHouse 写入版本化补算结果')
label('rebuild-write-label','版本化补算写入',2025,2290,'blue',25)

# Shared control storage is between Worker and API, so both callers get a short direct connection.
rect('control-lane',80,2450,2190,152,'#ecfcf6','none',18,'zone-04','parents')
text('control-lane-title','共用控制面',104,2518,27,'ink',True,parent='control-lane')
text('control-lane-sub','Worker / API',104,2560,24,'muted',parent='control-lane')
tile('statistics-control',590,2470,1130,112,'统计控制 MySQL','查询任务 / 租约 / 构建清单','database',parent='control-lane',size=30)
edge('worker-control',[(1440,2396),(1440,2470)],source='worker',target='statistics-control',meaning='Worker 直接读写任务、租约与构建清单')
label('worker-control-label','任务 / 租约 / 构建清单',1670,2440,'blue',24)

rect('query-lane',80,2632,2190,194,'#ecfcf6','none',18,'zone-04','parents')
text('analytics-shared','统一统计查询',104,2665,28,'ink',True,parent='query-lane')
text('analytics-query-boundary','查询不进入逐点击处理链路',1740,2665,24,'muted',parent='query-lane')
tile('query-admin',104,2690,365,112,'Admin','后台 / 统计 Tools','browser',parent='query-lane',size=30)
tile('query-api',590,2690,1130,112,'Analytics API','快照 / 持久化查询 Job','chart',parent='query-lane',size=31)
edge('query-api-call',[(469,2746),(590,2746)],source='query-admin',target='query-api',meaning='后台与统计 Tools 统一调用 Analytics API')
label('query-api-call-label','查询',529.5,2728,'blue',25)
edge('query-clickhouse',[(1720,2746),(2354,2746)],source='query-api',target='clickhouse-query',meaning='Analytics API 查询同一套 ClickHouse 中的分析结果')
label('query-clickhouse-label','统计查询',2025,2728,'blue',25)
edge('query-job-state',[(850,2690),(850,2582)],source='query-api',target='statistics-control',meaning='Analytics API 直接读写查询任务状态')
label('query-job-state-label','查询任务状态',1000,2640,'blue',24)

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
