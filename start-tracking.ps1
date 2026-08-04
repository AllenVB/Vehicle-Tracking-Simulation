# VTS — tek komutla canlı takip başlat.
#
# Ne yapar:
#   1) Gerekli Docker servislerini ayağa kaldırır (İETT/simülatör/observability HARİÇ).
#   2) Gateway sağlıklı olana kadar bekler.
#   3) cloudflared "quick tunnel" açar (telefon için HTTPS adresi) ve adresi yakalar.
#   4) Tünel adresini gateway'e bildirir (haritadaki QR bu adresi kullanır).
#   5) Bilgisayarda haritayı açar. cloudflared açık kaldıkça (bu pencere kapanmadıkça)
#      telefon bağlanabilir. Kapatmak için bu pencerede Ctrl+C.
#
# Kullanım:  sağ tık > "Run with PowerShell"  ya da  .\start-tracking.ps1
#            (ya da start-tracking.bat'a çift tıkla)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Info($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "OK  $m" -ForegroundColor Green }
function Warn($m) { Write-Host "!!  $m" -ForegroundColor Yellow }

# Demoda gereken çekirdek servisler (grafana/prometheus/kafka-ui gibi port çakışanları dışarıda).
$services = @(
  'postgres','redis','kafka','kafka-init',
  'api-gateway','ingestion-service','processing-service',
  'notification-service','scheduler-service','stream-analytics'
)

Info "Docker servisleri baslatiliyor..."
docker compose up -d @services
if ($LASTEXITCODE -ne 0) { Warn "docker compose hata verdi. Docker Desktop acik mi?"; Read-Host "Cikmak icin Enter"; exit 1 }
Ok "Konteynerler ayakta."

Info "Gateway saglik bekleniyor (localhost:8080)..."
$healthy = $false
for ($i = 0; $i -lt 40; $i++) {
  try {
    $h = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3
    if ($h.status -eq 'UP') { $healthy = $true; break }
  } catch {}
  Start-Sleep -Seconds 3
}
if (-not $healthy) { Warn "Gateway 2 dk icinde UP olmadi; yine de devam ediliyor." } else { Ok "Gateway UP." }

# cloudflared kurulu mu?
$cf = Get-Command cloudflared -ErrorAction SilentlyContinue
if (-not $cf) {
  Warn "cloudflared kurulu degil. Kurmak icin:"
  Write-Host "    winget install --id Cloudflare.cloudflared" -ForegroundColor White
  Write-Host "Kurduktan sonra bu script'i tekrar calistir." -ForegroundColor White
  Read-Host "Cikmak icin Enter"; exit 1
}

Info "cloudflared tuneli aciliyor (telefon icin HTTPS adresi)..."
$outLog = Join-Path $env:TEMP 'vts-cloudflared.out.log'
$errLog = Join-Path $env:TEMP 'vts-cloudflared.err.log'
Remove-Item $outLog,$errLog -Force -ErrorAction SilentlyContinue
$proc = Start-Process cloudflared `
  -ArgumentList @('tunnel','--no-autoupdate','--url','http://localhost:8080') `
  -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden

# Tunel adresini logdan yakala (cloudflared adresi stderr/stdout'a basar).
$publicUrl = $null
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 1
  $txt = ((Get-Content $outLog,$errLog -ErrorAction SilentlyContinue) -join "`n")
  $m = [regex]::Match($txt, 'https://[a-z0-9-]+\.trycloudflare\.com')
  if ($m.Success) { $publicUrl = $m.Value; break }
}
if (-not $publicUrl) {
  Warn "Tunel adresi alinamadi. cloudflared loglari: $errLog"
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
  Read-Host "Cikmak icin Enter"; exit 1
}
Ok "Tunel adresi: $publicUrl"

# Adresi gateway'e bildir (haritadaki QR bunu kullanacak).
try {
  Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/track/config' `
    -ContentType 'application/json' -Body (@{ url = $publicUrl } | ConvertTo-Json)
  Ok "Tunel adresi gateway'e bildirildi."
} catch { Warn "Adres gateway'e bildirilemedi: $($_.Exception.Message)" }

Start-Process 'http://localhost:8080'

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host " HAZIR." -ForegroundColor Green
Write-Host " 1) Bilgisayarda harita otomatik acilir (sifre yok)." -ForegroundColor White
Write-Host " 2) Filo sekmesinden + ile arac ekle ve sifre ata; sag ustteki QR'i telefona tarat." -ForegroundColor White
Write-Host " 3) Telefonda acilan sayfada plaka + sifre ile giris yap." -ForegroundColor White
Write-Host " 4) Konum izni ver -> aracin canli haritada gorunur." -ForegroundColor White
Write-Host ""
Write-Host " Surucu adresi (elle de acabilirsin):" -ForegroundColor White
Write-Host "   $publicUrl/driver.html" -ForegroundColor Yellow
Write-Host ""
Write-Host " Bu pencere ACIK kaldigi surece tunel calisir. Durdurmak icin Ctrl+C." -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Green

# cloudflared kapanana (Ctrl+C ile bu script sonlanana) kadar bekle.
Wait-Process -Id $proc.Id
