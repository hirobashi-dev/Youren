# 手机端工程基础

一套TypeScript代码支持Android和iOS。沿用绿色主题和中文界面，当前只实现连接状态及重试；业务功能随后续阶段开发。

## 安装与检查

仓库根使用Node22.23.3、npm10.5.0执行`npm ci`。
固定Expo57.0.26、React19.2.3、React Native0.86.3；安全区和开发客户端版本按Expo配套元数据固定。JSON配置由本文件解释：tsconfig开启严格类型；eas.json固定CLI和内部开发客户端/模拟器构建，不自动上传或发布。

```powershell
npm test -w @youren/mobile -- --runInBand
npm run typecheck -w @youren/mobile
npm run build:js -w @youren/mobile
npm exec -w @youren/mobile -- expo install --check
```

## 本机启动

复制`.env.example`为本目录忽略的`.env`。Android模拟器用`10.0.2.2`，真机使用可达的本机局域网地址；iOS模拟器可使用宿主机localhost。Vite的CORS配置不影响原生客户端。
Java API需按backend/README配置并启动；手机只使用公开`EXPO_PUBLIC_API_BASE_URL`，两秒超时和卸载取消不暴露错误详情。

```powershell
npm run android -w @youren/mobile
npm run start -w @youren/mobile
```

Android需要SDK/JDK及设备；项目命令使用当前进程环境选择工具，不修改系统默认。`android`构建并安装开发客户端，`start`启动Metro。`android/`、`ios/`由Expo生成并保持忽略，不使用clean重建已有手工原生改动。

Windows本机验证使用独立JDK21和现有SDK，以下变量只影响当前终端；其他电脑按实际工具路径调整：

```powershell
$env:JAVA_HOME='D:\MyWork\01_developer\Ai\.youren-tools\jdk-21.0.12.1+1'
$env:ANDROID_HOME='C:\Users\hbs\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='D:\MyWork\01_developer\Ai\.youren-tools\gradle'
npm run android -w @youren/mobile
```

本次隔离模拟器验收使用`Pixel_3a_API_34`只读模式、`emulator-5556`、Java API3008和Metro8083。手动构建可在生成的android目录执行`gradlew.bat assembleDebug -PreactNativeArchitectures=x86_64 --no-daemon`，再用指定设备的`adb install`安装APK。
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

两端须记录启动、重载、API成功/故障/恢复、中文、安全区、设备/OS/SDK及截图。验证结果统一写入docs/development/stage-1-acceptance.md。
