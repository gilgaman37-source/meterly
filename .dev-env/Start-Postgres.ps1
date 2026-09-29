$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Activate.ps1')

$dockerDesktop = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'

function Test-DockerEngine {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Join-Path $script:DockerBin 'docker.exe')
    $startInfo.Arguments = 'info --format "{{.ServerVersion}}"'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    if (-not $process.WaitForExit(3000)) {
        $process.Kill()
        $process.Dispose()
        return $false
    }

    $isReady = $process.ExitCode -eq 0
    $process.Dispose()
    return $isReady
}

if (-not (Test-DockerEngine)) {
    if (-not (Test-Path -LiteralPath $dockerDesktop)) {
        throw 'Docker Desktop is not installed.'
    }
    Write-Host 'Starting Docker Desktop and waiting for the engine...'
    if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
        Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
    }
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(110)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-DockerEngine) {
            $ready = $true
            break
        }
    }
    if (-not $ready) {
        throw 'Docker Desktop did not become ready within 120 seconds.'
    }
}

Push-Location $script:MeterlyRoot
try {
    docker compose up -d --wait postgres
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U meters -d postgres -c "ALTER ROLE meters WITH PASSWORD 'meters';" | Out-Null
    docker compose ps postgres
} finally {
    Pop-Location
}
