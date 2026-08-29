<#
.SYNOPSIS
    Compila, firma col token Certum e pubblica la versione distribuita.

.DESCRIPTION
    Due destinazioni, un solo APK: la cartella del sito (`J:\lunaultra`, che e' la webroot di
    persoft.it montata come disco) e una release su GitHub. Sono lo stesso file, firmato una
    volta sola: due APK diversi con lo stesso numero di versione sarebbero un modo sicuro di
    non capire piu' cosa c'e' installato sul telefono.

    L'ordine di pubblicazione sul sito non e' casuale: l'APK va a posto per prima e il manifest
    per ultimo. Finche' il manifest non cambia i telefoni vedono la versione precedente, e non
    esiste un istante in cui annunciano una versione il cui APK e' ancora a meta' upload.

.PARAMETER Note
    Testo che accompagna la versione: finisce nel manifest e nelle note della release.

.PARAMETER Destinazione
    Cartella del sito. Default: J:\lunaultra.

.PARAMETER BuildNumber
    Forza il numero di build. Se omesso viene calcolato: uno piu' del massimo fra quello gia'
    pubblicato sul sito e l'ultimo numero di run della CI, cosi' il versionCode cresce sempre
    qualunque canale abbia pubblicato per ultimo.

.PARAMETER SoloSito
    Non tocca GitHub: pubblica solo sul sito.

.EXAMPLE
    .\tools\pubblica\pubblica-aggiornamento.ps1 -Note "Unione panoramiche piu' veloce."
#>
[CmdletBinding()]
param(
    [string]$Note = "Miglioramenti e correzioni di Luna Timelapse.",
    [string]$Destinazione = "J:\lunaultra",
    [int]$BuildNumber = 0,
    [switch]$SoloSito
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$improntaPersoft = "2A6D58A5B9223B78A439E5A8206FC087C936BD47"
$nomeApkPubblicato = "LunaTimelapse.apk"
$urlBase = "https://www.persoft.it/lunaultra"
$manifestUrl = "$urlBase/aggiornamento.txt"
$tagRelease = "persoft"
$repoGitHub = "apatassini/Insta360luna"

function Step($t) { Write-Host "==> $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host "ERRORE: $t" -ForegroundColor Red; exit 1 }

# Windows PowerShell 5.1 negozia ancora TLS 1.0 di suo, e i server seri non lo accettano piu':
# senza questa riga la lettura del manifest fallisce e non si capisce perche'.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# In 5.1 lo stderr di un eseguibile diventa un ErrorRecord, e con ErrorActionPreference = Stop
# basta una riga di avviso su stderr per far morire lo script: `gh release delete` su un tag che
# non esiste ancora e' esattamente questo caso. Qui si guarda il codice di uscita, che e' l'unica
# cosa che dice davvero com'e' andata.
function Native {
    param([scriptblock]$Comando)
    $prima = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Comando | Out-Null; return $LASTEXITCODE } finally { $ErrorActionPreference = $prima }
}

# ---------------------------------------------------------------- numero di build
# versionCode = 1 + BuildNumber. Deve superare tutto cio' che e' gia' in giro, altrimenti
# Android rifiuta l'aggiornamento senza tante spiegazioni.
if ($BuildNumber -le 0) {
    $daSito = 0
    try {
        $manifest = Invoke-RestMethod -Uri "$manifestUrl`?t=$([int](Get-Date -UFormat %s))" -TimeoutSec 20
        if ($manifest.versionCode) { $daSito = [int]$manifest.versionCode }
    } catch {
        Write-Host "    (sul sito non c'e' ancora un manifest: parto dalla CI)" -ForegroundColor DarkGray
    }
    $daCi = 0
    try {
        $ultimo = & gh run list --repo $repoGitHub --limit 1 --json number --jq '.[0].number' 2>$null
        if ($ultimo) { $daCi = [int]$ultimo }
    } catch {
        Write-Host "    (numero di run della CI non leggibile)" -ForegroundColor DarkGray
    }
    $BuildNumber = ([Math]::Max($daSito, $daCi)) + 1
}
Step "Build numero $BuildNumber (versionCode $(1 + $BuildNumber))"

# ---------------------------------------------------------------- compila e firma
Step "Compilo e firmo col token Certum"
& powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $repo 'tools\firma\firma-apk.ps1') -BuildNumber $BuildNumber
if ($LASTEXITCODE -ne 0) { Fail "Compilazione o firma non riuscita." }

$apk = Join-Path $repo 'dist\app-release-persoft.apk'
if (-not (Test-Path -LiteralPath $apk)) { Fail "APK firmata non trovata: $apk" }

# ---------------------------------------------------------------- strumenti
$java = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $java) { $java = $env:JAVA_HOME }
if (-not $java -or -not (Test-Path (Join-Path $java 'bin\java.exe'))) { Fail "JDK 17 non trovato." }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\Sdk' }
$strumenti = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'lib\apksigner.jar') } |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
if (-not $strumenti) { Fail "apksigner non trovato in $sdk\build-tools." }

# ---------------------------------------------------------------- controlli prima di pubblicare
Step "Verifico firma e versione dell'APK"
$verifica = & (Join-Path $java 'bin\java.exe') -jar (Join-Path $strumenti.FullName 'lib\apksigner.jar') `
    verify --verbose --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "Firma Android non valida:`n$($verifica -join "`n")" }
$rigaImpronta = $verifica | Select-String "certificate SHA-1 digest:" | Select-Object -First 1
if (-not $rigaImpronta) { Fail "apksigner non ha riportato l'impronta del certificato." }
$impronta = $rigaImpronta.ToString().Split(":")[-1].Trim().Replace(":", "").ToUpperInvariant()
if ($impronta -ne $improntaPersoft) {
    Fail "L'APK non e' firmata con il certificato Persoft (impronta trovata: $impronta)."
}

$aapt = Join-Path $strumenti.FullName 'aapt2.exe'
if (-not (Test-Path -LiteralPath $aapt)) { Fail "aapt2 non trovato: non posso leggere la versione dell'APK." }
$badging = & $aapt dump badging $apk 2>&1 | Select-String "^package: name=" | Select-Object -First 1
if (-not $badging) { Fail "Non riesco a leggere la versione dell'APK." }
$versioneCodice = [int]([regex]::Match($badging.ToString(), "versionCode='(\d+)'").Groups[1].Value)
$versioneNome = [regex]::Match($badging.ToString(), "versionName='([^']+)'").Groups[1].Value
if ($versioneCodice -ne (1 + $BuildNumber)) {
    Fail "L'APK ha versionCode $versioneCodice invece di $(1 + $BuildNumber): la build non ha visto LUNA_BUILD_NUMBER."
}

$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
$manifestNuovo = [ordered]@{
    versionCode = $versioneCodice
    versionName = $versioneNome
    apkUrl      = "$urlBase/$nomeApkPubblicato"
    sha256      = $hash
    note        = $Note
}

# ---------------------------------------------------------------- sito
$radice = Split-Path -Qualifier $Destinazione
if (-not (Test-Path -LiteralPath "$radice\")) {
    Fail "Il disco $radice non e' disponibile: monta la webroot di persoft.it prima di pubblicare."
}
if (-not (Test-Path -LiteralPath $Destinazione)) {
    New-Item -ItemType Directory -Path $Destinazione -Force | Out-Null
}

Step "Pubblico su $Destinazione"
$apkTemporaneo = Join-Path $Destinazione "$nomeApkPubblicato.nuovo"
$manifestTemporaneo = Join-Path $Destinazione "aggiornamento.txt.nuovo"
Copy-Item -LiteralPath $apk -Destination $apkTemporaneo -Force
[IO.File]::WriteAllText($manifestTemporaneo, ($manifestNuovo | ConvertTo-Json -Depth 3),
    [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $apkTemporaneo -Destination (Join-Path $Destinazione $nomeApkPubblicato) -Force
Move-Item -LiteralPath $manifestTemporaneo -Destination (Join-Path $Destinazione 'aggiornamento.txt') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pagina-aggiornamenti.html') `
    -Destination (Join-Path $Destinazione 'index.html') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'htaccess-lunaultra') `
    -Destination (Join-Path $Destinazione '.htaccess') -Force

# ---------------------------------------------------------------- GitHub
if (-not $SoloSito) {
    Step "Pubblico la release '$tagRelease' su GitHub"
    # Tag a se' stante, separato dagli `apk-<branch>` della CI: quelle sono build di sviluppo
    # firmate con la chiave del repository, questa e' la versione firmata Persoft. Mescolarle
    # sotto lo stesso tag vorrebbe dire proporre al telefono un APK che non gli si installa.
    $copia = Join-Path $env:TEMP $nomeApkPubblicato
    Copy-Item -LiteralPath $apk -Destination $copia -Force
    $note = @"
Luna Timelapse $versioneNome (build $versioneCodice), firmata con il certificato Persoft.

$Note

SHA-256: $hash

Si installa anche da https://www.persoft.it/lunaultra/ — e' lo stesso file. L'app si aggiorna
da sola da li': questa release serve a chi vuole scaricarla a mano.
"@
    # Il tag puo' non esistere ancora: la cancellazione fallisce e va bene cosi'.
    Native { gh release delete $tagRelease --repo $repoGitHub --yes --cleanup-tag } | Out-Null
    $esito = Native {
        gh release create $tagRelease --repo $repoGitHub `
            --title "Luna Timelapse $versioneNome (Persoft)" --notes $note $copia
    }
    if ($esito -ne 0) { Fail "Pubblicazione della release GitHub non riuscita (exit $esito)." }
    Remove-Item -LiteralPath $copia -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Pubblicata Luna Timelapse $versioneNome (versionCode $versioneCodice)" -ForegroundColor Green
Write-Host "SHA-256: $hash" -ForegroundColor Cyan
Write-Host "Sito   : $urlBase/" -ForegroundColor Cyan
if (-not $SoloSito) {
    Write-Host "GitHub : https://github.com/$repoGitHub/releases/tag/$tagRelease" -ForegroundColor Cyan
}
