[CmdletBinding(SupportsShouldProcess = $true)]
param(
  [ValidateSet('dev')][string]$Environment = 'dev'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$composeEnv = Join-Path $repoRoot 'infra/.env'
$dbTarget = '127.0.0.1:5441/youren_dev'

# 迁移脚本只允许本机开发库，绝不提供测试库或远程库的目标参数。
if ($Environment -ne 'dev') { throw '只允许迁移本机开发数据库。' }
if ($PSCmdlet.ShouldProcess($dbTarget, '执行Flyway数据库迁移')) {
  if (-not (Test-Path -LiteralPath $composeEnv)) {
    throw '缺少 infra/.env；请先复制 .env.example 并配置本机密码。'
  }

  $passwordLine = Get-Content -LiteralPath $composeEnv -Encoding UTF8 |
    Where-Object { $_ -match '^\s*POSTGRES_PASSWORD\s*=' } |
    Select-Object -Last 1
  if (-not $passwordLine) { throw 'infra/.env未配置POSTGRES_PASSWORD。' }
  $password = ($passwordLine -split '=', 2)[1].Trim()
  if (($password.StartsWith('"') -and $password.EndsWith('"')) -or
      ($password.StartsWith("'") -and $password.EndsWith("'"))) {
    $password = $password.Substring(1, $password.Length - 2)
  }
  if ([string]::IsNullOrWhiteSpace($password) -or $password -eq 'local-example-only') {
    throw '请先将infra/.env中的示例密码替换为本机专用密码。'
  }

  $jdkHome = $env:YOUREN_JDK_HOME
  if (-not $jdkHome) { $jdkHome = Join-Path (Split-Path $repoRoot -Parent) '.youren-tools/jdk-21.0.12.1+1' }
  $java = Join-Path $jdkHome 'bin/java.exe'
  if (-not (Test-Path -LiteralPath $java)) { throw '找不到JDK 21；请设置YOUREN_JDK_HOME。' }

  # 迁移入口复用服务端配置验证；凭据只通过子进程环境传递，不放入命令行。
  $temporaryVariables = @(
    'JAVA_HOME', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD', 'SPRING_DATA_REDIS_URL'
  )
  $previousValues = @{}
  foreach ($name in $temporaryVariables) {
    $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  }
  try {
    $env:JAVA_HOME = $jdkHome
    $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5441/youren_dev'
    $env:SPRING_DATASOURCE_USERNAME = 'youren_dev'
    $env:SPRING_DATASOURCE_PASSWORD = $password
    $env:SPRING_DATA_REDIS_URL = 'redis://127.0.0.1:6381/0'

    $maven = Join-Path $PSScriptRoot 'mvnw.cmd'
    & $maven -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') -pl database -am install
    if ($LASTEXITCODE -ne 0) { throw '数据库迁移入口构建失败；请查看上方Maven错误。' }

    $classpathFile = Join-Path $PSScriptRoot 'database/target/migration-classpath.txt'
    & $maven -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') `
      -pl database org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath `
      "-Dmdep.outputFile=$classpathFile" '-Dmdep.includeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw '无法准备数据库迁移运行时依赖。' }

    $dependencyClasspath = Get-Content -LiteralPath $classpathFile -Raw -Encoding UTF8
    $migrationClasspath = @(
      (Join-Path $PSScriptRoot 'database/target/classes'),
      (Join-Path $PSScriptRoot 'shared/target/classes'),
      $dependencyClasspath.Trim()
    ) -join ';'
    & $java '-Dfile.encoding=UTF-8' '-cp' $migrationClasspath 'jp.youren.database.MigrationCommand' 'migrate'
    if ($LASTEXITCODE -ne 0) { throw '迁移失败；应用未启动。请检查本机数据库状态和迁移错误。' }
  }
  finally {
    foreach ($name in $temporaryVariables) {
      [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
    }
  }
}

if ($WhatIfPreference) {
  Write-Output "目标数据库：$dbTarget；未执行迁移。"
}
