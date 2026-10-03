[CmdletBinding(PositionalBinding = $false)]
param(
  [string]$JdkHome = $env:YOUREN_JDK_HOME,
  [Parameter(Position = 0, ValueFromRemainingArguments = $true)][string[]]$MavenArguments
)
$ErrorActionPreference = 'Stop'
# 只修改本进程的环境；退出后恢复，系统及其他终端的默认Java不受影响。
if (-not $JdkHome) {
  $JdkHome = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '.youren-tools/jdk-21.0.12.1+1'
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/javac.exe'))) {
  throw '未找到JDK 21，请设置YOUREN_JDK_HOME或传入-JdkHome。'
}
$previousJava = $env:JAVA_HOME
$previousOptions = $env:JAVA_TOOL_OPTIONS
try {
  $env:JAVA_HOME = $JdkHome
  $env:JAVA_TOOL_OPTIONS = "$previousOptions -Dfile.encoding=UTF-8"
  if (-not $MavenArguments) { $MavenArguments = @('verify') }
  & (Join-Path $PSScriptRoot 'mvnw.cmd') -B -ntp -f (Join-Path $PSScriptRoot 'pom.xml') @MavenArguments
  $buildExitCode = $LASTEXITCODE
} finally {
  $env:JAVA_HOME = $previousJava
  $env:JAVA_TOOL_OPTIONS = $previousOptions
}
exit $buildExitCode
