param(
    [string]$Serial = "127.0.0.1:16384",
    [switch]$Launch,
    [switch]$Release
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

if ($Release) {
    $signing = Join-Path $HOME ".android\yzj-signing.properties"
    if (-not $env:YZJ_RELEASE_STORE_FILE -and (Test-Path $signing)) {
        $props = @{}
        Get-Content $signing | ForEach-Object {
            if ($_ -match '^([^#=]+)=(.*)$') { $props[$Matches[1]] = $Matches[2] }
        }
        $env:YZJ_RELEASE_STORE_FILE = $props['storeFile']
        $env:YZJ_RELEASE_STORE_PASSWORD = $props['storePassword']
        $env:YZJ_RELEASE_KEY_ALIAS = $props['keyAlias']
        $env:YZJ_RELEASE_KEY_PASSWORD = $props['keyPassword']
    }
    & $gradle -p $root assembleRelease
    $apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    $package = "com.yzj.client"
} else {
    & $gradle -p $root assembleDebug
    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    $package = "com.yzj.client.debug"
}
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

if ($Launch) {
    & $adb -s $Serial shell am start -n "$package/com.yzj.client.MainActivity"
}

Write-Host "APK: $apk"
Write-Host "Installed on: $Serial"
