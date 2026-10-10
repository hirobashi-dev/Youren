# 手机端认证与普通资料

一套TypeScript代码支持Android和iOS，沿用绿色主题、中文系统字体、安全区和可滚动表单。游客首页保留连接状态与重试，无需注册即可浏览；主动进入邮箱登录/注册后，可编辑普通资料、查看审核后的公开主页、修改隐私和通知偏好、退出登录。普通主页不展示邮箱、审核草稿、年龄声明或恋爱资料；本阶段没有虚构帖子、群组或活动。

## 安装与检查

仓库根使用Node22.23.3；2026-10-05整仓验证实际使用npm10.9.9。执行`npm ci`安装锁定依赖。
固定Expo57.0.26、React19.2.3、React Native0.86.3和expo-secure-store57.0.4；安全区、开发客户端及安全存储版本按本地Expo配套元数据固定。JSON配置由本文件解释：tsconfig开启严格类型；package.json增加配套SecureStore；eas.json固定CLI和内部开发客户端/模拟器构建，不自动上传或发布。app.config.ts启用SecureStore插件和Android备份排除，iOS钥匙串仅设备解锁时可读且限本机。

```powershell
npm test -w @youren/mobile -- --runInBand
npm run typecheck -w @youren/mobile
npm run build:js -w @youren/mobile
npm exec -w @youren/mobile -- expo install --check
npm exec -- eslint apps/mobile
npm exec -- prettier --check apps/mobile
```

## 本机启动

Windows首次安装、数据库/Redis启动、API与Android模拟器的完整逐步说明见[本机开发环境手册](../../docs/development/local-environment.md)。

复制`.env.example`为本目录忽略的`.env`。Android模拟器用`10.0.2.2`，真机使用可达的本机局域网地址；iOS模拟器可使用宿主机localhost。Vite的CORS配置不影响原生客户端。
Java API需按backend/README配置并启动；手机只使用公开`EXPO_PUBLIC_API_BASE_URL`。认证/资料请求十秒超时，连接状态探测仍为两秒；页面卸载会取消表单请求，错误展示固定安全提示，不暴露响应原文。生产部署必须配置真实可达的HTTPS地址；默认localhost只用于开发。不在公开配置、URL或日志放置令牌、测试收件箱密钥、HMAC或其他服务端凭据。

## 账号与恢复规则

- 注册验证码用途为register，邮箱和六位数字验证码先做格式检查；条款/隐私版本来自`GET /v1/auth/policy`，主动勾选默认关闭。可展开阅读开发测试说明；正式法律正文尚未提供，部署正式服务前必须补齐真实政策和可读链接，不把样例说明当作生效政策。发送按钮显示服务端冷却时间并阻止重复提交，429/503显示安全重试信息。
- 匿名浏览不自动创建游客凭证；只有点击“保存游客身份”才创建并安全保存。游客秘密与账号秘密分开持久化；登录接续失败仍保留登录和同一游客重试键，显式重试`POST /v1/me/guest-claims`，仅服务端明确拒绝无效游客凭证时清除。账号受限或临时网络失败不删除游客秘密。
- 账号秘密只写入SecureStore中的一个紧凑对象，包含访问令牌、单次刷新令牌和refreshPending。启动先串行刷新、原子保存新令牌，再独立`GET /v1/me`读取本人；刷新响应严格使用RefreshSession令牌对合同，不依赖伪造的账号字段。存储未成功不能当作持久登录，也不能加载私有资料。
- 刷新前先持久化pending。明确离线且未发送、服务端429/503拒绝时保留可重试凭证；超时、断连或响应丢失可能已经轮换，保留秘密但只允许重新认证，重启或重试不重放旧刷新令牌。pending/离线期间隐藏私有资料；明确401/403清除账号秘密。刷新后的本人读取失败保留已保存的新令牌，绝不恢复已经消费的旧令牌。
- 并发私有401合并一次刷新，迟到401复用新的访问令牌；新登录和退出使旧响应失效。退出先尽力清理两类本机秘密，网络失败提示无法确认远程退出；安全存储失败显示恢复提示并阻止继续私有访问。
- 资料读取后用If-Match版本保存，初次为0；412/428保留用户输入并提供重新读取。待审草稿只供本人读取，公开主页单独读取公开DTO，未公开404不显示发布成功。昵称30/简介500是服务端可配置的暂定工程默认值，按扩展字素校验，不是用户确认上限；手机不强制更小的固定长度，服务端校验始终权威。当前只编辑昵称/简介，地区和兴趣若已有值可读取，头像上传未开放。
- 设置成功后才更新控件；通知偏好不代表系统权限、推送注册或实际通知投递已完成。

测试使用真实页面、API客户端和会话状态机，只替换外部fetch及安全存储适配器。覆盖输入/主动条款、冷却、登录与独立接续、严格响应/公开字段边界、资料加载/冲突/保存、设置、串行刷新、存储失败、临时错误/响应丢失、退出身份边界。组件渲染不替代原生设备验收。

```powershell
npm run android -w @youren/mobile
npm run start -w @youren/mobile
```

Android需要SDK/JDK及设备；项目命令使用当前进程环境选择工具，不修改系统默认。`android`构建并安装开发客户端，`start`启动Metro。`android/`、`ios/`由Expo生成并保持忽略。2026-10-05为本阶段开发APK运行Expo prebuild时，工具实际输出`Clearing android`并重新生成既有被忽略目录；后续如在生成目录加入手工原生改动，应先备份并核对prebuild影响。

Windows本机验证使用独立JDK21和现有SDK，以下变量只影响当前终端；其他电脑按实际工具路径调整：

```powershell
$env:JAVA_HOME='C:\pleiades\java\21'
$env:YOUREN_JDK_HOME=$env:JAVA_HOME
$env:ANDROID_HOME='C:\Users\hbs\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='D:\MyWork\01_developer\Ai\.youren-tools\gradle'
npm run android -w @youren/mobile
```

本次隔离模拟器验收使用`Pixel_3a_API_34`只读模式、`emulator-5556`、Java API3008和Metro8083。含SecureStore的开发APK已重新构建安装；手动构建可在生成的android目录执行`gradlew.bat assembleDebug -PreactNativeArchitectures=x86_64 --no-daemon`，再用指定设备的`adb install`安装APK。
Metro使用localhost时，本机需在当前终端设置`$env:NODE_OPTIONS='--dns-result-order=ipv4first'`，确保Android的`10.0.2.2`能访问IPv4监听；不更改系统网络配置。独立验证示例：

```powershell
$env:EXPO_PUBLIC_API_BASE_URL='http://10.0.2.2:3008'
npm run start -w @youren/mobile -- --localhost --port 8083
```

开发客户端初次提示确认后加载页面；重载使用开发菜单Reload。测试Redis启动后Java客户端可能需要短暂重连，验收应有限等待API就绪再点击重试。

## iOS开发构建

Windows不支持本地Xcode构建。需要Expo/EAS登录和Apple开发者资格、登记设备后，在本目录执行固定CLI：

```powershell
npm exec -- eas build --platform ios --profile development
```

可用macOS时使用development-simulator配置并实际安装验收。EAS项目绑定/账号条件未确认前不宣称构建成功，也不把Expo Go或JS导出当作平台验收。

2026-10-05模拟器现场确认游客连接、隔离测试邮箱注册、资料草稿保存、设置页和退出；资料保存提示待审核，不代表公开发布。2026-10-06注册页发送测试验证码后显示“验证码已发送，请查收邮箱”，不代表外部邮箱实际投递。会话刷新期间保留资料草稿的回归修复新增测试，手机64项测试、类型、lint、格式通过；历史整仓检查中的手机63项结果按当时记录保留。重启后的会话恢复与明确API断线重试尚未取得现场结果；Android实机和iOS原生验收按用户决定延期，不能把JS导出当作原生验收。完整结果和限制见[第二阶段验收记录](../../docs/development/stage-2-acceptance.md)；首阶段历史保留在[第一阶段记录](../../docs/development/stage-1-acceptance.md)。本次未执行EAS构建或上传。
