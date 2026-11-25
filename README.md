# 厨房手记

一个**纯单机**的手机菜谱记录 App，用来解决三件小事：今天不知道做什么菜、做过的好菜下次想不起来、做法和成品图没地方存。

没有服务端、没有账号、不需要联网。菜谱和照片只压缩后存在你自己的手机里，不上传任何地方，卸载 App 才会没。

<img src="图标.png" width="120" alt="厨房手记">

## 功能

- **记一道**：菜名、评分、做法、成品图（最多 9 张，可直接调相机拍，也可从相册多选，自动压缩到 720px）
- **我的菜**：按菜名或**配料**搜索（搜「鸡」能出所有用到鸡肉的菜），列表按最近一次下厨时间倒序
- **详情**：配料自动拆成标签、做法、图片点开可全屏看大图（多图左右切换）、编辑与删除
- **首页推荐**：优先从 4 星以上的菜里按评分加权（`best³`）抽 3 道，可「换一批」（换批时排除当前这批，尽量不重复）
- **今天这顿**：详情页一键加入采购清单，首页把各菜的配料**去重合并**成一张采购单，买完一键清空
- **一起点菜**：局域网点菜。App 在本机起一个只读小服务，显示 4 位访问码和二维码；同一 WiFi（或你的热点）下好友扫码就能看到你的菜、看图、勾选提交，结果自动回到你手机，可一键加进采购清单
- **备份**：导出 JSON 到系统「下载」目录，导入时从那里选
- **热更新**：网页层改动可通过更新包下发，不用重装

## 技术栈

| 层 | 用了什么 |
|---|---|
| 壳 | Capacitor 8 + 原生 Android（Java） |
| 界面 | 手写 HTML / CSS / JS，无框架、无构建期依赖（`index.html` 一个文件装全部界面逻辑） |
| 存储 | IndexedDB（菜谱与图片）、localStorage（采购清单、自定义更新地址） |
| 局域网服务 | 原生 NanoHTTPD，只在点「生成链接」时监听，端口从 8137 起自增 |
| 热更新 | `@capgo/capacitor-updater` 8.54 |

## 目录结构

```
index.html              App 全部界面与逻辑（含内联 CSS/JS）
lan.html                好友扫码后看到的选菜页
lan-bridge.src.js       局域网插件的 JS 桥（Capacitor 8 需显式 registerPlugin）
icons-data.js           配料图标（内联 SVG）
qrcode.js               二维码生成库（本地内置，不走 CDN）
version.json            当前版本号，唯一来源
capacitor.config.json   appId / appName / webDir

scripts/
  build-bundle.js       打热更包：同步 www、注入版本号、出 zip + update.json
  build-apk.js          打 APK：build-bundle → cap sync → gradlew assembleDebug
  sync-web.js           只把网页资源同步进 www/（本地预览用）
  render-icons.js       按配色重新生成五套密度的图标与启动图

android/
  app/src/main/java/com/cooklog/kitchen/
    MainActivity.java   注册两个原生插件
    LanSharePlugin.java 局域网服务（菜品/图片 HTTP 接口、收好友提交）
    BackupPlugin.java   备份写入系统下载目录、读取用户选的文件
  app/src/main/res/     图标与启动图

www/                    构建产物（webDir），由脚本生成，不进仓库
dist/                   热更包与 update.json，不进仓库
tools/                  本机 JDK 21 与 Android SDK，不进仓库
```

## 本地预览

不需要打包，直接用静态服务打开：

```bash
python -m http.server 8899
# 浏览器访问 http://127.0.0.1:8899/index.html
```

浏览器里除「一起点菜」（需要原生服务）和「导出备份到下载目录」（需要原生写入）会提示不可用外，其余功能都能正常跑。

## 构建安装包

**前置**：Node 18+、**JDK 21**（Capacitor 8 要求 21，17 会报「无效的源发行版」）、Android SDK（platform 36 / build-tools 36.0.0）。把 JDK 与 SDK 放到 `tools/` 下，脚本会自动找：

```
tools/jdk21.x.x/          → JAVA_HOME
tools/android-sdk/        → ANDROID_HOME
```

然后：

```bash
npm install
node scripts/sync-web.js      # 网页资源同步进 www/
node scripts/build-apk.js     # 产出根目录的「厨房手记.apk」（debug 签名，可直装覆盖）
```

改版本号只需改 `version.json`，脚本会把它同步到两个地方：

- `build-bundle.js` 把版本号注入 `index.html` 的 `APP_VERSION` → 决定 **App 里显示**的版本
- `build-apk.js` 额外算出 `versionCode`（`1.2.2 → 10202`）写进 `android/version.properties`，Gradle 从这里读 → 决定 **安装器**看到的版本、以及能否覆盖安装

两者必须一起出：只发网页包不重打 APK，新装用户拿到的就是旧版本，进 App 却会提示有新版本（这正是真实发生过的事）。

## 发布热更新

```bash
node scripts/build-bundle.js 1.2.3   # 出 dist/cooklog-1.2.3.zip + dist/update.json
```

把这两个文件传到任意静态托管目录即可。App 里的默认更新地址是 `DEFAULT_UPDATE_URL`（`index.html` 顶部），指向 `https://ncbky.xyz/cooklog`；想换托管在「回顾 → 应用更新 → 自定义更新地址」里改。

`update.json` 结构就三个字段，`sha256` 必须是 zip 的 SHA-256 小写十六进制：

```json
{ "version": "1.2.3", "file": "cooklog-1.2.3.zip", "sha256": "…" }
```

## 必须知道的几条约束

这几条都是实际踩过的，改代码前先看一眼，能省半天。

1. **原生改动只能靠重装 APK。** 热更只下发 `www/` 里的网页资源，改 Java、Manifest、权限、图标、启动图都进不去。
2. **更新逻辑本身修不了自己。** 如果某版的检查更新代码是错的，热更补丁也包含那段错代码，只能装一次新 APK 才能跳出来。
3. **有两个版本号，别混。** 安装器只认 Gradle 的 `versionCode`（必须单调递增，否则覆盖安装会被当成同版本拦下）；App 里显示的是网页的 `APP_VERSION`。发版时 `version.json` 升一次，`build-apk.js` 两边一起写；**不要只发热更包不重打 APK**，否则新装用户装到的是旧版本，进去却提示有新版本。
4. **下载必须带校验值。** `capacitor-updater` 8.54 的 `download` 在发请求前强制校验 sha256，`checksum` 为空直接抛 `Checksum required`（表现为 `Failed to download from: <url>`，很容易误判成网络问题）。
5. **`set()` 传 `{ id }`，不是整个 bundle 对象。** 而且它成功后原生会**自动重启**，Promise 永远不会 resolve，后面别写「请重启 App」之类的逻辑。
6. **Capacitor 8 不会自动挂载插件。** `Capacitor.Plugins.X` 是空的，必须用 `registerPlugin`（`lan-bridge.src.js` 就是干这个的，用 esbuild 打成 IIFE 提供给页面）。
7. **AGP 会拦中文路径。** 项目路径含中文时 `android/gradle.properties` 必须有 `android.overridePathCheck=true`。
8. **IndexedDB 的 `transaction()` 第一个参数要传数组**（`transaction(['logs'], 'readonly')`），传字符串在部分 WebView 里直接不触发回调。
9. **好友端页面由网页传给原生。** 原生不再硬读 APK 里的 `lan.html`，所以以后改好友端界面能跟着热更走。
10. **`gradlew` 必须是 LF 换行**（`.gitattributes` 已固定），否则在 Linux/macOS 上执行报 `\r` 错误。
11. **图标前景层必须真透明。** headless Chrome 截图只给 `omitBackground` 仍会画白底，要同时 `Emulation.setDefaultBackgroundColorOverride(a=0)`，否则自适应图标整块变白。

## 数据

`IndexedDB → logs`，**一条记录 = 一次下厨**，同名菜可以有多条（详情页主区显示最新一条，底部「更早的记录」可折叠单独编辑删除）。

```js
{
  id,            // 'r' + 时间戳36进制 + 随机串
  dish,          // 菜名
  rating,        // 0-5
  images: [],    // 压缩后的 dataURL，最多 9 张
  ingredients,   // 原始文本，展示时才按中英文逗号/顿号/分号/空格/换行拆成标签
  steps,         // 做法，纯文本
  date           // 下厨日期的时间戳（毫秒）
}
```

图片以 `canvas` 压到最长边 720px、JPEG 质量 0.62，一张约几十 KB；采购清单只存菜名数组在 localStorage，不碰 IndexedDB。
