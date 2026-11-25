const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = require('path').resolve(__dirname, '..');
const RES = path.join(ROOT, 'android/app/src/main/res');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT = 9340;
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* ---------- 图形定义（512 坐标系，标记中心 288,274） ---------- */
const G = '#d3693f', G2 = '#a8412a';
const markBody = `
  <g stroke="url(#gr)" stroke-width="19" stroke-linecap="round" fill="none" opacity=".9">
    <path d="M204 242c-19-15-19-29 0-44s19-29 0-44"/>
    <path d="M256 230c-19-15-19-29 0-44s19-29 0-44"/>
    <path d="M308 242c-19-15-19-29 0-44s19-29 0-44"/>
  </g>
  <g fill="url(#gr)">
    <path d="M140 302h232v10c0 55-41 86-94 86h-44c-53 0-94-31-94-86z"/>
    <rect x="126" y="280" width="260" height="28" rx="14"/>
    <rect x="370" y="277" width="80" height="29" rx="14.5" transform="rotate(-8 386 291)"/>
  </g>`;
const defs = `<defs><linearGradient id="gr" x1="0" y1="0" x2=".85" y2="1">
  <stop offset="0" stop-color="${G}"/><stop offset="1" stop-color="${G2}"/></linearGradient></defs>`;

const FG_HTML = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;background:transparent;overflow:hidden}
svg{display:block;width:100vw;height:100vh}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 432 432">${defs}
<g transform="translate(216,216) scale(0.772) translate(-288,-274)">${markBody}</g>
</svg></body></html>`;

const LEGACY_HTML = (round) => `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;overflow:hidden}
svg{display:block;width:100vw;height:100vh}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">${defs}
<defs><linearGradient id="bg" x1="0" y1="0" x2=".7" y2="1">
  <stop offset="0" stop-color="#fffcF6"/><stop offset="1" stop-color="#f2e8da"/></linearGradient>
  ${round ? '<clipPath id="cc"><circle cx="256" cy="256" r="256"/></clipPath>' : ''}</defs>
<g ${round ? 'clip-path="url(#cc)"' : ''}>
  <rect width="512" height="512" fill="url(#bg)"/>
  <g transform="translate(-32,-18)">${markBody}</g>
</g>
</svg></body></html>`;

const SPLASH_HTML = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;background:#fbf6ec;overflow:hidden}
body{display:flex;align-items:center;justify-content:center}
svg{width:38vmin;height:38vmin;display:block}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">${defs}
<g transform="translate(-32,-18)">${markBody}</g>
</svg></body></html>`;

fs.writeFileSync(path.join(ROOT, '_fg.html'), FG_HTML);
fs.writeFileSync(path.join(ROOT, '_lg.html'), LEGACY_HTML(false));
fs.writeFileSync(path.join(ROOT, '_rd.html'), LEGACY_HTML(true));
fs.writeFileSync(path.join(ROOT, '_sp.html'), SPLASH_HTML);

function pngSize(file) {
  const b = fs.readFileSync(file);
  return [b.readUInt32BE(16), b.readUInt32BE(20)];
}

(async () => {
  const proc = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`,
    '--user-data-dir=C:/Users/PC/AppData/Local/Temp/cookicon-' + Date.now(),
    '--no-first-run', '--disable-gpu', '--hide-scrollbars', 'about:blank'], { stdio: 'ignore' });
  let ws, id = 0, pend = {};
  for (let i = 0; i < 40 && !ws; i++) {
    await sleep(250);
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find(t => t.type === 'page'); if (!page) continue;
      ws = new WebSocket(page.webSocketDebuggerUrl);
      await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    } catch (e) {}
  }
  ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend[m.id]) { pend[m.id](m); delete pend[m.id]; } };
  const send = (mm, p = {}) => new Promise(r => { const n = ++id; pend[n] = r; ws.send(JSON.stringify({ id: n, method: mm, params: p })); });
  await send('Page.enable'); await send('Runtime.enable');

  async function capture(htmlFile, w, h, out, transparent) {
    await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
    /* 只靠 omitBackground 在 headless=new 下仍会画白底，必须同时清掉默认背景色 */
    await send('Emulation.setDefaultBackgroundColorOverride',
      transparent ? { color: { r: 0, g: 0, b: 0, a: 0 } } : { color: { r: 255, g: 255, b: 255, a: 1 } });
    await send('Page.navigate', { url: 'file:///' + ROOT.replace(/\\/g, '/') + '/' + htmlFile });
    await sleep(320);
    const r = await send('Page.captureScreenshot', {
      format: 'png', omitBackground: !!transparent,
      clip: { x: 0, y: 0, width: w, height: h, scale: 1 }
    });
    fs.writeFileSync(out, Buffer.from(r.result.data, 'base64'));
    await send('Emulation.setDefaultBackgroundColorOverride', {});
    return pngSize(out);
  }

  const fgSizes = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
  const lgSizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };

  for (const [d, s] of Object.entries(fgSizes)) {
    const out = path.join(RES, 'mipmap-' + d, 'ic_launcher_foreground.png');
    const sz = await capture('_fg.html', s, s, out, true);
    console.log('fg', d, s, '->', sz);
  }
  for (const [d, s] of Object.entries(lgSizes)) {
    const a = await capture('_lg.html', s, s, path.join(RES, 'mipmap-' + d, 'ic_launcher.png'), false);
    const b = await capture('_rd.html', s, s, path.join(RES, 'mipmap-' + d, 'ic_launcher_round.png'), true);
    console.log('legacy', d, s, a, b);
  }

  // 启动图：按现有每张 splash.png 的实际尺寸重新渲染
  const splashes = [];
  (function walk(dir) {
    for (const f of fs.readdirSync(dir)) {
      const p = path.join(dir, f);
      if (fs.statSync(p).isDirectory()) walk(p);
      else if (f === 'splash.png') splashes.push(p);
    }
  })(RES);
  for (const p of splashes) {
    const [w, h] = pngSize(p);
    await capture('_sp.html', w, h, p, false);
    console.log('splash', path.relative(RES, p), w + 'x' + h);
  }

  // 预览：512 圆角方 + 圆形
  await capture('_lg.html', 512, 512, path.join(ROOT, '_icon_final_square.png'), false);
  await capture('_rd.html', 512, 512, path.join(ROOT, '_icon_final_round.png'), true);
  console.log('done');
  ws.close(); proc.kill();
})();
