const cp = require('child_process');
const path = require('path');
const fs = require('fs');

const root = path.resolve(__dirname, '..');
const sdk = path.join(root, 'tools/android-sdk');

// Capacitor 8 需要 Java 21；优先选 tools 下的 jdk21 目录
function findJdk() {
  const tools = path.join(root, 'tools');
  let dirs = [];
  try { dirs = fs.readdirSync(tools); } catch (e) {}
  const jdk21 = dirs.filter(function (d) { return /^jdk-?21/i.test(d) || /^amazon-corretto-21/i.test(d); });
  const jdk17 = dirs.filter(function (d) { return /^jdk-?17/i.test(d); });
  const pick = jdk21[0] || jdk17[0];
  if (!pick) throw new Error('未找到 JDK，请在 tools/ 下放置 jdk21 目录');
  return path.join(tools, pick);
}

const javaHome = findJdk();
console.log('JAVA_HOME: ' + javaHome);

const env = Object.assign({}, process.env, {
  JAVA_HOME: javaHome,
  ANDROID_HOME: sdk,
  ANDROID_SDK_ROOT: sdk
});

function run(cmd, cwd) {
  console.log('> ' + cmd);
  cp.execSync(cmd, { cwd: cwd || root, env: env, stdio: 'inherit', shell: 'cmd.exe' });
}

/* 把 version.json 的版本号写成 Gradle 能读的 versionCode / versionName
 * 1.2.2 → versionCode 10202，保证每次发版单调递增（否则覆盖安装会被判为同版本） */
function writeVersionProps() {
  let ver = '1.0.0';
  try {
    ver = JSON.parse(fs.readFileSync(path.join(root, 'version.json'), 'utf8')).version || ver;
  } catch (e) {}
  const parts = ver.split('.').map(function (x) { return parseInt(x, 10) || 0; });
  const code = (parts[0] || 0) * 10000 + (parts[1] || 0) * 100 + (parts[2] || 0);
  fs.writeFileSync(
    path.join(root, 'android/version.properties'),
    '# 由 scripts/build-apk.js 自动生成，不要手改\nversionName=' + ver + '\nversionCode=' + code + '\n'
  );
  console.log('versionName: ' + ver + '  versionCode: ' + code);
}

/* 把本机的智谱 Key 写进 Java 常量：只进 APK，不进仓库、不进热更包 */
function writeAiKey() {
  let key = '';
  try { key = fs.readFileSync(path.join(root, 'ai-key.local'), 'utf8').trim(); } catch (e) {}
  const safe = key.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  fs.writeFileSync(
    path.join(root, 'android/app/src/main/java/com/cooklog/kitchen/AiKey.java'),
    'package com.cooklog.kitchen;\n\n' +
    '/** 由 scripts/build-apk.js 从 ai-key.local 生成，不要提交到仓库。 */\n' +
    'public final class AiKey {\n' +
    '    public static final String ZHIPU = "' + safe + '";\n' +
    '}\n'
  );
  console.log('AI key: ' + (key ? 'injected from ai-key.local' : 'EMPTY (ai-key.local not found)'));
}

run('node scripts\\build-bundle.js');
writeVersionProps();
writeAiKey();
run('npx cap sync android');
run('gradlew.bat assembleDebug', path.join(root, 'android'));

const apk = path.join(root, 'android/app/build/outputs/apk/debug/app-debug.apk');
if (!fs.existsSync(apk)) throw new Error('APK not found: ' + apk);
const target = path.join(root, '厨房手记.apk');
fs.copyFileSync(apk, target);
console.log('APK: ' + target + ' (' + fs.statSync(target).size + ' bytes)');
