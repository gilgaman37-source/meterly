param(
  [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
  [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
  [string]$JavaBin = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin'
)

$ErrorActionPreference = 'Stop'
$smokeRoot = Join-Path $env:TEMP ('meterly-smoke-' + [guid]::NewGuid().ToString('N'))
$pgData = Join-Path $smokeRoot 'pgdata'
$pgOut = Join-Path $smokeRoot 'postgres.out.log'
$pgErr = Join-Path $smokeRoot 'postgres.err.log'
$backendOut = Join-Path $smokeRoot 'backend.out.log'
$backendErr = Join-Path $smokeRoot 'backend.err.log'
$backendProcess = $null
$postgresProcess = $null
$postgresStarted = $false

try {
  New-Item -ItemType Directory -Path $smokeRoot, $pgData -Force | Out-Null
  & (Join-Path $PostgresBin 'initdb.exe') -D $pgData -U meters -A trust --encoding=UTF8 --locale=C | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
  $postgresProcess = Start-Process -FilePath (Join-Path $PostgresBin 'postgres.exe') -ArgumentList '-D', $pgData, '-p', '55432', '-h', '127.0.0.1' -PassThru -WindowStyle Hidden -RedirectStandardOutput $pgOut -RedirectStandardError $pgErr
  $ready = $false
  for ($attempt = 0; $attempt -lt 40; $attempt += 1) {
    & (Join-Path $PostgresBin 'pg_isready.exe') -h 127.0.0.1 -p 55432 -U meters | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 250
  }
  if (-not $ready) { throw 'postgres start failed' }
  $postgresStarted = $true
  & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p 55432 -U meters meters
  if ($LASTEXITCODE -ne 0) { throw 'createdb failed' }

  Push-Location $smokeRoot
  & (Join-Path $JavaBin 'jar.exe') xf (Join-Path $env:USERPROFILE '.m2\repository\net\sourceforge\tess4j\tess4j\5.19.0\tess4j-5.19.0.jar') tessdata/eng.traineddata
  Pop-Location
  $uploadDir = Join-Path $smokeRoot 'uploads'
  New-Item -ItemType Directory -Path $uploadDir -Force | Out-Null

  $env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/meters'
  $env:DATABASE_USERNAME = 'meters'
  $env:DATABASE_PASSWORD = 'meters'
  $env:SERVER_PORT = '18080'
  $env:UPLOAD_DIRECTORY = $uploadDir
  $env:TESSDATA_PATH = Join-Path $smokeRoot 'tessdata'
  $env:JWT_SECRET = 'VGhpcy1pcy1hLXNtb2tlLXRlc3Qtc2VjcmV0LXRoYXQtaXMtbG9uZy1lbm91Z2g='

  $jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'backend\target') -Filter 'meter-monitor-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
  if (-not $jar) { throw 'backend jar not found; run mvn package first' }
  $backendProcess = Start-Process -FilePath (Join-Path $JavaBin 'java.exe') -ArgumentList '-jar', $jar.FullName -PassThru -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr

  $healthy = $false
  for ($attempt = 0; $attempt -lt 90; $attempt += 1) {
    if ($backendProcess.HasExited) { throw 'backend exited before becoming healthy' }
    try {
      $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 2
      if ($health.status -eq 'UP') { $healthy = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 500
  }
  if (-not $healthy) { throw 'backend health timeout' }

  $suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
  $password = 'correct-password-' + $suffix
  $registration = @{ username = 'smoke' + $suffix; email = 'smoke' + $suffix + '@example.com'; password = $password } | ConvertTo-Json
  $auth = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/auth/register' -Method Post -ContentType 'application/json' -Body $registration
  $login = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/auth/login' -Method Post -ContentType 'application/json' -Body (@{ login = $auth.email; password = $password } | ConvertTo-Json)
  if (-not $login.token) { throw 'login did not return JWT' }
  $headers = @{ Authorization = 'Bearer ' + $auth.token }

  $meter = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/meters' -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ serialNumber = 'SMOKE-' + $suffix; meterType = 'COLD_WATER'; description = 'Smoke meter'; region = 'Smoke region' } | ConvertTo-Json)
  $reading = Invoke-RestMethod -Uri ('http://127.0.0.1:18080/api/meters/' + $meter.id + '/readings') -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ value = 100; submittedAt = $null; meterReset = $false; resetReason = $null } | ConvertTo-Json)
  $history = Invoke-RestMethod -Uri ('http://127.0.0.1:18080/api/meters/' + $meter.id + '/readings') -Method Get -Headers $headers
  if ($history.totalElements -ne 1) { throw 'manual reading missing from history' }

  Add-Type -AssemblyName System.Drawing
  $imagePath = Join-Path $smokeRoot 'display.png'
  $bitmap = New-Object System.Drawing.Bitmap 900, 240
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  $graphics.Clear([System.Drawing.Color]::White)
  $font = New-Object System.Drawing.Font 'Consolas', 150, ([System.Drawing.FontStyle]::Bold)
  $graphics.DrawString('138.42', $font, [System.Drawing.Brushes]::Black, 125, 35)
  $graphics.Dispose(); $font.Dispose(); $bitmap.Save($imagePath, [System.Drawing.Imaging.ImageFormat]::Png); $bitmap.Dispose()

  $queuedBody = & curl.exe -sS -f -X POST -H ('Authorization: Bearer ' + $auth.token) -F ('image=@' + $imagePath + ';type=image/png') ('http://127.0.0.1:18080/api/meters/' + $meter.id + '/readings/image')
  if ($LASTEXITCODE -ne 0) { throw 'image upload failed' }
  $queued = $queuedBody | ConvertFrom-Json
  $ocr = $null
  for ($attempt = 0; $attempt -lt 60; $attempt += 1) {
    $ocr = Invoke-RestMethod -Uri ('http://127.0.0.1:18080/api/reading-requests/' + $queued.requestId) -Method Get -Headers $headers
    if ($ocr.status -in @('COMPLETED', 'FAILED', 'REQUIRES_CONFIRMATION')) { break }
    Start-Sleep -Milliseconds 500
  }
  if ($ocr.status -eq 'FAILED') { throw ('OCR failed: ' + ($ocr.validationErrors -join '; ')) }
  if ($ocr.status -eq 'REQUIRES_CONFIRMATION') {
    $confirmedBody = & curl.exe -sS -f -X PATCH -H ('Authorization: Bearer ' + $auth.token) -H 'Content-Type: application/json' --data '{"value":138.42}' ('http://127.0.0.1:18080/api/reading-requests/' + $queued.requestId + '/confirm')
    if ($LASTEXITCODE -ne 0) { throw 'OCR confirmation failed' }
    $ocr = $confirmedBody | ConvertFrom-Json
  }
  if ($ocr.status -ne 'COMPLETED') { throw ('unexpected OCR status: ' + $ocr.status) }

  $history = Invoke-RestMethod -Uri ('http://127.0.0.1:18080/api/meters/' + $meter.id + '/readings') -Method Get -Headers $headers
  $payments = Invoke-RestMethod -Uri ('http://127.0.0.1:18080/api/meters/' + $meter.id + '/payments') -Method Get -Headers $headers
  if ($history.totalElements -ne 2) { throw 'OCR reading missing from history' }
  if ($payments[-1].status -ne 'MISSING_TARIFF') { throw 'missing tariff status was not returned' }

  [pscustomobject]@{
    health = $health.status
    user = $auth.username
    meterId = $meter.id
    manualReading = $reading.value
    ocrStatus = $ocr.status
    ocrValue = $ocr.recognizedValue
    ocrConfidence = $ocr.ocrConfidence
    historyCount = $history.totalElements
    paymentStatus = $payments[-1].status
  } | ConvertTo-Json -Compress
} catch {
  Write-Output ('SMOKE_ERROR: ' + $_.Exception.Message)
  if (Test-Path $backendOut) { Get-Content $backendOut -Tail 80 }
  if (Test-Path $backendErr) { Get-Content $backendErr -Tail 80 }
  throw
} finally {
  if ($backendProcess -and -not $backendProcess.HasExited) {
    Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $backendProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
  }
  if ($postgresStarted) { & (Join-Path $PostgresBin 'pg_ctl.exe') -D $pgData -m fast -w stop | Out-Null }
  elseif ($postgresProcess -and -not $postgresProcess.HasExited) { Stop-Process -Id $postgresProcess.Id -Force -ErrorAction SilentlyContinue }
  $resolved = [IO.Path]::GetFullPath($smokeRoot)
  $tempPrefix = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'meterly-smoke-'))
  if ($resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
  }
}
