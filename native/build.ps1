# Builds the embedded Java helper: javac -> JVM checks -> R8 D8 -> Base64 in chat_tags.plugin.
# Needs JDK 17 and R8 (r8.jar); set CHAT_TAGS_JDK / CHAT_TAGS_R8 or keep the defaults below.
$ErrorActionPreference = 'Stop'
$nativeRoot = $PSScriptRoot
$jdk = if ($env:CHAT_TAGS_JDK) { $env:CHAT_TAGS_JDK } else {
    (Get-ChildItem 'C:\Users\Admin\Documents\Codex\2026-09-21\c-program-files-windowsapps\work\native\tools\jdk' -Directory | Select-Object -First 1).FullName }
$r8 = if ($env:CHAT_TAGS_R8) { $env:CHAT_TAGS_R8 } else {
    'C:\Users\Admin\Documents\Codex\2026-09-21\c-program-files-windowsapps\work\native\tools\r8.jar' }
$build = Join-Path $nativeRoot 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes, $dex -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $nativeRoot 'src'), (Join-Path $nativeRoot 'stubs'), (Join-Path $nativeRoot 'tests') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 --release 8 -d $classes @sources
if ($LASTEXITCODE) { throw 'Java compilation failed' }
& (Join-Path $jdk 'bin\java.exe') -cp $classes chat.tags.TagsBridgeTest
if ($LASTEXITCODE) { throw 'Java checks failed' }
$helperJar = Join-Path $build 'helper.jar'
$stubJar = Join-Path $build 'stubs.jar'
# Only the helper goes into the DEX; stubs and tests are build fixtures.
& (Join-Path $jdk 'bin\jar.exe') cf $helperJar -C $classes chat/tags/TagsBridge.class -C $classes 'chat/tags/TagsBridge$Entry.class' -C $classes 'chat/tags/TagsBridge$Match.class' -C $classes 'chat/tags/TagsBridge$Search.class' -C $classes 'chat/tags/TagsBridge$Callback.class'
if ($LASTEXITCODE) { throw 'Helper packaging failed' }
& (Join-Path $jdk 'bin\jar.exe') cf $stubJar -C $classes de -C $classes app -C $classes android
if ($LASTEXITCODE) { throw 'Stub packaging failed' }
& (Join-Path $jdk 'bin\java.exe') -cp $r8 com.android.tools.r8.D8 --release --min-api 26 --classpath $stubJar --output $dex $helperJar
if ($LASTEXITCODE) { throw 'DEX compilation failed' }
python (Join-Path $nativeRoot 'embed_dex.py') (Join-Path $dex 'classes.dex')
if ($LASTEXITCODE) { throw 'DEX embedding failed' }
