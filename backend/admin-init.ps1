[CmdletBinding(PositionalBinding = $false)]
param([string]$JdkHome = $env:YOUREN_JDK_HOME)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$localEnv = Join-Path $repoRoot 'infra/.env'
$jarFile = Join-Path $PSScriptRoot 'api/target/youren-api-0.1.0-SNAPSHOT.jar'

# 初始化只能使用开发机本地配置，首个账号由操作者在交互控制台输入凭据和MFA。
if (-not (Test-Path -LiteralPath $localEnv)) {
  throw '缺少 infra/.env；请先配置本机开发数据库。'
}
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) {
  $JdkHome = Join-Path (Split-Path $repoRoot -Parent) '.youren-tools/jdk-21.0.12.1+1'
}
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw '找不到JDK 21；请设置JAVA_HOME或YOUREN_JDK_HOME。' }

$passwordLine = Get-Content -LiteralPath $localEnv -Encoding UTF8 |
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

$temporaryVariables = @(
  'JAVA_HOME', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
  'SPRING_DATASOURCE_PASSWORD', 'SPRING_DATA_REDIS_URL', 'YOUREN_ADMIN_MFA_ENCRYPTION_KEY'
)
$previousValues = @{}
foreach ($name in $temporaryVariables) {
  $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
  if ([string]::IsNullOrWhiteSpace($env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY)) {
    $keyLine = Get-Content -LiteralPath $localEnv -Encoding UTF8 |
      Where-Object { $_ -match '^\s*YOUREN_ADMIN_MFA_ENCRYPTION_KEY\s*=' } |
      Select-Object -Last 1
    if ($keyLine) {
      $env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY = ($keyLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
    }
  }
  try {
    $keyBytes = [Convert]::FromBase64String($env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY)
    if ($keyBytes.Length -ne 32) { throw 'invalid key length' }
  } catch {
    throw '请在infra/.env设置本机专用的YOUREN_ADMIN_MFA_ENCRYPTION_KEY（32字节Base64密钥）。'
  }
  $env:JAVA_HOME = $JdkHome
  $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5441/youren_dev'
  $env:SPRING_DATASOURCE_USERNAME = 'youren_dev'
  $env:SPRING_DATASOURCE_PASSWORD = $password
  $env:SPRING_DATA_REDIS_URL = 'redis://127.0.0.1:6381/0'

  # 初始化入口随API一起打包，PropertiesLauncher只切换主类，不把口令放入命令行。
  & (Join-Path $PSScriptRoot 'mvnw.cmd') -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') -pl api -am install
  if ($LASTEXITCODE -ne 0) { throw '管理员初始化命令构建失败；请查看Maven错误。' }
  if (-not (Test-Path -LiteralPath $jarFile)) { throw '找不到管理员初始化运行包。' }
  & $java '-Dfile.encoding=UTF-8' '-Dloader.main=jp.youren.api.admin.AdminBootstrapCommand' `
    '-cp' $jarFile 'org.springframework.boot.loader.launch.PropertiesLauncher'
  if ($LASTEXITCODE -ne 0) { throw '管理员初始化失败；请检查迁移、密钥配置和本机数据库状态。' }
}
finally {
  foreach ($name in $temporaryVariables) {
    [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
  }
  $password = $null
}
