import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const base = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(base, '../../..');
const vendor = new Map([
  ['/vendor/vue.js', 'vue/dist/vue.global.prod.js'],
  ['/vendor/element.js', 'element-plus/dist/index.full.min.js'],
  ['/vendor/element.css', 'element-plus/dist/index.css'],
]);
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png'};
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    const name = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname.slice(1));
    const file = vendor.has(url.pathname) ? path.join(repo, 'frontend/console-vue/node_modules', vendor.get(url.pathname)) : path.resolve(base, name);
    if (!vendor.has(url.pathname) && !file.startsWith(base + path.sep)) { res.writeHead(403).end(); return; }
    res.writeHead(200, {'Content-Type': types[path.extname(file)] || 'text/plain; charset=utf-8','Cache-Control':'no-store'});
    res.end(await fs.readFile(file));
  } catch { res.writeHead(404).end('Not found'); }
});
server.listen(5188, '127.0.0.1', () => console.log('ShortLink design samples: http://127.0.0.1:5188'));
