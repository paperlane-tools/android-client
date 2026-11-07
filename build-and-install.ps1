param(
    [string]$Serial = "127.0.0.1:16384",
    [switch]$Launch
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "C:\Users\work\AppData\Local\Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"

$gradle = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $gradle) { $gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source }
if (-not $gradle) { throw "Gradle 8.13 not found" }
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

& $gradle -p $root assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

if ($Launch) {
    & $adb -s $Serial shell am start -n com.yzj.client.debug/com.yzj.client.MainActivity
}

Write-Host "APK: $apk"
Write-Host "Installed on: $Serial"
