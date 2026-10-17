# frames/ 里的 JPEG 序列 → 带背景音的 mp4（1080p 主片 + 720p 分享版），并抽封面、切一张 README 用的 GIF。
# 背景音由 music.mjs 现场合成，不依赖任何素材文件。
#
#   pwsh -File build.ps1            只出片到 out/
#   pwsh -File build.ps1 -Deliver   额外把要进仓库的那几个文件复制到本目录
param(
  [switch]$Deliver,
  [double]$GifStart = 13.4,
  [double]$GifLen = 7.4
)
$ErrorActionPreference = 'Stop'
$ff = Join-Path $PSScriptRoot 'node_modules\@ffmpeg-installer\win32-x64\ffmpeg.exe'
if (-not (Test-Path $ff)) { throw "找不到 ffmpeg: $ff（先 pnpm install）" }

$out = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$frames = Join-Path $PSScriptRoot 'frames\f%05d.jpg'
$theme = Join-Path $out 'theme.wav'
if (-not (Get-ChildItem (Join-Path $PSScriptRoot 'frames') -Filter '*.jpg' -ErrorAction SilentlyContinue)) {
  throw 'frames/ 是空的：先跑 node capture.mjs 0 53 30 frames 95'
}

Write-Host '== 背景音 =='
& node (Join-Path $PSScriptRoot 'music.mjs') $theme

function Encode([string]$name, [string]$scale, [string]$crf) {
  $target = Join-Path $out $name
  $silent = Join-Path $env:TEMP ('watchplay-silent-' + $name)
  $vf = @()
  if ($scale) { $vf = @('-vf', "scale=$scale`:flags=lanczos") }
  & $ff -y -hide_banner -loglevel warning -framerate 30 -i $frames @vf `
    -c:v libx264 -preset slow -crf $crf -pix_fmt yuv420p $silent
  & $ff -y -hide_banner -loglevel warning -i $silent -i $theme -map 0:v -map 1:a `
    -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart $target
  Remove-Item $silent -Force
}

Write-Host '== 1080p =='
Encode 'WatchPlay-intro-1080p.mp4' '' 20
Write-Host '== 720p =='
Encode 'WatchPlay-intro-720p.mp4' '1280:720' 22

Write-Host '== 封面 =='
& $ff -y -hide_banner -loglevel warning -i (Join-Path $out 'WatchPlay-intro-1080p.mp4') -ss 15.6 -frames:v 1 (Join-Path $out 'poster.png')

Write-Host '== GIF =='
& $ff -y -hide_banner -loglevel warning -ss $GifStart -t $GifLen -i (Join-Path $out 'WatchPlay-intro-1080p.mp4') `
  -filter_complex "[0:v]fps=15,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" `
  -loop 0 (Join-Path $out 'preview.gif')

if ($Deliver) {
  Copy-Item (Join-Path $out 'WatchPlay-intro-1080p.mp4') $PSScriptRoot -Force
  Copy-Item (Join-Path $out 'poster.png') $PSScriptRoot -Force
  Copy-Item (Join-Path $out 'preview.gif') $PSScriptRoot -Force
  Write-Host '已复制 1080p / 封面 / GIF 到 docs/video/'
}

Get-ChildItem $out | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,2)}} | Format-Table -AutoSize
Write-Host '完成'
