$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$localJdk = Join-Path $projectRoot ".toolchain\jdk\jdk-17.0.16+8"
$localSdk = Join-Path $projectRoot ".toolchain\android-sdk"
$localGradle = Join-Path $projectRoot ".toolchain\gradle-8.9\bin\gradle.bat"

if (Test-Path -LiteralPath $localJdk) {
    $env:JAVA_HOME = $localJdk
}
if (Test-Path -LiteralPath $localSdk) {
    $env:ANDROID_SDK_ROOT = $localSdk
}
if (-not $env:JAVA_HOME) {
    throw "JDK 17 not found. Install Android Studio or set JAVA_HOME to JDK 17."
}

$proxyAvailable = Test-NetConnection 127.0.0.1 -Port 7890 -InformationLevel Quiet -WarningAction SilentlyContinue
if ($proxyAvailable) {
    $env:GRADLE_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
}

Push-Location $projectRoot
try {
    if (Test-Path -LiteralPath $localGradle) {
        & $localGradle testDebugUnitTest lintDebug assembleDebug --no-daemon
    } else {
        & (Join-Path $projectRoot "gradlew.bat") testDebugUnitTest lintDebug assembleDebug --no-daemon
    }
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE." }

    $outputDir = Join-Path $projectRoot "dist"
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk") `
        -Destination (Join-Path $outputDir "FocusPlan-0.2.0-debug.apk") -Force
    Write-Host "APK: $outputDir\FocusPlan-0.2.0-debug.apk"
} finally {
    Pop-Location
}
