// 排查用：打印页面报错、就绪标志与总时长。
import { chromium } from 'playwright-core';
import path from 'node:path';
import { startServer } from './serve.mjs';

const root = path.resolve(import.meta.dirname, '../..');
const server = await startServer(root);
const url = server.url + '/docs/video/index.html?static=1';
const browser = await chromium.launch({ channel: 'msedge', args: ['--hide-scrollbars'] });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
page.on('pageerror', e => console.error('PAGEERROR:\n' + (e.stack || e.message)));
page.on('console', m => console.log('CONSOLE[' + m.type() + ']:', m.text()));
await page.goto(url, { waitUntil: 'load' });
await page.waitForTimeout(2500);
console.log('ready =', await page.evaluate(() => window.__ready));
console.log('total =', await page.evaluate(() => window.__total));
console.log('scenes =', await page.evaluate(() => (window.__scenes || []).join(' ')));
await browser.close();
await server.close();
