# android-client\dist\ · 安卓端分发件

这里放**能直接装到手机/模拟器上的 APK**。源码在 `..\app\`，构建说明见 `..\README.md`。

## 目前有什么

| 文件 | 版本 | 类型 | 说明 |
|---|---|---|---|
| `yzj-client-v0.8.0-debug.apk` | v0.8.0 | **debug（测试版）** | 由 GitHub Actions 云编译；`debuggable`，未做 release 签名 |

> 仓库里**不提交 APK**，构建产物发布在 GitHub Releases：
> https://github.com/paperlane-tools/android-client/releases
>
> 目前**没有** release/正式签名包。`..\README.md` 里也写明：
> 「当前是 debug 包，正式分发还需要 release 签名」。

## 构建 debug（和上面那个包一致）

```powershell
cd E:\yzj-js\android-client
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk dist\yzj-client-v<版本>-debug.apk
```

## 出正式版（当前仓库还没做）

1. 在 `app\build.gradle` 里加 `release` 构建类型 + `signingConfigs`（keystore 不要提交进仓库）；
2. `gradle assembleRelease`；
3. 产物拷到本目录，命名 `yzj-client-v<版本>-release.apk`；
4. 在 `..\README.md` 的版本记录里补一行。

## 安装到 MuMu 多开

```powershell
adb -s 127.0.0.1:16384 install -r yzj-client-v0.8.0-debug.apk
adb -s 127.0.0.1:16416 install -r yzj-client-v0.8.0-debug.apk
```
