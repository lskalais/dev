$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '..\src\main\webapp'
$files = Get-ChildItem -Path $root -Filter '*.xhtml' -File | Sort-Object Name
$fontLinks = @(
    '    <link rel="preconnect" href="https://fonts.googleapis.com"/>',
    '    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin="anonymous"/>',
    '    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Outfit:wght@200;300;400;500&amp;family=Albert+Sans:wght@400;500;600&amp;display=swap"/>'
) -join "`r`n"
$anchor = '    <h:outputStylesheet library="css" name="app.css"/>'
foreach ($file in $files) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    if ($content -match 'fonts\.googleapis\.com') {
        Write-Host "deja present : $($file.Name)"
        continue
    }
    if (-not $content.Contains($anchor)) {
        Write-Host "ancre absente : $($file.Name)"
        continue
    }
    $content = $content.Replace($anchor, $fontLinks + "`r`n" + $anchor)
    Set-Content -LiteralPath $file.FullName -Value $content -NoNewline -Encoding UTF8
    Write-Host "polices ajoutees : $($file.Name)"
}
