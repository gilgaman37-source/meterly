$ErrorActionPreference = 'Stop'

$meterlyRoot = Split-Path -Parent $PSScriptRoot

& (Join-Path $PSScriptRoot 'Start-Postgres.ps1')
. (Join-Path $PSScriptRoot 'Activate.ps1')

Push-Location (Join-Path $meterlyRoot 'backend')
try {
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
