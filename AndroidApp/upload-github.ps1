param(
    [string]$Branch = ("android/automatic-sync-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$remote = "git@github.com:rescheni/OpenBrowser.git"
$sourceRoot = Split-Path $PSScriptRoot -Parent
$checkout = Join-Path ([IO.Path]::GetTempPath()) ("openbrowser-upload-" + [Guid]::NewGuid().ToString("N"))

function Invoke-Git {
    param([string[]]$GitArguments)
    & git @GitArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Git command failed. Checkout retained at: $checkout"
    }
}

if (!(Get-Command git -ErrorAction SilentlyContinue)) {
    throw "Install Git for Windows before running this script."
}

$files = @(
    "AndroidApp/app/build.gradle.kts",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/data/ProfileStore.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/BrowserActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/MainActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/proxy/LocalProxyServer.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/proxy/ProxyManager.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/proxy/ProxyTester.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/proxy/UpstreamDialer.kt",
    "AndroidApp/app/src/main/res/values/strings.xml",
    "AndroidApp/app/src/main/res/values-en/strings.xml",
    "AndroidApp/app/src/test/java/com/openbrowser/mobile/proxy/UpstreamDialerTest.kt",
    "AndroidApp/README.md",
    "AndroidApp/build-install.ps1",
    "AndroidApp/upload-github.ps1",
    "AndroidApp/app/src/main/AndroidManifest.xml",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/SettingsActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/SyncActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/GeckoBrowserActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/web/WebDataCleaner.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/PortableCrypto.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/PortableTransport.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/PortableBundle.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/CookieSnapshots.kt",
    "AndroidApp/app/src/main/res/layout/activity_settings.xml",
    "AndroidApp/app/src/main/res/menu/menu_browser.xml",
    "AndroidApp/app/src/main/res/values/sync_strings.xml",
    "AndroidApp/app/src/main/res/values-en/sync_strings.xml",
    "AndroidApp/app/src/test/java/com/openbrowser/mobile/sync/PortableCryptoTest.kt",
    "AndroidApp/app/src/test/resources/portable-sync-vector.txt",
    "Browserapp/automation/portable-sync.js",
    "Browserapp/automation/portable-sync-selftest.js",
    "Browserapp/package.json",
    "Browserapp/main.js",
    "Browserapp/preload.js",
    "Browserapp/index.html",
    "Browserapp/ui/ui-cloud.js",
    ".github/workflows/android-apk.yml",
    ".github/workflows/portable-sync.yml",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/OpenBrowserApp.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/SyncMerge.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/ProfileLeases.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/SyncStore.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/S3Signer.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/SyncCoordinator.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/SyncWorker.kt",
    "AndroidApp/app/src/main/res/values/sync_auto_strings.xml",
    "AndroidApp/app/src/main/res/values-en/sync_auto_strings.xml",
    "AndroidApp/app/src/test/java/com/openbrowser/mobile/sync/SyncMergeTest.kt",
    "Browserapp/automation/sync-merge.js",
    "Browserapp/automation/sync-provider.js",
    "Browserapp/automation/sync-controller.js",
    "Browserapp/automation/sync-engine-selftest.js",
    "Browserapp/automation/s3-sign.js",
    "Browserapp/automation/website-state.js",
    "Browserapp/automation/website-state-runtime.js",
    "Browserapp/automation/website-state-selftest.js",
    "Browserapp/sync-smoke/index.html",
    "AndroidApp/app/src/main/assets/sync/website-state-runtime.js",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/WebsiteData.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/WebsiteSnapshots.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/WebsiteBrowserBridge.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/sync/CredentialVaultDialog.kt",
    "AndroidApp/app/src/test/java/com/openbrowser/mobile/sync/WebsiteDataTest.kt",
    "AndroidApp/app/src/test/resources/website-state-vector.json",
    "Browserapp/engine.js",
    "Browserapp/engine/profile-sanitizer.js",
    "Browserapp/renderer.js",
    "Browserapp/package-lock.json",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/data/TrashProfileStore.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/ProfileEditActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/ui/GeckoCleanupActivity.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/web/ProfileDataCleaner.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/web/GeckoDataCleanup.kt",
    "AndroidApp/app/src/main/java/com/openbrowser/mobile/web/WebViewProcess.kt"
)
foreach ($file in $files) {
    if (!(Test-Path -LiteralPath (Join-Path $sourceRoot $file))) {
        throw "Source file missing: $file"
    }
}

# Clone the existing history into a separate directory. Do not modify the
# source checkout, publish build outputs, or replace the remote default branch.
Invoke-Git -GitArguments @("clone", "--", $remote, $checkout)
Push-Location $checkout
try {
    Invoke-Git -GitArguments @("check-ref-format", "--branch", $Branch)
    Invoke-Git -GitArguments @("switch", "-c", $Branch)
    foreach ($file in $files) {
        $destination = Join-Path $checkout $file
        New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
        Copy-Item -LiteralPath (Join-Path $sourceRoot $file) -Destination $destination -Force
    }
    Invoke-Git -GitArguments (@("add", "--") + $files)
    Invoke-Git -GitArguments @("diff", "--cached", "--stat")

    $messageFile = Join-Path $checkout ".git\upload-message.txt"
    $message = @"
Implement automatic desktop and Android profile sync

Add three-way merging, reviewed conflict resolution, conditional writes,
offline retries, encrypted settings and automatic scheduling on both ends.
Support WebDAV, native S3 signing, presigned URLs, local files and LAN.
Capture opted-in Android Cookies on backgrounding, defer updates to open
profiles and apply known-Cookie deletions without replaying consumed imports.
Add origin-scoped localStorage/IndexedDB snapshots and an encrypted app-owned
password vault with opt-in v2 merging and one-shot storage restoration.
Include the preceding portable exchange, isolation and proxy fixes.

Validation: 40 sync tests passed; 2 socket tests were skipped due to the
editing sandbox. Kotlin compilation, Android unit tests, lint and device
verification are pending because no usable JDK/ADB is available here.
"@
    [IO.File]::WriteAllText($messageFile, $message, [Text.UTF8Encoding]::new($false))
    Invoke-Git -GitArguments @("commit", "--file", $messageFile)
    Invoke-Git -GitArguments @("push", "--set-upstream", "origin", "HEAD")
    Write-Host "Uploaded branch: $Branch"
    Write-Host ("Review: https://github.com/rescheni/OpenBrowser/tree/" + $Branch)
    Write-Host "Checkout retained at: $checkout"
} finally {
    Pop-Location
}
