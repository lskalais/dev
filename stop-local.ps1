param(
    [string]$MariaDbHome = 'C:\devtool\mariadb-13.0.2-winx64',
    [string]$MariaDbData = 'C:\devtool\mariadb-13.0.2-winx64\data13',
    [string]$TomcatHome = 'C:\devtool\apache-tomcat-10.1.61',
    [int]$MariaDbPort = 3306,
    [string]$MariaDbDefaultsFile = $env:MARIADB_DEFAULTS_FILE
)

$ErrorActionPreference = 'Stop'

if ($MariaDbPort -lt 1 -or $MariaDbPort -gt 65535) {
    throw "Port MariaDB invalide : $MariaDbPort"
}

if (-not (Test-Path -LiteralPath $TomcatHome -PathType Container)) {
    throw "Tomcat introuvable : $TomcatHome. Indiquez son dossier avec -TomcatHome."
}

$catalina = Join-Path $TomcatHome 'bin\catalina.bat'
if (-not (Test-Path -LiteralPath $catalina -PathType Leaf)) {
    throw "Script d'arrêt Tomcat introuvable : $catalina"
}

Write-Host 'Arrêt de Tomcat...'
$expectedTomcatHome = [IO.Path]::GetFullPath($TomcatHome)
$tomcatProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object {
        $_.CommandLine -and (
            $_.CommandLine.IndexOf(
                "-Dcatalina.base=`"$expectedTomcatHome`"",
                [StringComparison]::OrdinalIgnoreCase
            ) -ge 0 -or
            $_.CommandLine.IndexOf(
                "-Dcatalina.home=`"$expectedTomcatHome`"",
                [StringComparison]::OrdinalIgnoreCase
            ) -ge 0
        )
    })

if ($tomcatProcesses.Count -gt 0) {
    Push-Location (Join-Path $TomcatHome 'bin')
    try {
        & $catalina stop
        if ($LASTEXITCODE -ne 0) {
            throw "La commande d'arrêt de Tomcat a échoué avec le code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $tomcatStopped = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 1
        $remainingProcesses = @($tomcatProcesses | Where-Object {
            Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        })
        if ($remainingProcesses.Count -eq 0) {
            $tomcatStopped = $true
            break
        }
    }

    if (-not $tomcatStopped) {
        throw 'Tomcat est toujours actif après 30 secondes; MariaDB reste démarrée.'
    }
}
else {
    Write-Host 'Tomcat ne fonctionne pas; aucun serveur Tomcat ne sera arrêté.'
}

$mariaDbServer = Join-Path $MariaDbHome 'bin\mariadbd.exe'
$mariaDbAdmin = Join-Path $MariaDbHome 'bin\mariadb-admin.exe'
if (-not (Test-Path -LiteralPath $mariaDbServer -PathType Leaf)) {
    throw "Exécutable MariaDB introuvable : $mariaDbServer"
}
if (-not (Test-Path -LiteralPath $mariaDbAdmin -PathType Leaf)) {
    throw "Outil d'arrêt MariaDB introuvable : $mariaDbAdmin"
}

$databaseReady = Test-NetConnection -ComputerName 127.0.0.1 -Port $MariaDbPort `
    -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $databaseReady) {
    Write-Host "MariaDB n'écoute pas sur le port $MariaDbPort; rien à arrêter."
    exit 0
}

$expectedExecutable = [IO.Path]::GetFullPath($mariaDbServer)
$expectedData = [IO.Path]::GetFullPath($MariaDbData)
$managedProcess = Get-CimInstance Win32_Process -Filter "Name = 'mariadbd.exe'" |
    Where-Object {
        $_.ExecutablePath -and
        [string]::Equals(
            [IO.Path]::GetFullPath($_.ExecutablePath),
            $expectedExecutable,
            [StringComparison]::OrdinalIgnoreCase
        ) -and
        $_.CommandLine -and
        $_.CommandLine.IndexOf("--datadir=`"$expectedData`"", [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $_.CommandLine.IndexOf("--port=$MariaDbPort", [StringComparison]::OrdinalIgnoreCase) -ge 0
    }

if (-not $managedProcess) {
    Write-Host "Le port $MariaDbPort est utilisé, mais pas par l'instance MariaDB locale attendue; elle n'a pas été arrêtée."
    exit 0
}

$mariaDbArguments = @()
if (-not [string]::IsNullOrWhiteSpace($MariaDbDefaultsFile)) {
    if (-not (Test-Path -LiteralPath $MariaDbDefaultsFile -PathType Leaf)) {
        throw "Fichier d'options MariaDB introuvable : $MariaDbDefaultsFile"
    }
    $mariaDbArguments += "--defaults-extra-file=$MariaDbDefaultsFile"
}
$mariaDbArguments += @(
    '--protocol=tcp'
    '--host=127.0.0.1'
    "--port=$MariaDbPort"
    'shutdown'
)

Write-Host "Arrêt de MariaDB sur le port $MariaDbPort..."
& $mariaDbAdmin @mariaDbArguments
if ($LASTEXITCODE -ne 0) {
    throw "L'arrêt gracieux de MariaDB a échoué avec le code $LASTEXITCODE. Vérifiez les droits dans le fichier d'options MariaDB."
}

for ($attempt = 0; $attempt -lt 30; $attempt++) {
    Start-Sleep -Seconds 1
    $databaseReady = Test-NetConnection -ComputerName 127.0.0.1 -Port $MariaDbPort `
        -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $databaseReady) {
        Write-Host 'MariaDB est arrêté.'
        exit 0
    }
}

throw "MariaDB écoute toujours sur le port $MariaDbPort après 30 secondes."
