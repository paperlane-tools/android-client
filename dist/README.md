# android-client · Android 分发件

正式 APK 发布在 GitHub Releases：

https://github.com/paperlane-tools/android-client/releases/latest

| 版本 | 类型 | 说明 |
|---|---|---|
| `v1.0.0` | **release（正式签名）** | versionCode 100；release keystore 签名，`debuggable=false` |

仓库源树不提交 APK。Release 资产包含：

- `yzj-client-v1.0.0-release.apk`
- `yzj-client-v1.0.0-release.apk.sha256`

## 本地构建正式包

签名密钥不进入仓库。通过环境变量提供：

```powershell
$env:YZJ_RELEASE_STORE_FILE = "C:\Users\work\.android\yzj-release.jks"
$env:YZJ_RELEASE_STORE_PASSWORD = "<本机密码>"
$env:YZJ_RELEASE_KEY_ALIAS = "yzj-release"
$env:YZJ_RELEASE_KEY_PASSWORD = "<本机密码>"

gradle assembleRelease
```

输出：

```
app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions

推送 `v*` tag 时，仓库使用 Actions Secrets 构建并发布正式签名 APK：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 安装

正式包包名是 `com.yzj.client`，可与旧的 `.debug` 验证包并存：

```powershell
adb -s 127.0.0.1:16384 install -r yzj-client-v1.0.0-release.apk
```
