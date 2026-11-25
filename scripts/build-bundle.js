const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const cp = require('child_process');
const archiver = require('archiver');

const root = path.resolve(__dirname, '..');
const www = path.join(root, 'www');
const dist = path.join(root, 'dist');
const versionFile = path.join(root, 'version.json');

let cur = { version: '1.0.0' };
try { cur = JSON.parse(fs.readFileSync(versionFile, 'utf8')); } catch (e) {}
const version = process.argv[2] || cur.version;
cur.version = version;
fs.writeFileSync(versionFile, JSON.stringify(cur, null, 2) + '\n');

['index.html', 'icons-data.js', 'qrcode.js', 'lan.html'].forEach(function (f) {
  fs.copyFileSync(path.join(root, f), path.join(www, f));
});
cp.execSync(
  'npx esbuild node_modules/@capgo/capacitor-updater/dist/esm/index.js --bundle --format=iife --global-name=CapgoUpdater --outfile=www/updater-bundle.js --platform=browser --log-level=error',
  { cwd: root, stdio: 'inherit' }
);
fs.copyFileSync(path.join(www, 'updater-bundle.js'), path.join(root, 'updater-bundle.js'));

// 局域网共选的原生桥（Capacitor 8 需显式 registerPlugin）
cp.execSync(
  'npx esbuild lan-bridge.src.js --bundle --format=iife --outfile=www/lan-bridge.js --platform=browser --log-level=error',
  { cwd: root, stdio: 'inherit' }
);
fs.copyFileSync(path.join(www, 'lan-bridge.js'), path.join(root, 'lan-bridge.js'));

const htmlPath = path.join(www, 'index.html');
let html = fs.readFileSync(htmlPath, 'utf8');
html = html.replace(/var APP_VERSION='[^']*'/, "var APP_VERSION='" + version + "'");
fs.writeFileSync(htmlPath, html);
// 根目录副本同步版本号，便于浏览器里直接预览当前版本
const rootHtmlPath = path.join(root, 'index.html');
let rootHtml = fs.readFileSync(rootHtmlPath, 'utf8');
rootHtml = rootHtml.replace(/var APP_VERSION='[^']*'/, "var APP_VERSION='" + version + "'");
fs.writeFileSync(rootHtmlPath, rootHtml);

fs.mkdirSync(dist, { recursive: true });
const fileName = 'cooklog-' + version + '.zip';
const zipPath = path.join(dist, fileName);
const out = fs.createWriteStream(zipPath);
const archive = archiver('zip', { zlib: { level: 9 } });

out.on('close', function () {
  const sha = crypto.createHash('sha256').update(fs.readFileSync(zipPath)).digest('hex');
  fs.writeFileSync(path.join(dist, 'update.json'), JSON.stringify({
    version: version,
    file: fileName,
    sha256: sha
  }, null, 2) + '\n');
  console.log('bundle: ' + zipPath);
  console.log('size: ' + fs.statSync(zipPath).size + ' bytes');
  console.log('sha256: ' + sha);
  console.log('update.json written to dist/');
});

archive.on('error', function (err) { throw err; });
archive.pipe(out);
archive.directory(www, false);
archive.finalize();
