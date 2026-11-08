param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Confidentia'),
    [switch]$SkipStart
)

$ErrorActionPreference = 'Stop'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $arguments = @(
            '-NoProfile',
            '-ExecutionPolicy', 'Bypass',
            '-File', ('"{0}"' -f $PSCommandPath),
            '-InstallRoot', ('"{0}"' -f $InstallRoot)
        )
        if ($SkipStart) {
            $arguments += '-SkipStart'
        }

        $powerShellPath = (Get-Process -Id $PID).Path
        $elevatedProcess = Start-Process -FilePath $powerShellPath `
            -ArgumentList $arguments -Verb RunAs -Wait -PassThru
        exit $elevatedProcess.ExitCode
    }
}

function Invoke-WingetInstall([string]$PackageId, [switch]$Interactive) {
    $arguments = @(
        'install', '--id', $PackageId, '--exact',
        '--accept-package-agreements', '--accept-source-agreements'
    )
    if ($Interactive) {
        $arguments += '--interactive'
    }
    else {
        $arguments += '--silent'
    }

    & winget @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "winget n'a pas pu installer $PackageId (code $LASTEXITCODE)."
    }
}

function Install-ApacheArchive(
    [string]$Url,
    [string]$ArchiveName,
    [string]$Destination,
    [string]$ExpectedDirectory
) {
    if (Test-Path -LiteralPath $Destination -PathType Container) {
        return
    }

    $temporaryArchive = Join-Path ([IO.Path]::GetTempPath()) $ArchiveName
    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N'))
    try {
        Invoke-WebRequest -Uri $Url -OutFile $temporaryArchive
        Expand-Archive -LiteralPath $temporaryArchive -DestinationPath $temporaryDirectory
        $extractedDirectory = Join-Path $temporaryDirectory $ExpectedDirectory
        if (-not (Test-Path -LiteralPath $extractedDirectory -PathType Container)) {
            throw "L'archive téléchargée ne contient pas le dossier attendu : $ExpectedDirectory"
        }
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        Copy-Item -Path (Join-Path $extractedDirectory '*') -Destination $Destination -Recurse -Force
    }
    finally {
        Remove-Item -LiteralPath $temporaryArchive -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Protect-File([string]$Path) {
    $userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path '/inheritance:r' '/grant:r' "*$($userSid):(F)" '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de restreindre les permissions du fichier $Path."
    }
}

function Protect-Directory([string]$Path) {
    $userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $Path '/inheritance:r' '/grant:r' "*$($userSid):(OI)(CI)(F)" '*S-1-5-18:(OI)(CI)(F)' '*S-1-5-32-544:(OI)(CI)(F)' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de restreindre les permissions du dossier $Path."
    }
}

Assert-Administrator

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw 'winget est requis. Installez le Gestionnaire de package Windows, puis relancez le script.'
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$configurationDirectory = Join-Path $env:ProgramData 'Confidentia'
$configurationPath = Join-Path $configurationDirectory 'confidentia.json'
$tomcatVersion = '10.1.61'
$mavenVersion = '3.9.16'
$tomcatHome = Join-Path $InstallRoot "apache-tomcat-$tomcatVersion"
$mavenHome = Join-Path $InstallRoot "apache-maven-$mavenVersion"
$tomcatUrl = "https://dlcdn.apache.org/tomcat/tomcat-10/v$tomcatVersion/bin/apache-tomcat-$tomcatVersion-windows-x64.zip"
$mavenUrl = "https://dlcdn.apache.org/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"

New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
New-Item -ItemType Directory -Path $configurationDirectory -Force | Out-Null
Protect-Directory $configurationDirectory

function Find-Java17 {
    $java = Get-ChildItem -Path (Join-Path $env:ProgramFiles 'Eclipse Adoptium') `
        -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\jdk-17[^\\]*\\bin\\java\.exe$' } |
        Select-Object -First 1
    if ($java) {
        return $java.FullName
    }
    return $null
}

$java17Path = Find-Java17
if (-not $java17Path) {
    Invoke-WingetInstall 'EclipseAdoptium.Temurin.17.JDK'
    $java17Path = Find-Java17
}
if (-not (Get-Service -Name MariaDB -ErrorAction SilentlyContinue)) {
    Write-Host 'L’installation MariaDB peut demander de définir le mot de passe administrateur root.'
    Invoke-WingetInstall 'MariaDB.Server' -Interactive
}

if (-not $java17Path) {
    throw 'Java 17 est introuvable après son installation.'
}
$env:JAVA_HOME = Split-Path (Split-Path $java17Path -Parent) -Parent
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"

Install-ApacheArchive $mavenUrl "apache-maven-$mavenVersion-bin.zip" $mavenHome "apache-maven-$mavenVersion"
Install-ApacheArchive $tomcatUrl "apache-tomcat-$tomcatVersion-windows-x64.zip" $tomcatHome "apache-tomcat-$tomcatVersion"
$maven = Join-Path $mavenHome 'bin\mvn.cmd'
$mariaDbClient = Get-ChildItem -Path (Join-Path $env:ProgramFiles 'MariaDB*') `
    -Filter mariadb.exe -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $mariaDbClient) {
    throw 'Le client mariadb.exe est introuvable après l’installation de MariaDB.'
}
$mariaDbClientPath = $mariaDbClient.FullName

$mariaDbService = Get-Service -Name MariaDB -ErrorAction SilentlyContinue
if (-not $mariaDbService) {
    throw 'Le service Windows MariaDB est introuvable. Vérifiez son installation avant de relancer le script.'
}
if ($mariaDbService.Status -ne 'Running') {
    Start-Service -Name $mariaDbService.Name
    $mariaDbService.WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
}

if (Test-Path -LiteralPath $configurationPath -PathType Leaf) {
    $configuration = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
    if ([string]$configuration.DB_PASSWORD -notmatch '^[0-9a-f]{64}$') {
        throw "Mot de passe DB_PASSWORD invalide dans $configurationPath."
    }
    try {
        $encryptionKey = [Convert]::FromBase64String([string]$configuration.APP_ENCRYPTION_KEY)
    }
    catch {
        throw "Clé APP_ENCRYPTION_KEY invalide dans $configurationPath."
    }
    if ($encryptionKey.Length -ne 32) {
        throw "APP_ENCRYPTION_KEY doit représenter 32 octets dans $configurationPath."
    }
}
else {
    $randomBytes = New-Object byte[] 32
    $passwordBytes = New-Object byte[] 32
    $randomGenerator = New-Object Security.Cryptography.RNGCryptoServiceProvider
    $randomGenerator.GetBytes($randomBytes)
    $randomGenerator.GetBytes($passwordBytes)
    $randomGenerator.Dispose()
    $databasePassword = -join ($passwordBytes | ForEach-Object { $_.ToString('x2') })
    $configuration = [ordered]@{
        DB_HOST = '127.0.0.1'
        DB_PORT = '3306'
        DB_NAME = 'confidentia'
        DB_USER = 'confidentia_app'
        DB_PASSWORD = $databasePassword
        DB_SCHEMA_MODE = 'update'
        APP_ENCRYPTION_KEY = [Convert]::ToBase64String($randomBytes)
        PRIVATE_STORAGE_DIR = (Join-Path $configurationDirectory 'private-storage')
    }

    $secureRootPassword = Read-Host 'Mot de passe root MariaDB défini pendant l’installation' -AsSecureString
    $rootPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureRootPassword)
    $defaultsFile = Join-Path $configurationDirectory ([Guid]::NewGuid().ToString('N') + '.cnf')
    $sqlFile = Join-Path $configurationDirectory ([Guid]::NewGuid().ToString('N') + '.sql')
    try {
        $rootPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($rootPasswordPointer)
        $escapedRootPassword = $rootPassword.Replace('\', '\\').Replace('"', '\"')
        [IO.File]::WriteAllText(
            $defaultsFile,
            "[client]`r`nuser=root`r`npassword=`"$escapedRootPassword`"`r`n",
            [Text.UTF8Encoding]::new($false)
        )
        Protect-File $defaultsFile
        $sql = @"
CREATE DATABASE IF NOT EXISTS confidentia CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'confidentia_app'@'localhost' IDENTIFIED BY '$($configuration.DB_PASSWORD)';
CREATE USER IF NOT EXISTS 'confidentia_app'@'127.0.0.1' IDENTIFIED BY '$($configuration.DB_PASSWORD)';
ALTER USER 'confidentia_app'@'localhost' IDENTIFIED BY '$($configuration.DB_PASSWORD)';
ALTER USER 'confidentia_app'@'127.0.0.1' IDENTIFIED BY '$($configuration.DB_PASSWORD)';
GRANT ALL PRIVILEGES ON confidentia.* TO 'confidentia_app'@'localhost';
GRANT ALL PRIVILEGES ON confidentia.* TO 'confidentia_app'@'127.0.0.1';
"@
        [IO.File]::WriteAllText($sqlFile, $sql, [Text.UTF8Encoding]::new($false))
        Protect-File $sqlFile
        & $mariaDbClientPath "--defaults-extra-file=$defaultsFile" '--batch' "--execute=source $sqlFile"
        if ($LASTEXITCODE -ne 0) {
            throw "La création de la base MariaDB a échoué (code $LASTEXITCODE)."
        }
        $configuration | ConvertTo-Json | Set-Content -LiteralPath $configurationPath -Encoding UTF8
        Protect-File $configurationPath
    }
    finally {
        if ($rootPasswordPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($rootPasswordPointer)
        }
        Remove-Item -LiteralPath $defaultsFile, $sqlFile -Force -ErrorAction SilentlyContinue
    }
}

Protect-File $configurationPath
$privateStorage = [string]$configuration.PRIVATE_STORAGE_DIR
New-Item -ItemType Directory -Path $privateStorage -Force | Out-Null
Protect-Directory $privateStorage

$setenvPath = Join-Path $tomcatHome 'bin\setenv.bat'
$environmentNames = @(
    'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD',
    'DB_SCHEMA_MODE', 'APP_ENCRYPTION_KEY', 'PRIVATE_STORAGE_DIR'
)
$setenvLines = foreach ($name in $environmentNames) {
    $value = [string]$configuration.$name
    if ($value.Contains('"') -or $value.Contains('%')) {
        throw "La valeur de $name contient un caractère non pris en charge par setenv.bat."
    }
    "set `"$name=$value`""
}
[IO.File]::WriteAllLines($setenvPath, $setenvLines, [Text.Encoding]::ASCII)
Protect-File $setenvPath

Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $maven '--batch-mode' '--no-transfer-progress' 'clean' 'package'
    if ($LASTEXITCODE -ne 0) {
        throw "La construction Maven a échoué (code $LASTEXITCODE)."
    }
    $war = Join-Path $PSScriptRoot 'target\confidentia.war'
    if (-not (Test-Path -LiteralPath $war -PathType Leaf)) {
        throw "Le WAR construit est introuvable : $war"
    }
    Copy-Item -LiteralPath $war -Destination (Join-Path $tomcatHome 'webapps\confidentia.war') -Force
}
finally {
    Pop-Location
}

Write-Host 'Installation terminée.'
Write-Host "Tomcat : $tomcatHome"
Write-Host 'MariaDB démarre en service Windows et reste actif après la fermeture de Tomcat.'
Write-Host 'Ne supprimez pas le fichier de configuration : il contient les identifiants et la clé de chiffrement.'
if (-not $SkipStart) {
    Write-Host 'Ouvrez http://localhost:8080/confidentia/ lorsque Tomcat est prêt.'
    Write-Host 'Tomcat démarre au premier plan; utilisez Ctrl+C pour l’arrêter.'
    Push-Location (Join-Path $tomcatHome 'bin')
    try {
        & (Join-Path $tomcatHome 'bin\catalina.bat') 'run'
        if ($LASTEXITCODE -ne 0) {
            throw "Tomcat s'est arrêté avec le code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Host "Pour démarrer plus tard : & '$tomcatHome\bin\catalina.bat' run"
}
