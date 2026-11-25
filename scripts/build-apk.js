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

run('node scripts\\build-bundle.js');
run('npx cap sync android');
run('gradlew.bat assembleDebug', path.join(root, 'android'));

const apk = path.join(root, 'android/app/build/outputs/apk/debug/app-debug.apk');
if (!fs.existsSync(apk)) throw new Error('APK not found: ' + apk);
const target = path.join(root, '厨房手记.apk');
fs.copyFileSync(apk, target);
console.log('APK: ' + target + ' (' + fs.statSync(target).size + ' bytes)');
