# Windows本机开发环境手册

本文把本仓库的 PostgreSQL、Redis、Java API、Android模拟器和Expo Metro串成一套可重复的本机开发环境。开发数据库卷会保留数据；测试数据库与开发数据库分开。所有示例只面向本机开发，不连接生产环境。

## 环境组成与端口

| 服务             | 本机地址                              | 用途                              | 生命周期                         |
| ---------------- | ------------------------------------- | --------------------------------- | -------------------------------- |
| PostgreSQL开发库 | `127.0.0.1:5441`，数据库`youren_dev`  | API开发数据                       | Docker卷持久保留                 |
| PostgreSQL测试库 | `127.0.0.1:5442`，数据库`youren_test` | 自动化测试专用；不要让开发API连接 | 按测试需要启动，数据可被测试清理 |
| Redis开发服务    | `127.0.0.1:6381`                      | API开发缓存/限流                  | 容器停止后数据可丢弃             |
| Redis测试服务    | `127.0.0.1:6382`                      | 自动化测试专用                    | 按测试需要启动                   |
| Java API         | `127.0.0.1:3000`                      | 手机和管理端访问                  | 终端中运行，Ctrl+C停止           |
| Expo Metro       | `localhost:8083`                      | 向Android开发客户端提供JS包       | 终端中运行，Ctrl+C停止           |
| Android模拟器    | Android Studio AVD                    | 手机UI和SecureStore现场验证       | 模拟器窗口运行期间可用           |

Compose只把数据库和Redis端口绑定在loopback。API也建议绑定`127.0.0.1`；模拟器用`adb reverse`转发API请求，不需要向局域网开放服务。

## 1. 首次安装和版本确认

需要安装Docker Desktop、Node.js 22.23.3/npm、JDK 21、Android Studio（含SDK、Platform Tools、Emulator）和PowerShell 7。安装Android Studio后，在Device Manager创建或下载一个Android 14/API 34模拟器。仓库之前使用的AVD名为`Pixel_3a_API_34`；本机名称可不同。

在仓库根目录打开PowerShell，确认版本与工具可见：

```powershell
node --version
npm --version
java -version
docker version
docker compose version
adb version
emulator -version
```

Node应为`v22.23.3`，npm建议使用仓库声明的`10.5.0`，JDK主版本应为21，Docker命令应能连接Docker Desktop。若`adb`或`emulator`找不到，先按下一节配置Android SDK路径，再重新打开终端。

### 可选：设置当前Windows用户的永久环境变量

下面命令把JDK和Android SDK设为当前Windows用户的开发工具，并将工具目录幂等地加入用户PATH；不需要管理员权限。修改后关闭并重新打开PowerShell、Android Studio和VS Code，使新环境变量生效。仓库构建脚本优先使用`YOUREN_JDK_HOME`，并在构建子进程中设置`JAVA_HOME`。`java -version`按PATH顺序选择可执行文件；仅设置`JAVA_HOME`不会改变命令解析顺序，因此JDK21的`bin`目录必须排在旧Java目录前面。

先确认JDK实际安装位置。本机当前使用`C:\pleiades\java\21`；如果JDK移动或其他电脑路径不同，只修改`$jdk`：

```powershell
$jdk = 'C:\pleiades\java\21'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe')) -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw "JDK路径无效：$jdk" }
if (-not (Test-Path (Join-Path $sdk 'platform-tools\adb.exe'))) { throw "Android SDK路径不存在：$sdk" }

[Environment]::SetEnvironmentVariable('YOUREN_JDK_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$pathEntries = @($userPath -split ';' | Where-Object { $_ })
$additions = @((Join-Path $jdk 'bin'), (Join-Path $sdk 'platform-tools'), (Join-Path $sdk 'emulator'))
foreach ($entry in $additions) {
  if (-not ($pathEntries | Where-Object { $_.TrimEnd('\') -ieq $entry.TrimEnd('\') })) {
    $pathEntries += $entry
  }
}
$pathEntries = @($additions + $pathEntries | Select-Object -Unique)
[Environment]::SetEnvironmentVariable('Path', ($pathEntries -join ';'), 'User')
```

确认Node安装器或版本管理器也把Node 22.23.3放进当前用户PATH。避免用`setx PATH`覆盖或截断已有PATH。若不希望永久改变量，可只在当前PowerShell中运行：

```powershell
$env:YOUREN_JDK_HOME = 'C:\pleiades\java\21'
$env:JAVA_HOME = $env:YOUREN_JDK_HOME
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:JAVA_HOME\bin;$env:PATH"
```

需要在**新开的PowerShell**里验证永久设置：

```powershell
$env:YOUREN_JDK_HOME
$env:ANDROID_HOME
& (Join-Path $env:YOUREN_JDK_HOME 'bin/java.exe') -version
Get-Command java -All | Select-Object -ExpandProperty Source
java -version
adb version
emulator -version
```

`java -version`应显示JDK21；若仍显示旧版本，检查JDK21的`bin`是否在用户PATH前部，并在新终端重试。仓库`build.ps1`、`run.ps1`和`migrate.ps1`使用`YOUREN_JDK_HOME`；本机若将JDK安装在其他位置，应同步更新该变量。

## 2. 安装仓库依赖（首次或锁文件更新后）

从仓库根目录执行。`npm ci`严格按`package-lock.json`安装，通常不需要每天重复：

```powershell
npm ci
```

首次Java构建会使用Maven Wrapper下载固定版本的Maven及项目依赖；需要网络。JDK路径不对时，先设置`YOUREN_JDK_HOME`，不要为了本项目替换其他Java项目的工具链。

## 3. 配置并启动开发数据库与Redis

### 首次配置密码

`infra/.env`是本机忽略文件，不要提交或覆盖已有文件。若不存在，复制模板并在编辑器中把示例值替换成密码管理器生成的本机随机密码：

```powershell
if (-not (Test-Path 'infra/.env')) { Copy-Item 'infra/.env.example' 'infra/.env' }
notepad.exe infra/.env
```

文件中保留`POSTGRES_PASSWORD=<本机随机密码>`。首次管理员初始化前，再添加`YOUREN_ADMIN_MFA_ENCRYPTION_KEY=<本机32字节Base64密钥>`；该密钥用于保护管理员MFA种子，后续启动API也必须使用同一个密钥。建议使用安全随机生成器，不要提交该文件。迁移和初始化脚本读取这些本机值时不会把密码或密钥打印出来。PostgreSQL首次初始化后，Compose卷内已保存数据库角色；之后单纯修改`.env`不会更改已有数据库密码。若已初始化过，应保留原密码，或另行执行明确的本机改密操作；不要删除卷来“重置”。

### 每次开机后启动

先启动Docker Desktop并等待Engine就绪，然后在仓库根目录启动**开发**数据库和Redis：

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres-dev redis-dev
docker compose --env-file infra/.env -f infra/compose.yaml ps
```

等待两个服务状态变为`healthy`。也可分别检查：

```powershell
docker compose --env-file infra/.env -f infra/compose.yaml exec -T postgres-dev pg_isready -U youren_dev -d youren_dev
docker compose --env-file infra/.env -f infra/compose.yaml exec -T redis-dev redis-cli ping
```

预期PostgreSQL显示`accepting connections`，Redis返回`PONG`。开发API只连`5441/youren_dev`和`6381`。不要把`postgres-test`或`redis-test`当开发数据源；后续测试可能清理测试schema。不要对日常停止使用`down -v`，它会删除数据库卷。

首次初始化后，每次Windows重启只需启动Docker Desktop并重跑`up -d`。若希望Docker Desktop随登录自动启动，可在Docker Desktop设置中开启；数据库容器仍由上面的Compose命令启动。

## 4. 初始化或升级开发数据库

API启动时**不会**自动改表。开发库首次初始化、代码加入新迁移后，在仓库根目录运行：

```powershell
.\backend\migrate.ps1 -WhatIf
.\backend\migrate.ps1
```

第一条只预览固定目标`127.0.0.1:5441/youren_dev`，不构建、不连接、不写数据库。确认目标正确后才执行第二条。脚本拒绝非`dev`目标，从`infra/.env`读取数据库密码，通过临时进程环境传给迁移JVM，完成后恢复调用Shell原有环境变量；它不会对测试库或远程数据库执行迁移。脚本会构建并安装共享/数据库模块到本机Maven缓存，然后运行`MigrationCommand migrate`；首次运行可能需要下载Maven插件和依赖。

只验证脚本的预览与拒绝边界（不会运行数据库迁移）：

```powershell
powershell.exe -NoProfile -File backend/tests/local-migrate.test.ps1
```

成功时最后输出类似`{"status":"ok","operation":"migrate"}`。若开发库已有当前迁移，重复运行应为无变更成功。任何失败都不要继续启动API；先检查Docker健康、`.env`密码和目标端口。此脚本不用于接续旧库；`audit-legacy`/`adopt-legacy`属于另一个需备份和人工确认的操作，不要用于此本机新库。

### 首次管理员初始化（仅运行一次）

管理员MFA加密密钥必须长期保存在未提交的`infra/.env`中。若还未生成，可在仓库根目录用PowerShell生成并写入文件；命令不会输出密钥：

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$key = [Convert]::ToBase64String($bytes)
if ((Get-Content infra/.env) -match '^YOUREN_ADMIN_MFA_ENCRYPTION_KEY=') { throw '密钥已存在，不要覆盖已有管理员密钥。' }
Add-Content -LiteralPath infra/.env -Value "YOUREN_ADMIN_MFA_ENCRYPTION_KEY=$key" -Encoding utf8
$key = $null
```

确保开发数据库已启动并迁移，然后在可交互的PowerShell 7终端执行：

```powershell
pwsh -NoProfile -File .\backend\admin-init.ps1
```

命令会提示输入管理员登录名、至少16位口令，并在控制台短暂显示一次TOTP二维码URI；把URI导入认证器后输入六位验证码。命令不接受命令行凭据、不会打印口令或MFA种子，也不创建默认账户。成功后首管理员已存在，再次执行会安全拒绝。备份`YOUREN_ADMIN_MFA_ENCRYPTION_KEY`到本机密码管理器；密钥丢失会使已存MFA种子无法解密，切勿为“修复”而随意替换。

## 5. 构建并启动Java API

API默认绑定`127.0.0.1`；第5节设置的`SERVER_ADDRESS`会被`application.yml`读取。部署到容器或其他网络环境时，必须按部署网络显式设置监听地址。

首次启动、后端源码更新或迁移后，打开一个专用PowerShell窗口。在仓库根目录构建API及其模块：

```powershell
.\backend\build.ps1 -pl api -am verify
```

该命令会编译并运行对应Java单元/集成验证，集成测试需要Docker。只在源码未变且JAR已构建时可跳过构建步骤。

认证联调使用隔离的`auth-test`测试收件箱，不会发送真实邮件。每个API进程生成两把彼此独立的临时密钥；不要共用、提交或粘贴到聊天中。`.env`密码允许含引号，下面会去掉包裹引号后传给Java：

```powershell
function New-LocalAuthSecret {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$env:YOUREN_AUTH_CHALLENGE_SECRET = New-LocalAuthSecret
$env:YOUREN_AUTH_TEST_INBOX_KEY = New-LocalAuthSecret
$env:SPRING_PROFILES_ACTIVE = 'auth-test'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5441/youren_dev'
$env:SPRING_DATASOURCE_USERNAME = 'youren_dev'
$passwordLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^\s*POSTGRES_PASSWORD\s*=' } | Select-Object -Last 1
$env:SPRING_DATASOURCE_PASSWORD = (($passwordLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
$env:SPRING_DATA_REDIS_URL = 'redis://127.0.0.1:6381/0'
$env:YOUREN_MEDIA_ROOT = Join-Path (Get-Location).Path '.local/media-private'
$mfaKeyLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^YOUREN_ADMIN_MFA_ENCRYPTION_KEY=' } | Select-Object -Last 1
if (-not $mfaKeyLine) { throw 'infra/.env 中没有找到管理员 MFA 密钥' }
$env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY = (($mfaKeyLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '3000'
$repoRoot = (Get-Location).Path
$java = Join-Path $env:YOUREN_JDK_HOME 'bin/java.exe'
$apiJar = Join-Path $repoRoot 'backend/api/target/youren-api-0.1.0-SNAPSHOT.jar'
New-Item -ItemType Directory -Force '.local' | Out-Null
$arguments = "-Dfile.encoding=UTF-8 -jar `"$apiJar`""
$apiProcess = Start-Process -FilePath $java -ArgumentList $arguments `
  -WorkingDirectory $repoRoot -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $repoRoot '.local/api.stdout.log') `
  -RedirectStandardError (Join-Path $repoRoot '.local/api.stderr.log')

$ready = $null
$deadline = (Get-Date).AddSeconds(30)
do {
  try {
    $ready = Invoke-RestMethod 'http://127.0.0.1:3000/health/ready' -TimeoutSec 2
    if ($ready.status -eq 'ok') { break }
  } catch { }
  Start-Sleep -Seconds 1
} while ((Get-Date) -lt $deadline -and -not $apiProcess.HasExited)
if (-not $ready -or $ready.status -ne 'ok') {
  Get-Content '.local/api.stderr.log' -Tail 40
  throw "API未就绪。进程ID：$($apiProcess.Id)；检查上方日志。"
}
Write-Output "API已就绪；进程ID：$($apiProcess.Id)；日志位于.local/api.*.log"
```

这里用`Start-Process`在后台启动JDK中的Java，API子进程继承当前PowerShell里的临时配置；当前窗口仍保留测试收件箱密钥，之后可直接读取验证码。密码不会出现在Java命令行。标准输出/错误分别写入被Git忽略的`.local`日志。手动前台启动也可用`.\backend\run.ps1 -Service api`，但该命令会占住当前窗口，不适合同时在同一窗口读取测试邮箱。

API正常启动后可在当前窗口或其他终端确认：

```powershell
Invoke-RestMethod 'http://127.0.0.1:3000/health/live'
Invoke-RestMethod 'http://127.0.0.1:3000/health/ready'
```

两者都应返回`status: ok`。`ready`失败时，先检查数据库和Redis健康状态。新的API进程意味着测试收件箱密钥也更新；应在启动它的PowerShell窗口读取验证码。

### 快速前台启动（游客浏览与移动端验收）

若只验证游客首页、会话恢复与服务不可用/重试，不需要测试收件箱时，可在专用PowerShell窗口设置开发库连接后以前台方式运行`run.ps1`。`run.ps1`不会自动读取`infra/.env`；密码从文件载入当前进程，不会打印到终端：

```powershell
$passwordLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^\s*POSTGRES_PASSWORD\s*=' } | Select-Object -Last 1
if (-not $passwordLine) { throw 'infra/.env 中没有找到 POSTGRES_PASSWORD' }
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5441/youren_dev'
$env:SPRING_DATASOURCE_USERNAME = 'youren_dev'
$env:SPRING_DATASOURCE_PASSWORD = (($passwordLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
$env:SPRING_DATA_REDIS_URL = 'redis://127.0.0.1:6381/0'
$env:YOUREN_MEDIA_ROOT = Join-Path (Get-Location).Path '.local/media-private'
$mfaKeyLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^YOUREN_ADMIN_MFA_ENCRYPTION_KEY=' } | Select-Object -Last 1
if (-not $mfaKeyLine) { throw 'infra/.env 中没有找到管理员 MFA 密钥' }
$env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY = (($mfaKeyLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
pwsh -NoProfile -File .\backend\run.ps1
```

看到`{"event":"started","service":"api"}`后保持窗口运行。停止时按`Ctrl+C`；重启时，在仍保留上述变量的同一个窗口再次运行最后一行。

到期帖子和图片清理、24小时未绑定媒体回收及72小时待审告警由独立worker执行。API启动后，在另一个PowerShell窗口用同一开发库、Redis、媒体目录和MFA密钥环境启动worker：

```powershell
$passwordLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^\s*POSTGRES_PASSWORD\s*=' } | Select-Object -Last 1
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5441/youren_dev'
$env:SPRING_DATASOURCE_USERNAME = 'youren_dev'
$env:SPRING_DATASOURCE_PASSWORD = (($passwordLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
$env:SPRING_DATA_REDIS_URL = 'redis://127.0.0.1:6381/0'
$env:YOUREN_MEDIA_ROOT = Join-Path (Get-Location).Path '.local/media-private'
$mfaKeyLine = Get-Content 'infra/.env' | Where-Object { $_ -match '^YOUREN_ADMIN_MFA_ENCRYPTION_KEY=' } | Select-Object -Last 1
if (-not $mfaKeyLine) { throw 'infra/.env 中没有找到管理员 MFA 密钥' }
$env:YOUREN_ADMIN_MFA_ENCRYPTION_KEY = (($mfaKeyLine -split '=', 2)[1]).Trim().Trim('"').Trim("'")
pwsh -NoProfile -File .\backend\run.ps1 -Service worker
```

worker每分钟分批处理；停止后再次启动会重新领取停机前未完成的删除任务。待审告警写入数据库outbox并保持内容待审，审核台仍需人工操作。

## 6. 启动Android模拟器并转发API

可在Android Studio的Device Manager点击AVD旁的启动按钮；或在单独终端执行（将名称替换为本机AVD名称）：

```powershell
emulator -avd Pixel_3a_API_34
```

启动后确认ADB设备：

```powershell
adb devices -l
```

从输出选中`emulator-####`设备，然后把模拟器本机的API端口安全转发到电脑的loopback端口：

```powershell
$device = 'emulator-5554' # 替换成adb devices显示的设备序列号
adb -s $device reverse tcp:3000 tcp:3000
adb -s $device reverse --list
```

这样API可只监听`127.0.0.1`，不需要开放给局域网。模拟器重启后重新执行`adb reverse`。`10.0.2.2:3000`是另一种模拟器访问宿主机的方式，但要让API绑定外部接口；本手册优先使用loopback转发。

## 7. 启动手机端Metro

新开一个终端，进入仓库根目录。API地址与ADB转发配对，使用`127.0.0.1`：

```powershell
$env:EXPO_PUBLIC_API_BASE_URL = 'http://127.0.0.1:3000'
$env:NODE_OPTIONS = '--dns-result-order=ipv4first'
npm run start -w @youren/mobile -- --localhost --port 8083
```

Metro终端保持打开。在模拟器中启动Youren开发客户端并载入项目；若第一次构建开发客户端，另开终端执行：

```powershell
npm run android -w @youren/mobile
```

Android模拟器打开应用后，可从游客首页检查连接状态。每次新开Metro终端都要重新设置`EXPO_PUBLIC_API_BASE_URL`；该变量只暴露本机API地址，不含密钥。验证码、HMAC密钥、收件箱密钥和访问令牌绝不放进`EXPO_PUBLIC_*`变量。

## 8. 手动验证注册验证码（可选）

App的`POST /v1/auth/email-code`成功响应含`challengeId`。在开发客户端调试网络面板或调试代理中取得该ID；只取ID，不要记录验证码或认证头。然后在**启动API的同一个PowerShell窗口**（密钥变量仍在该窗口）执行：

```powershell
$challengeId = '填入本次请求的challenge UUID'
$inbox = Invoke-RestMethod -Uri "http://127.0.0.1:3000/__test/mail/$challengeId" `
  -Headers @{ 'X-Test-Inbox-Key' = $env:YOUREN_AUTH_TEST_INBOX_KEY }
$inbox.code
```

把终端显示的短效测试码手动输入模拟器；不要把它贴到聊天、提交到Git或写进日志。测试收件箱只存在API进程内存，重启API后旧码不可读取。端点只允许主机loopback并要求独立密钥；Android模拟器不得直接读取该端点。此流程验证的是隔离收件箱，不是真实邮件投递。

## 9. 每日启动清单

Windows重启后按顺序执行：

1. 启动Docker Desktop。
2. 仓库根目录运行`docker compose --env-file infra/.env -f infra/compose.yaml up -d postgres-dev redis-dev`，确认`ps`中两个服务健康。
3. 如有新数据库迁移，先运行`.\backend\migrate.ps1 -WhatIf`核对目标，再运行`.\backend\migrate.ps1`。
4. 新开API PowerShell终端，按第5节生成密钥、配置进程变量并启动后台Java；保留该终端以便安全读取验证码，确认`/health/ready`为200。
5. 在Device Manager启动Android模拟器，执行`adb reverse tcp:3000 tcp:3000`。
6. 启动Metro专用终端，设置API base URL，执行`npm run start -w @youren/mobile -- --localhost --port 8083`。

只要电脑不重启、终端与模拟器不关闭，Docker服务、API、Metro和模拟器可一直供开发会话使用；不需要每次重建数据库或重装客户端。

## 10. 停止与保留数据

- Metro终端按Ctrl+C；API若以前台`run.ps1`方式启动则按Ctrl+C，若按本手册后台方式启动则在启动API的同一个PowerShell窗口执行`Stop-Process -Id $apiProcess.Id`。不要按名称结束所有`java.exe`，以免影响其他项目；关闭该窗口会丢失PID变量，但不会自动关闭后台Java进程。
- API停止后清除当前Shell中的测试凭据：`Remove-Item Env:YOUREN_AUTH_CHALLENGE_SECRET, Env:YOUREN_AUTH_TEST_INBOX_KEY, Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue`。
- 关闭Android模拟器窗口；不需要卸载开发客户端。
- 停止数据库服务但保留卷：

  ```powershell
  docker compose --env-file infra/.env -f infra/compose.yaml stop postgres-dev redis-dev
  ```

- 下次用`up -d postgres-dev redis-dev`恢复原开发数据。
- 不要运行`docker compose down -v`或手动删除`postgres-dev`卷，除非明确要永久清空数据库并已备份。
- 可以关闭Docker Desktop；若经常开发，保持它运行能省去启动等待。

## 11. 常见问题

| 现象                       | 检查与处理                                                                                                                |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Compose提示密码变量缺失    | 确认仓库根目录有`infra/.env`，且包含非空`POSTGRES_PASSWORD`；用`--env-file infra/.env`启动Compose。                       |
| PostgreSQL容器不健康       | 查看`docker compose ... logs postgres-dev`；确认5441未被其他程序占用。已存在卷时，`.env`密码必须仍与数据库角色密码一致。  |
| 迁移报连接失败             | 先确认容器healthy及`pg_isready`成功；检查迁移固定目标为`127.0.0.1:5441/youren_dev`。迁移脚本不会自动切换数据库。          |
| API `/health/ready`返回503 | 检查API进程环境中的JDBC用户名/密码、Redis URL及开发容器状态；不要先改成测试库。                                           |
| 模拟器连不上API            | `adb devices -l`确认设备在线；重新执行`adb -s <设备序列号> reverse tcp:3000 tcp:3000`；确认电脑本机`/health/ready`为200。 |
| Metro打不开或地址错误      | 确认同一终端设置了`EXPO_PUBLIC_API_BASE_URL`，然后在客户端Reload；检查8083是否被其他Metro占用。                           |
| `adb`/`emulator`命令不存在 | 检查`ANDROID_HOME`及用户PATH包含`platform-tools`和`emulator`；保存环境变量后重新打开终端。                                |
| Java版本不匹配             | 检查`YOUREN_JDK_HOME`、`JAVA_HOME`指向JDK21；用新终端运行`java -version`。                                                |
| 测试验证码无法读取         | 必须使用当前API进程的`YOUREN_AUTH_TEST_INBOX_KEY`、未过期的`challengeId`和本机loopback地址。重启API后重新发送验证码。     |

自动化测试数据库不能替代此开发数据库。运行整仓`npm run check`时遵循[CI与验收说明](ci.md)中的Docker、测试隔离和端口约定；不要与手动浏览器验收并行操作同一Compose项目。
