[CmdletBinding(PositionalBinding = $false)]
param(
  [ValidateSet('api','worker')][string]$Service = 'api',
  [string]$JdkHome = $env:YOUREN_JDK_HOME
)
$ErrorActionPreference = 'Stop'
# 仅为本次子进程选择独立JDK，不修改系统设置；已打包的服务分别运行。
if (-not $JdkHome) { $JdkHome = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.youren-tools/jdk-21.0.12.1+1' }
$javaExecutable=Join-Path $JdkHome 'bin/java.exe'
$jarFile=Join-Path $PSScriptRoot "$Service/target/youren-$Service-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $javaExecutable) -or -not (Test-Path -LiteralPath $jarFile)) { throw 'JDK或服务JAR不存在，请先执行backend/build.ps1 verify。' }
& $javaExecutable '-Dfile.encoding=UTF-8' -jar $jarFile
exit $LASTEXITCODE
