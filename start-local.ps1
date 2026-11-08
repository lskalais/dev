param(
    [string]$MariaDbHome = 'C:\devtool\mariadb-13.0.2-winx64',
    [string]$MariaDbData = 'C:\devtool\mariadb-13.0.2-winx64\data13',
    [string]$TomcatHome = 'C:\devtool\apache-tomcat-10.1.61',
    [int]$MariaDbPort = 3306
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:APP_ENCRYPTION_KEY)) {
    $env:APP_ENCRYPTION_KEY = [Environment]::GetEnvironmentVariable('APP_ENCRYPTION_KEY', 'User')
}

if ([string]::IsNullOrWhiteSpace($env:APP_ENCRYPTION_KEY)) {
    throw "APP_ENCRYPTION_KEY n'est pas définie dans l'environnement du processus ni dans l'environnement utilisateur Windows."
}

try {
    $encryptionKeyBytes = [Convert]::FromBase64String($env:APP_ENCRYPTION_KEY)
}
catch {
    throw 'APP_ENCRYPTION_KEY doit être encodée en Base64.'
}

if ($encryptionKeyBytes.Length -ne 32) {
    throw 'APP_ENCRYPTION_KEY doit décoder en exactement 32 octets.'
}
[Array]::Clear($encryptionKeyBytes, 0, $encryptionKeyBytes.Length)

if (-not (Test-Path -LiteralPath $MariaDbHome -PathType Container)) {
    throw "MariaDB introuvable : $MariaDbHome. Indiquez son dossier avec -MariaDbHome."
}

$mariaDbServer = Join-Path $MariaDbHome 'bin\mariadbd.exe'
if (-not (Test-Path -LiteralPath $mariaDbServer -PathType Leaf)) {
    throw "Exécutable MariaDB introuvable : $mariaDbServer"
}

if (-not (Test-Path -LiteralPath $MariaDbData -PathType Container) -or
        -not (Test-Path -LiteralPath (Join-Path $MariaDbData 'mysql') -PathType Container)) {
    throw "Dossier de données MariaDB invalide ou non initialisé : $MariaDbData"
}

if (-not (Test-Path -LiteralPath $TomcatHome -PathType Container)) {
    throw "Tomcat introuvable : $TomcatHome. Indiquez son dossier avec -TomcatHome."
}

$catalina = Join-Path $TomcatHome 'bin\catalina.bat'
if (-not (Test-Path -LiteralPath $catalina -PathType Leaf)) {
    throw "Script de démarrage Tomcat introuvable : $catalina"
}

$databaseReady = Test-NetConnection -ComputerName 127.0.0.1 -Port $MariaDbPort `
    -InformationLevel Quiet -WarningAction SilentlyContinue
$mariaDbProcess = $null

if (-not $databaseReady) {
    $mariaDbLog = Join-Path $MariaDbData 'mariadb-startup.err'
    $mariaDbArguments = @(
        "--basedir=`"$MariaDbHome`""
        "--datadir=`"$MariaDbData`""
        "--port=$MariaDbPort"
        '--bind-address=127.0.0.1'
        "--log-error=`"$mariaDbLog`""
    )
    Write-Host "Démarrage de MariaDB depuis '$MariaDbHome' avec les données '$MariaDbData'..."
    $mariaDbProcess = Start-Process -FilePath $mariaDbServer -ArgumentList $mariaDbArguments `
        -WorkingDirectory $MariaDbHome -WindowStyle Hidden -PassThru
}
else {
    Write-Host "MariaDB répond déjà sur le port $MariaDbPort; aucun deuxième serveur ne sera lancé."
}

for ($attempt = 0; -not $databaseReady -and $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    $databaseReady = Test-NetConnection -ComputerName 127.0.0.1 -Port $MariaDbPort `
        -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $databaseReady -and $null -ne $mariaDbProcess) {
        $mariaDbProcess.Refresh()
        if ($mariaDbProcess.HasExited) {
            break
        }
    }
}

if (-not $databaseReady) {
    if (Test-Path -LiteralPath $mariaDbLog -PathType Leaf) {
        Get-Content -LiteralPath $mariaDbLog -Tail 30 | Write-Host
    }
    throw "MariaDB n'écoute pas sur 127.0.0.1:$MariaDbPort. Vérifiez son journal et le dossier de données."
}

Write-Host "MariaDB est disponible sur le port $MariaDbPort."
Write-Host "Démarrage de Tomcat au premier plan. Utilisez Ctrl+C pour l'arrêter."
Push-Location (Join-Path $TomcatHome 'bin')
try {
    $LASTEXITCODE = 0
    & $catalina run
    if ($LASTEXITCODE -ne 0) {
        throw "Tomcat s'est arrêté avec le code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
