# android-client · 安卓多开客户端

**把两个浏览器脚本装进一个安卓 App：Kotlin 壳 + WebView，多进程 + 独立数据目录做账号隔离。**

| | |
|---|---|
| 版本 | v0.8.0（测试版 / debug） |
| 形态 | Android App（Kotlin + WebView） |
| 内置脚本 | quiz-helper v1.6.2 / materials-helper v1.3.1 |
| 安装包 | 不随仓库分发（见 `dist/README.md`） |

> **适用平台：易智教云课堂（`csgmooc.com`，含其移动端页面）**
> 内置的两个脚本分别对应该平台的「刷题」和「资料」；其他平台的页面不会生效。

---

## 速览（原来的详细说明在下面）

这是一个可运行的 Android 验证客户端：原生层已迁移到 Kotlin，两个油猴脚本原样跑在
WebView 里，用 Android 多进程 + `WebView.setDataDirectorySuffix` 做账号隔离，
用原生 HTTP 桥接解决 AI 接口的 CORS 问题。

## 当前能力（v0.4.6）

- 默认 2 个实例，主界面可添加到 6 个；实例 1~6 分别运行在
  `:acct0` 到 `:acct5` 六个独立进程。
- 每个实例有独立的 WebView 数据目录：`app_webview_yzj_acct0` 到
  `app_webview_yzj_acct5`。
- 主界面显示每个实例的登录状态、当前页面、脚本版本、题库条数、运行/停止状态，
  并显示最近一次 WebView 预览图。
- 支持“仅日志”模式：WebView 缩到 2dp 保持可见，页面区域替换为分类日志。
  网络项只显示方法、域名/路径、状态码和耗时，不显示请求头；支持进度、
  网络、错误筛选，相同日志 5 秒内自动去重，敏感字段自动脱敏。
- 支持配置方案：默认方案 + 自定义方案，可统一应用到全部实例，也可给单个实例
  保存覆盖配置。配置界面是表单，不再手写 JSON。
- 配置可导出/导入为文件；导出文件不包含 API Key。
- 使用 Android Keystore AES-GCM 加密保存登录态和 API Key。密文文件在
  `files/secure/`，账号进程和主进程都能实时读取。
- 提供“12h 会话保活”前台服务：每 10 分钟用加密保存的 authtoken 请求一次轻量接口，
  持续 12 小时；会话失效时在通知和实例状态里标记“需重登”。
- 支持单实例题库的导入/导出（文件或剪贴板）与清空。
- 支持单实例重置、全部重置、全部停止、全部启动、配置一键下发。
- 重置会同时删除该实例在 Keystore 中的加密会话；账号进程存活时先等待
  sessionStorage/localStorage/Cookie 清空，再回登录页，避免重置后又被自动恢复。
  账号进程已停止时，主进程会写入待重置标记；下次进入该实例会先清 WebView
  存储和 Cookie，再加载登录页。
- 进入账号后自动注入：
  - `quiz/csgmooc-helper.user.js`（自动作答助手 v1.6.2）
  - `materials/userscript/csgmooc-materials.user.js`（资料助手 v1.3.1）
- 兼容层补齐了 `GM_getValue / GM_setValue / GM_setClipboard / GM_xmlhttpRequest`。
- `GM_xmlhttpRequest` 走原生 `HttpURLConnection`，不受浏览器 CORS 限制，AI 请求可直接发。
- 支持原生扫码登录（getSchools → loginCode → appScanLogin），二维码直接显示在 App 内。
- 支持从文件或剪贴板导入登录态，也可以把当前账号 sessionStorage 导出到剪贴板。
- 支持清空单个实例的 sessionStorage / localStorage / Cookie。
- 资料日志、历史记录 CSV 的 Blob 下载会被拦截并保存到应用外部目录。
- WebView 渲染进程崩溃后自动重建当前账号页面。
- 实例页顶部有“任务 / 资料 / 扫码登录 / 导入文件 / 粘贴登录态 / 导出登录态 /
  清空登录态 / 日志模式 / 实例配置 / 自检 / 常亮”按钮。

## Kotlin 迁移

`app/src/main/java/com/yzj/client/` 下的业务代码全部是 `.kt`：

- `AccountActivity.kt`：WebView、脚本注入、登录态恢复、日志模式、预览。
- `MainActivity.kt`：实例卡片、批量操作、配置/题库入口。
- `ConfigDialog.kt` / `ConfigStore.kt`：表单配置与方案管理。
- `SecureStore.kt`：Android Keystore + AES-GCM。
- `SessionKeeper.kt`：12 小时会话保活前台服务。
- `QrLogin.kt`、`NativeBridge.kt`、`BankStore.kt` 等。

构建时 Gradle 会自动加载 Kotlin 2.2.0 插件。

## 构建

前置：JDK 17、Android SDK Platform 34、Gradle 8.13（本机已具备）。

```powershell
cd E:\yzj-js\android-client
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug
```

构建产物：

```
E:\yzj-js\android-client\app\build\outputs\apk\debug\app-debug.apk
```

已验证的分发副本（构建目录清理后仍保留）：

```
E:\yzj-js\android-client\dist\yzj-client-v0.4.6-poc-debug.apk
```

一键构建并安装到 MuMu：

```powershell
cd E:\yzj-js\android-client
.\build-and-install.ps1 -Serial 127.0.0.1:16384 -Launch
```

## MuMu 多开

MuMu 的 ADB 端口通常是：

- 实例 0：`127.0.0.1:16384`
- 实例 1：`127.0.0.1:16416`

克隆实例：

```powershell
& 'C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe' control -v 0 shutdown
& 'C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe' clone -v 0 -n 1
& 'C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe' control -v 0,1 launch
```

然后分别安装：

```powershell
adb -s 127.0.0.1:16384 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 127.0.0.1:16416 install -r app\build\outputs\apk\debug\app-debug.apk
```

## 登录态导入

应用启动后，账号页顶部有“导入登录态”。文件路径：

```
/sdcard/Android/data/com.yzj.client.debug/files/session_storage.json
```

推送：

```powershell
adb -s 127.0.0.1:16384 push work\session_storage.json /sdcard/Android/data/com.yzj.client.debug/files/session_storage.json
```

导入后应用会显示导入条数、token 是否存在，然后跳到任务页。若站点提示登录过期，
说明保存的 `reslibAuthtoken` 已失效，需要在账号页手动重新登录一次。

## 扫码登录

账号页顶部点“扫码登录”，输入学校名称，点“获取二维码”。扫码端是 **易智教 App**，不是微信。应用会：

1. 调 `getSchools` 拿到加密 schoolNum；
2. 调 `loginCode` 拿二维码图和 ticket；
3. 显示二维码，每秒轮询 `refreshCode?code=...`；
4. 选择登录入口：
   - 课堂模式（任务 / 资料）：`authType=0`
   - 资源库观看模式：`authType=1`
5. 易智教 App 扫码后会打开“将登录教学一体化平台，请确认是否本人操作”，
   **必须在 App 内点“确认登录”**；此时 App 会调用 `/user/scanDeal?type=2`；
   PC 端 `refreshCode` 随后返回 `action=2` 和真正的 `ticket`；
   再用 `appScanLogin?ticket=...` 换取 authtoken；
6. 用 authtoken 打开站点的 SSO 桥接页
   `spoc/html/white_blank.html?authType=…&aToken=…`；
7. 桥接页会调用 `auth/getUserDetail`，由站点自己写入 userInfo、stuInfo、schoolNum、
   encryptSchool、redirectUrl 等字段，然后跳转到对应首页。

二维码 150 秒内有效，过期后重新点“获取二维码”。

> v0.4.2 修复：
> - 旧版把 `loginCode` 返回的二维码 code 直接当 ticket 轮询 `appScanLogin`，易智教 App
>   扫码后 `refreshCode` 才会返回真正的 ticket，所以旧链路一直停在“未授权”。
> - 旧版只把 authtoken 手写进 sessionStorage，缺少 `auth/getUserDetail` 返回的
>   userInfo/schoolNum 等字段。现在扫码成功后会走 `white_blank.html` 桥接页。
>
> v0.4.3 修复重置：主进程直接删除 `session:N` 密文，账号进程异步清 Web 存储/Cookie，
> 登录页加载后再二次清理，并禁止本次重置流程从 Keystore 恢复会话。
>
> v0.4.4 补齐易智教 App 确认步骤：扫码二维码解码为
> `{"code":"...","qrtype":2}`；App 扫到后调用 `/user/scanCode`，再进入扫码确认页，
> 只有点“确认登录”调用 `/user/scanDeal?type=2`，PC 的 `refreshCode` 才返回
> `action=2` 和 ticket。
>
> v0.4.6 修复题库跨进程读取：题库改存到 `files/banks/` 下的 JSON 文件，
> 主进程导入后账号进程立即可读；同时修正原生桥多套一层 JSON 字符串导致题库
> 对象被当成文本的问题。网页平铺 JSON、App 包装 JSON、对象/键值数组和
> 中文/英文 CSV 都可导入，CSV 支持引号、逗号和字段内换行。

### 逆向核对结果

从易智教 App（`cn.campsg.spoc4` 4.35.7）的
`assets/apps/__UNI__9616053/www/app-service.js` 核对到的真实链路：

1. 二维码内容：`{"code":"...","qrtype":2}`；
2. App 扫到后调 `GET /user/scanCode?code=...`；
3. 返回 4 时进入 `pages/index/codeLogin`，页面文案是
   “将登录 教学一体化平台，请确认是否本人操作”；
4. 点“确认登录”才调用 `GET /user/scanDeal?code=...&type=2`；
5. PC 端 `refreshCode` 这时才返回 `action=2` 和 ticket；
6. PC 再调 `appScanLogin?ticket=...` 换 authtoken；
7. 最后用 `white_blank.html?authType=0&aToken=...` 写完整 SPOC sessionStorage。

只在 App 里扫码、不点“确认登录”，PC 端会一直停在 `action=0/1`，这是之前最常见
的失败原因。

## 登录态迁移

- “导出登录态”：把当前账号的完整 sessionStorage 复制到剪贴板。
- “粘贴登录态”：从剪贴板读取 JSON，写入当前账号后跳到任务页。
- “导入文件”：读取
  `/sdcard/Android/data/com.yzj.client.debug/files/session_storage.json`。

这套方式可以在实例之间迁移，也可以复制到另一台 MuMu 实例。

## 配置方案

主界面“统一配置”打开配置编辑器：

- 下拉框选择方案（默认方案 + 自定义方案）。
- 全部是可填写的表单：AI 服务商、base_url、模型、图片模型、API Key、超时、
  刷题开关、低置信阈值、重试、批量题数、延迟、资料节奏、跳过规则。
- API Key 输入框只负责写入 Keystore；留空会保留已保存的 Key。
- “保存方案”只保存；“保存并应用全部”会写入所有实例的 GM 设置。
- 实例卡片上的“配置”只影响当前实例，保存为实例覆盖。
- 主界面“导出配置 / 导入配置”用于整份配置文件迁移；导出文件不包含 API Key。

配置文件在：

```
/data/data/com.yzj.client.debug/files/config.json
```

加密的登录态和 API Key 在：

```
/data/data/com.yzj.client.debug/files/secure/
```

## 仅日志模式

实例卡片点“仅日志”会把 WebView 缩到 2dp 保持可见，主体区域显示：

- 油猴脚本通过 `GM` 兼容层输出的日志；
- WebView 控制台的 `console` 消息；
- 注入失败、页面脚本报错等诊断信息。

这样既能看到运行进度，又不用一直显示浏览器画面。WebView 仍保留 2dp 可见高度，
避免被当作完全隐藏的页面。

日志面板按“全部 / 进度 / 网络 / 错误”筛选；网络日志形如
`GET api.deepseek.com/models → 401 (126ms)`。复制按钮只复制当前筛选结果，
不会把 1,000 条历史全部塞进剪贴板。

## 题库导入导出

实例卡片点“题库”：

- 导出题库到文件：生成 `bank-slotN-时间.json`，包含 `answerBank` 与 `bankNotes`。
- 从文件导入：支持网页平铺导出、本应用包装格式、裸 `answerBank` 对象、
  JSON 数组，以及网页导出的 CSV。
- 从剪贴板导入：适合从 PC 题库编辑器复制 JSON。
- 清空题库：只删本地 `answerBank` / `bankNotes`，不影响服务器记录。

导出目录：

```
/sdcard/Android/data/com.yzj.client.debug/files/Download/yzj-export/
```

运行中的题库文件：

```
/data/data/com.yzj.client.debug/files/banks/slotN_answerBank.json
/data/data/com.yzj.client.debug/files/banks/slotN_bankNotes.json
```

## 日志与 CSV 导出

油猴脚本用 `Blob + <a download>` 导出日志/CSV，Android WebView 默认不会处理。
`shim.js` 会拦截这类点击，把文本交给原生 `saveText`，文件保存在：

```
/sdcard/Android/data/com.yzj.client.debug/files/Download/yzj-export/
```

实测导出的资料日志文件名类似：

```
yzj-mat-log-20261020-28809.txt
```

## 已完成的验证

在 MuMu Android 12 / WebView 110 上：

- 原生层已全部迁移到 Kotlin（`app/src/main/java/com/yzj/client/*.kt`，没有 Java 业务类）。
- 两个脚本均成功注入，状态行显示 `quiz:1.6.2`、`mat:1.3.1`。
- 资料助手面板可打开，GM 设置值正常读取（30/90/10/15/45）。
- 自动作答助手设置页可打开，显示 v1.6.2 和 AI 服务商配置。
- 默认 2 个实例；主界面“添加实例”扩到 3 个后，实例 1/2/3 分别对应
  `:acct0/:acct1/:acct2` 三个独立进程。
- 三个实例同时存在三个进程：
  - `com.yzj.client.debug`
  - `com.yzj.client.debug:acct0`
  - `com.yzj.client.debug:acct1`
  - `com.yzj.client.debug:acct2`
- 三个 WebView 数据目录独立：
  - `app_webview_yzj_acct0`
  - `app_webview_yzj_acct1`
  - `app_webview_yzj_acct2`
- 自检结果：每个账号 `prevLocal=null`，写入后只看到自己的 `slotN`，说明 localStorage/sessionStorage 隔离。
- 原生桥接自检：`https://api.deepseek.com/models` 返回 HTTP 401（无 key 的正常结果），说明跨域请求链路可用。
- 克隆 MuMu 实例后，两个 VM 均能同时运行同一 APK，并各自注入两个脚本。
- 原生扫码登录成功生成并显示二维码，轮询 `appScanLogin` 正常（未实际扫码）。
- 主界面预览图成功显示实例当前 WebView 画面，状态显示“运行中”。
- “仅日志”模式成功切换，页面主体显示脚本日志、控制台日志和诊断信息。
- 配置编辑器的“应用到本实例”成功写入 `config.json` 的 `overrides` 和
  `shared_prefs/yzj_gm.xml` 的 `slotN:` 键。
- 配置界面已改为表单，包含 AI、刷题、延迟、资料节奏和跳过规则；不再需要手写 JSON。
- Keystore 加密验证：`files/secure/` 下生成 `session:0` 和 API Key 密文文件；
  `yzj_secure.xml` 只有 Base64 密文；在应用数据里检索明文 token 无结果。
- API Key 解密验证：配置里保存测试 Key 后，油猴设置页的 API Key 输入框显示为已填充。
- 12h 会话保活验证：前台服务通知正常，状态文件写入 `lastSessionCheck`、
  `sessionWindowStartedAt`；旧 token 被服务端判定过期时正确标记 `sessionExpired=true`。
- 重置实测：删除 `files/secure/` 下的 `session:0` 后重新打开实例，停留在
  `uniplatform/#/school` 登录页，状态为 `token=false`，没有再次自动登录。
- v0.4.6 重置实测：账号进程运行中和停止后各测一次；`session:0` 均被删除，
  再次进入是登录页，`token=false`，题库 1082 条仍保留。
- v0.4.6 题库实测：真实网页导出 CSV 和 JSON 均导入为 1082 条；已运行实例
  通过原生桥读到 `bank=1082`，不再出现主界面有数据但脚本读成字符串的问题。
- v0.4.6 日志实测：全部/进度/网络/错误筛选、清空和当前筛选复制均可用；
  自检网络项显示为 `GET api.deepseek.com/models → 401 (126ms)`。
- v0.4.6 质量门：`assembleDebug` 与 `lintDebug` 均通过；答题脚本 92/92、
  资料脚本 19/19 离线测试通过。
- 题库“导出到文件”成功生成 `bank-slot1-20261020-135641.json`。
- 单实例“重置”成功后，登录态、运行状态和预览图被清空。
- 资料助手“导出日志”成功落到
  `/sdcard/Android/data/com.yzj.client.debug/files/Download/yzj-export/yzj-mat-log-20261020-28809.txt`。

## 已知限制

1. **后台 WebView 定时器会被 Chromium 节流**  
   在实例 A 切到实例 B 后，A 的 `setInterval` 可能暂停。当前验证版适合“切换实例使用”；
   如果要真正同时跑多个实例的长时间任务，建议：
   - 用 MuMu 多开，每个 VM 跑一个账号；或
   - 后续把业务逻辑迁到原生 Kotlin 引擎（OkHttp + 协程），WebView 只负责登录。

2. **登录态需要重新获取**  
   `work/session_storage.json` 是旧会话，站点会判定过期并清空。正式使用时在 WebView 里登录一次即可。

3. **AI Key 不预置**  
   每个账号在“AI 易智教助手 → 设置”里填自己的 Key；`GM_xmlhttpRequest` 已能直连各家 API。

4. **这是验证版，不是加固版**  
   支持 2~6 个实例；token 和 API Key 已用 Keystore 加密保存，但 WebView 页面运行期间
   sessionStorage 里仍会有内存态 token。当前是 debug 包，正式分发还需要 release 签名。

5. **12 小时保活依赖服务端会话策略**  
   `SessionKeeper` 每 10 分钟请求一次轻量接口，尽量让 token 保持活跃；如果服务端主动
   失效、异地登录或网络长期中断，仍会标记“需重登”。这不是客户端能单方面保证的。

## 后续建议

- 先保留这个 WebView 版做交互验证。
- 再把资料任务和刷题任务迁到原生 HTTP 引擎，彻底绕开 WebView 后台节流。
- 多账号调度用“账号表 + 每账号 CookieJar + 前台服务 + WorkManager”。
- 如果要继续用 WebView，至少加前台服务、账号级加密存储和更细的失败重试。

## 项目状态

**半成品，可粗略使用。** 多开、脚本注入、题库/日志等主链路已跑通，但仍有已知问题（例如切换实例后定时器可能暂停、暂无 release 签名包），适合验证与自用，不建议直接分发。

## 免责声明

本项目仅供**个人学习、技术研究与交流**使用。

- 不保证答案正确、功能完整或持续可用。
- 使用本项目产生的任何后果（包括但不限于账号异常、成绩问题、数据丢失、违反平台或学校规定）由使用者自行承担。
- 请遵守所在学校 / 教育机构及目标平台的服务条款；不要用于商业用途或大规模滥用。
- 本项目与目标平台及其运营方无任何关联，也未获得其授权或认可。
- 若相关权利方认为内容侵犯其权益，请联系删除。

## 许可证

[MIT](LICENSE) © 2026 paperlane-tools
