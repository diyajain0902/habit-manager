<#
  Builds a double-clickable Habit Manager for Windows.

    .\package.ps1              -> dist\HabitManager\HabitManager.exe  (portable folder)
                                  dist\HabitManager-windows.zip      (the same, zipped to share)
    .\package.ps1 -Installer   -> also dist\HabitManager-1.0.0.exe, a classic installer
                                  (needs the WiX Toolset 3 on your PATH)

  Needs a JDK 17 or newer (javac, jar, jdeps, jpackage) on your PATH. The app includes its
  own small Java runtime, so the computer you give it to does not need Java at all.
#>
param([switch]$Installer)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$version = '1.0.0'
$build = 'build'
$dist = 'dist'

function Run($exe, [string[]]$arguments) {
  & $exe @arguments
  if ($LASTEXITCODE -ne 0) { throw "$exe failed (exit code $LASTEXITCODE)" }
}

Write-Host 'Cleaning old build...'
Remove-Item -Recurse -Force $build, (Join-Path $dist 'HabitManager'), (Join-Path $dist 'HabitManager-windows.zip') -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $build 'classes'), (Join-Path $build 'jar'), $dist | Out-Null

Write-Host 'Compiling...'
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
Run javac (@('-d', (Join-Path $build 'classes')) + $sources)

Write-Host 'Drawing the bubble icon...'
Run java @('-cp', (Join-Path $build 'classes'), 'habitmanager.desktop.IconExporter', (Join-Path $build 'bubble.ico'))

Write-Host 'Packing the jar (with the dashboard inside)...'
$jar = Join-Path $build 'jar\habitmanager.jar'
Run jar @('--create', '--file', $jar, '--main-class', 'habitmanager.Main', '-C', (Join-Path $build 'classes'), '.', '-C', '.', 'web')

# Only bundle the parts of Java the app actually uses, to keep the download small.
$modules = (& jdeps --print-module-deps --ignore-missing-deps $jar).Trim()
if ($LASTEXITCODE -ne 0 -or -not $modules) { throw 'jdeps failed' }
Write-Host "Java modules needed: $modules"

$common = @(
  '--name', 'HabitManager',
  '--app-version', $version,
  '--vendor', 'Habit Manager',
  '--description', 'A cute, pastel habit tracker with reminders',
  '--icon', (Join-Path $build 'bubble.ico'),
  '--input', (Join-Path $build 'jar'),
  '--main-jar', 'habitmanager.jar',
  '--main-class', 'habitmanager.Main',
  '--add-modules', $modules,
  '--jlink-options', '--strip-debug --no-man-pages --no-header-files --compress=2',
  '--dest', $dist
)

Write-Host 'Building the app folder with jpackage...'
Run jpackage (@('--type', 'app-image') + $common)

Write-Host 'Zipping it up...'
Compress-Archive -Path (Join-Path $dist 'HabitManager') -DestinationPath (Join-Path $dist 'HabitManager-windows.zip') -Force

if ($Installer) {
  if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    throw 'The installer needs the WiX Toolset 3 (candle.exe) on your PATH: https://wixtoolset.org/docs/wix3/'
  }
  Write-Host 'Building the installer...'
  Run jpackage (@('--type', 'exe', '--win-menu', '--win-shortcut', '--win-per-user-install', '--win-dir-chooser') + $common)
}

Write-Host ''
Write-Host 'Done!' -ForegroundColor Green
Write-Host "  Double-click: $(Join-Path $dist 'HabitManager\HabitManager.exe')"
Write-Host "  To share:     $(Join-Path $dist 'HabitManager-windows.zip')"
Write-Host '  Your habits are kept in %APPDATA%\HabitManager.'
