$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Activate.ps1')

Write-Host "`nInstalled versions:" -ForegroundColor Cyan
java -version
mvn -version
node --version
npm --version
tesseract --version | Select-Object -First 2
docker --version

Write-Host "`nMeterly local tools are ready. Start the database with Start-Postgres.ps1." -ForegroundColor Green
