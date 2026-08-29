<#
.SYNOPSIS
    Compila e firma l'APK con il certificato Persoft che sta sul token Certum.

.DESCRIPTION
    Su Android la catena CA non conta nulla: il certificato serve solo come
    identita' stabile del firmatario. Qui si usa comunque il certificato Persoft
    (CN=Persoft di Patassini Alessandro) perche' e' la scelta fatta per questo
    progetto; il token deve essere inserito a ogni firma.

    Passaggi: assembleRelease -> zipalign -> apksigner via PKCS#11 -> verify.

    Il PIN non viene mai messo sulla riga di comando (sarebbe visibile nella
    lista processi): apksigner lo legge da una variabile d'ambiente.

.PARAMETER Apk
    APK non firmata da firmare. Default: l'output di assembleRelease.

.PARAMETER Out
    Percorso dell'APK firmata. Default: dist\app-release-persoft.apk.

.PARAMETER NoBuild
    Non ricompila: firma l'APK indicata da -Apk cosi' com'e'.

.PARAMETER Pin
    PIN del token. Se omesso viene cercato in $env:PERSOFT_PIN,
    $env:FASTDESK_TOKEN_PIN e nel file .tokenpin nella radice del repo.

.EXAMPLE
    .\tools\firma\firma-apk.ps1
    Compila la release e la firma, output in dist\app-release-persoft.apk.

.EXAMPLE
    .\tools\firma\firma-apk.ps1 -NoBuild -Apk .\scaricata-da-ci.apk
    Firma un'APK gia' compilata (per esempio scaricata dagli artefatti della CI).
#>
[CmdletBinding()]
param(
    [string]$Apk,
    [string]$Out,
    [switch]$NoBuild,
    [string]$Pin,
    [int]$BuildNumber = 0
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Step($t) { Write-Host "==> $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host "ERRORE: $t" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- JDK
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    $j = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($j) { $jdk = Split-Path -Parent (Split-Path -Parent $j.Source) }
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Fail "JDK non trovato: imposta JAVA_HOME oppure metti java.exe nel PATH."
}
$java = Join-Path $jdk 'bin\java.exe'

# ---------------------------------------------------------------- Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $lp = Join-Path $repo 'local.properties'
    if (Test-Path $lp) {
        $m = (Get-Content $lp | Select-String '^\s*sdk\.dir\s*=\s*(.+)$')
        if ($m) { $sdk = $m.Matches[0].Groups[1].Value.Trim() -replace '\\', '\' -replace '\:', ':' }
    }
}
if (-not $sdk) { $sdk = 'C:\Android\Sdk' }
if (-not (Test-Path $sdk)) { Fail "Android SDK non trovato ($sdk). Imposta ANDROID_HOME." }

# La build-tools piu' recente che contenga davvero gli strumenti che servono.
$bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
      Where-Object { (Test-Path (Join-Path $_.FullName 'zipalign.exe')) -and
                     (Test-Path (Join-Path $_.FullName 'lib\apksigner.jar')) } |
      Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
if (-not $bt) { Fail "Nessuna build-tools utilizzabile in $sdk\build-tools." }
$zipalign     = Join-Path $bt.FullName 'zipalign.exe'
$apksignerJar = Join-Path $bt.FullName 'lib\apksigner.jar'
Write-Host "JDK          : $jdk"
Write-Host "build-tools  : $($bt.Name)"

# ---------------------------------------------------------------- PIN
if (-not $Pin) { $Pin = $env:PERSOFT_PIN }
if (-not $Pin) { $Pin = $env:FASTDESK_TOKEN_PIN }
if (-not $Pin) {
    $pf = Join-Path $repo '.tokenpin'
    if (Test-Path $pf) { $Pin = (Get-Content $pf -Raw).Trim() }
}
if (-not $Pin) {
    Fail "PIN del token non trovato. Imposta `$env:PERSOFT_PIN, oppure crea .tokenpin nella radice del repo, oppure passa -Pin."
}

# ---------------------------------------------------------------- build
$defaultUnsigned = Join-Path $repo 'app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not $NoBuild) {
    Step "Compilo la release"
    # I socket AF_UNIX creati sotto AppData\Local\Temp su questa macchina falliscono
    # con "Invalid argument: connect", e senza di quelli Gradle non riesce nemmeno a
    # parlare col proprio daemon ("Unable to establish loopback connection"). Il temp
    # va quindi spostato fuori da AppData per la durata della build.
    $tmp = Join-Path $repo '_temp\tmp'
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $oldTemp = $env:TEMP; $oldTmp = $env:TMP
    $env:TEMP = $tmp; $env:TMP = $tmp
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = $sdk
    # L'APK deve uscire NON firmata: la chiave sta sul token e Gradle non puo' usarla.
    $env:LUNA_FIRMA_ESTERNA = "1"
    # versionCode = 1 + questo numero. In CI lo da' GITHUB_RUN_NUMBER; qui, dove si compila la
    # build che viene davvero distribuita, lo decide chi pubblica: con 0 uscirebbe versionCode 1,
    # cioe' piu' vecchio di qualunque cosa sia gia' installata, e nessun telefono si aggiornerebbe.
    if ($BuildNumber -gt 0) { $env:LUNA_BUILD_NUMBER = "$BuildNumber" }
    try {
        & (Join-Path $repo 'gradlew.bat') -p $repo assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { Fail "assembleRelease fallita (exit $LASTEXITCODE)." }
    } finally {
        $env:TEMP = $oldTemp; $env:TMP = $oldTmp
        Remove-Item Env:\LUNA_FIRMA_ESTERNA -ErrorAction SilentlyContinue
        Remove-Item Env:\LUNA_BUILD_NUMBER -ErrorAction SilentlyContinue
    }
    if (-not $Apk) { $Apk = $defaultUnsigned }
}
if (-not $Apk) { $Apk = $defaultUnsigned }
if (-not (Test-Path $Apk)) { Fail "APK di partenza non trovata: $Apk" }
$Apk = (Resolve-Path $Apk).Path

if (-not $Out) { $Out = Join-Path $repo 'dist\app-release-persoft.apk' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null

# ---------------------------------------------------------------- zipalign
# Va fatto PRIMA della firma: zipalign dopo apksigner invaliderebbe la firma v2/v3.
Step "zipalign"
$aligned = Join-Path $repo '_temp\app-release-aligned.apk'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $aligned) | Out-Null
& $zipalign -p -f 4 $Apk $aligned
if ($LASTEXITCODE -ne 0) { Fail "zipalign fallito." }

# ---------------------------------------------------------------- firma
Step "Firma col token Certum (profilo: common profile)"
$cfg = Join-Path $PSScriptRoot 'certum-common.cfg'
$wrapper = Join-Path $PSScriptRoot 'ApkSignerPkcs11.java'
$env:PERSOFT_PIN = $Pin   # apksigner lo legge da qui: non finisce nella riga di comando
try {
    & $java -cp $apksignerJar "-Dpkcs11.config=$cfg" $wrapper sign `
        --ks NONE --ks-type PKCS11 --ks-provider-name SunPKCS11-Certum `
        --ks-key-alias 'Persoft di Patassini Alessandro' `
        --ks-pass env:PERSOFT_PIN `
        --v4-signing-enabled false `
        --out $Out $aligned
    if ($LASTEXITCODE -ne 0) { Fail "apksigner fallito (exit $LASTEXITCODE)." }
} finally {
    Remove-Item Env:\PERSOFT_PIN -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- verifica
Step "Verifica"
& $java -jar $apksignerJar verify --verbose --print-certs $Out
if ($LASTEXITCODE -ne 0) { Fail "La verifica della firma e' fallita." }

Write-Host ""
Write-Host "APK firmata: $Out" -ForegroundColor Green
Write-Host ("dimensione : {0:N0} byte" -f (Get-Item $Out).Length)
