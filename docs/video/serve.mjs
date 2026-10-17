// 静态服务器：把仓库根目录挂到 http://127.0.0.1:5311 上。
//
// 为什么不是双击 index.html：动画里嵌着 examples/watch-ui/demo-scene.html 的 iframe，逐帧截图时
// 要驱动它切页（watchplay.go('music')），file:// 下父页访问子帧会被跨源策略拦掉。http 下同源，
// 而且这也正是将来挂到 Pages 上的形态。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.wav': 'audio/wav',
  '.mp4': 'video/mp4',
};

export function startServer(root, port = 5311) {
  const base = path.resolve(root);
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const file = path.join(base, decodeURIComponent(url.pathname));
    if (!file.startsWith(base)) {
      res.writeHead(403).end('forbidden');
      return;
    }
    fs.readFile(file, (error, data) => {
      if (error) {
        res.writeHead(404).end('not found: ' + url.pathname);
        return;
      }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream' });
      res.end(data);
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      resolve({
        url: `http://127.0.0.1:${server.address().port}`,
        close: () => new Promise(done => server.close(done)),
      });
    });
  });
}

// 直接运行时当预览服务用：node serve.mjs
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(import.meta.filename)) {
  const root = path.resolve(import.meta.dirname, '../..');
  const { url } = await startServer(root, Number(process.env.PORT ?? 5311));
  console.log(`WatchPlay 介绍动画：${url}/docs/video/index.html`);
  console.log('Ctrl+C 结束');
}
