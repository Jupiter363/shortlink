import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';

const base=path.dirname(fileURLToPath(import.meta.url));
const repo=path.resolve(base,'../../..');
const require=createRequire(path.join(repo,'frontend/console-vue/package.json'));
const QRCode=require('qrcode');
const vendor=new Map([['/vendor/vue.js','vue/dist/vue.esm-browser.prod.js']]);
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.json':'application/json; charset=utf-8'};
const port=Number(process.env.RELAY_PREVIEW_PORT||5189);
const server=http.createServer(async(req,res)=>{
 try{
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405).end();return;}
  const url=new URL(req.url,'http://127.0.0.1');
  if(url.pathname==='/__preview/qr'){
   const value=url.searchParams.get('text')||'';
   if(value.length>2048||!/^https?:\/\//i.test(value)){res.writeHead(400).end('Invalid QR input');return;}
   const svg=await QRCode.toString(value,{type:'svg',width:256,margin:4,color:{dark:'#182847',light:'#ffffff'}});
   res.writeHead(200,{'Content-Type':'image/svg+xml','Cache-Control':'no-store'}).end(svg);return;
  }
  const name=url.pathname==='/'?'index.html':decodeURIComponent(url.pathname.slice(1));
  const file=vendor.has(url.pathname)?path.join(repo,'frontend/console-vue/node_modules',vendor.get(url.pathname)):path.resolve(base,name);
  if(!vendor.has(url.pathname)&&!file.startsWith(base+path.sep)){res.writeHead(403).end();return;}
  const body=await fs.readFile(file);
  res.writeHead(200,{'Content-Type':types[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'});
  res.end(req.method==='HEAD'?undefined:body);
 }catch(error){res.writeHead(404,{'Content-Type':'text/plain; charset=utf-8'}).end('页面或本地资源不存在');}
});
server.listen(port,'127.0.0.1',()=>console.log(`JUPITER RELAY preview: http://127.0.0.1:${port}`));
