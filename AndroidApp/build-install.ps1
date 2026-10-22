param(
    [string]$Serial = "",
    [switch]$BuildOnly,
    [switch]$CheckDesktop
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Push-Location $PSScriptRoot
try {
    if ($CheckDesktop) {
        if (!(Get-Command node -ErrorAction SilentlyContinue)) { throw "Install Node.js 22+ to run desktop sync regression checks." }
        $desktopRoot = Join-Path (Split-Path $PSScriptRoot -Parent) "Browserapp"
        foreach ($test in @("automation\portable-sync-selftest.js", "automation\sync-engine-selftest.js", "automation\website-state-selftest.js", "ui-module-load-selftest.js")) {
            & node (Join-Path $desktopRoot $test)
            if ($LASTEXITCODE -ne 0) { throw "Desktop sync regression failed. Installation was stopped." }
        }
    }
    $cachedTools = Join-Path $env:LOCALAPPDATA "Temp\opencode\android-build"
    $javaCandidates = @(
        $env:JAVA_HOME,
        (Join-Path $cachedTools "jdk17"),
        (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
    )
    $javaRoot = $javaCandidates | Where-Object {
        $_ -and (Test-Path (Join-Path $_ "bin\java.exe"))
    } | Select-Object -First 1
    if (!$javaRoot) { throw "JDK not found. Install JDK 17 and set JAVA_HOME." }
    $env:JAVA_HOME = $javaRoot

    $localSdk = ""
    if (Test-Path "local.properties") {
        $sdkLine = Get-Content "local.properties" | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $localSdk = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\') }
    }
    $sdkCandidates = @(
        $localSdk, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        (Join-Path $cachedTools "sdk"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    )
    $sdkRoot = $sdkCandidates | Where-Object {
        $_ -and (Test-Path (Join-Path $_ "platforms\android-34\android.jar"))
    } | Select-Object -First 1
    if (!$sdkRoot) { throw "Android SDK 34 not found. Install platforms;android-34 and build-tools;34.0.0." }
    $env:ANDROID_HOME = $sdkRoot
    if ($localSdk -ne $sdkRoot) {
        $properties = @()
        if (Test-Path "local.properties") {
            $properties = @(Get-Content "local.properties" | Where-Object { $_ -notmatch '^sdk\.dir=' })
        }
        $escapedSdk = $sdkRoot.Replace('\', '\\').Replace(':', '\:')
        $properties += "sdk.dir=$escapedSdk"
        [IO.File]::WriteAllLines((Join-Path $PSScriptRoot "local.properties"), [string[]]$properties, [Text.UTF8Encoding]::new($false))
    }

    & .\gradlew.bat --console=plain assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw "Build or checks failed. Installation was stopped." }

    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    $metadata = Get-Content "app\build\outputs\apk\debug\output-metadata.json" -Raw | ConvertFrom-Json
    New-Item -ItemType Directory -Force "dist" | Out-Null
    $artifact = Join-Path $PSScriptRoot "dist\openbrowser-mobile-$($metadata.elements[0].versionName).apk"
    Copy-Item $apk $artifact -Force
    Write-Host "APK: $artifact"
    if ($BuildOnly) { return }

    $adb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (!(Test-Path $adb)) { throw "ADB not found. Install Android SDK Platform Tools." }
    $deviceLines = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw "Cannot list Android devices." }
    $devices = @($deviceLines | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($Serial) {
        if ($Serial -notin $devices) { throw "Device $Serial is not ready. Unlock the phone and authorize USB debugging. $($deviceLines -join '; ')" }
    } elseif ($devices.Count -eq 1) {
        $Serial = $devices[0]
    } else {
        throw "Expected one authorized phone; found $($devices.Count). Connect and authorize your phone, or use -Serial. $($deviceLines -join '; ')"
    }
    # Update in place. Never uninstall, clear app data, or silently allow a downgrade.
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "Installation failed. Existing app data was not cleared." }
    $launch = @(& $adb -s $Serial shell am start -W -n com.openbrowser.mobile/.ui.MainActivity)
    if ($LASTEXITCODE -ne 0 -or ($launch -match 'Error:|Exception')) {
        throw "APK installed, but launch failed: $($launch -join '; ')"
    }
    $launch | Write-Host
    Write-Host "Installed and launched on $Serial. Complete the in-app smoke checks in README.md."
} finally {
    Pop-Location
}
