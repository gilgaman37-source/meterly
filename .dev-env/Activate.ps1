$ErrorActionPreference = 'Stop'

$script:MeterlyRoot = Split-Path -Parent $PSScriptRoot
$script:JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$script:MavenHome = 'C:\tools\apache-maven-3.9.9'
$script:DockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
$script:TesseractHome = 'C:\Program Files\Tesseract-OCR'

$requiredPaths = @(
    (Join-Path $script:JdkHome 'bin\java.exe'),
    (Join-Path $script:MavenHome 'bin\mvn.cmd'),
    (Join-Path $script:DockerBin 'docker.exe'),
    (Join-Path $script:TesseractHome 'tesseract.exe'),
    (Join-Path $script:TesseractHome 'tessdata\eng.traineddata')
)

$missingPaths = $requiredPaths | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missingPaths) {
    throw "Development environment components were not found:`n$($missingPaths -join "`n")"
}

$env:JAVA_HOME = $script:JdkHome
$env:MAVEN_HOME = $script:MavenHome
$env:TESSDATA_PATH = Join-Path $script:TesseractHome 'tessdata'
$env:DATABASE_URL = 'jdbc:postgresql://localhost:5434/meters'
$env:DATABASE_USERNAME = 'meters'
$env:DATABASE_PASSWORD = 'meters'
$env:JWT_SECRET = 'VGhpcy1pcy1hLWxvY2FsLWRldmVsb3BtZW50LXNlY3JldC1mb3ItbWV0ZXJseQ=='
$env:UPLOAD_DIRECTORY = Join-Path $script:MeterlyRoot 'backend\data\uploads'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:3000,http://localhost:5173'
$env:NEXT_PUBLIC_API_URL = 'http://localhost:8080'

$pathEntries = @(
    (Join-Path $script:JdkHome 'bin'),
    (Join-Path $script:MavenHome 'bin'),
    $script:DockerBin,
    $script:TesseractHome
)
$env:Path = (($pathEntries + ($env:Path -split ';')) | Select-Object -Unique) -join ';'

New-Item -ItemType Directory -Force -Path $env:UPLOAD_DIRECTORY | Out-Null

Write-Host 'Meterly development environment activated.' -ForegroundColor Green
Write-Host "JAVA_HOME:      $env:JAVA_HOME"
Write-Host "TESSDATA_PATH:  $env:TESSDATA_PATH"
Write-Host "PostgreSQL:     localhost:5434/meters"
Write-Host "Backend:        http://localhost:8080"
Write-Host "Frontend:       http://localhost:3000"
