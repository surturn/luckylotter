import { chromium } from 'playwright';
import path from 'node:path';

const html = process.argv[2];
const out = process.argv[3];

const browser = await chromium.launch();
const page = await browser.newPage();
await page.goto('file:///' + html.replace(/\\/g, '/'), { waitUntil: 'load' });
await page.emulateMedia({ media: 'print' });
await page.waitForTimeout(1200);
await page.pdf({
  path: out,
  format: 'A4',
  printBackground: true,
  margin: { top: '18mm', bottom: '18mm', left: '16mm', right: '16mm' },
  displayHeaderFooter: true,
  headerTemplate: '<div></div>',
  footerTemplate:
    '<div style="width:100%;font-size:8pt;color:#8a8f99;padding:0 16mm;' +
    'font-family:Segoe UI,sans-serif;display:flex;justify-content:space-between">' +
    '<span>LuckLotter — AI Retention Layer</span>' +
    '<span class="pageNumber"></span></div>',
});
await browser.close();
console.log('wrote', out);
