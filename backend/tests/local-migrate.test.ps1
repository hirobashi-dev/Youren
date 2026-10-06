$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'migrate.ps1'

# WhatIf只能预览固定的本机开发库目标，不得触发Maven、Java或数据库写入。
$result = & $scriptPath -WhatIf 2>&1
$invokeSucceeded = $?
$output = $result | Out-String
if (-not $invokeSucceeded) {
  throw "迁移预览应成功，实际执行失败：$output"
}
if ($output -notmatch '127\.0\.0\.1:5441/youren_dev' -or $output -notmatch '未执行迁移') {
  throw "迁移预览必须明确显示受限的开发库目标且不得执行迁移：$output"
}

Write-Output 'PASS: migration preview is local-only and performs no migration.'

# 非开发环境必须在启动构建和连接数据库前拒绝。
$invalidTargetAccepted = $true
try {
  $invalidTargetResult = & $scriptPath -Environment test -WhatIf 2>&1
  $invalidTargetAccepted = $?
  $invalidTargetOutput = $invalidTargetResult | Out-String
}
catch {
  $invalidTargetAccepted = $false
  $invalidTargetOutput = $_.ToString()
}
if ($invalidTargetAccepted) {
  throw "迁移脚本不应接受测试环境目标：$invalidTargetOutput"
}
Write-Output 'PASS: non-development database targets are rejected.'
