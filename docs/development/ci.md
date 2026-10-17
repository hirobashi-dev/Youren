# 统一检查与双工具链CI

## 执行顺序与依赖

CI的`setup-java`使用官方Adoptium元数据标识`21.0.12+101.0.LTS`，对应Temurin发布`21.0.12.1+1`；四段Java版本加build不能直接作为其SemVer输入。后端和浏览器保持相同精确发布。本机JDK目录和系统默认设置不变。

根目录先`npm ci --no-audit --no-fund`，再`npm run check`。统一入口依次执行前端、后端、浏览器；任何非零退出、启动异常或信号中断立即停止。`check:frontend`、`check:backend`、`check:browser`可单独定位问题。

固定Node22.23.3、npm10.5.0、JDK21.0.12.1+1和Maven3.9.9 Wrapper。Windows使用独立JDK和PowerShell 7，Linux使用JAVA_HOME。依赖由根锁文件固定；报告解析器fast-xml-parser5.11.2用于检查真实XML，不读取日志估算数量。

后端必须连接Docker daemon；Testcontainers使用临时隔离实例，缺Docker不能跳过集成测试。JUnit六组最低数量为shared单元7、database集成11、api单元6/集成1、worker单元3/集成2；新增测试允许增加，缺失、零测试、失败、错误、跳过及统计与testcase不一致均返回非零。`clean verify`清除旧报告，避免陈旧结果通过。

浏览器需要Chrome、已构建的API JAR及`infra/.env`（首次从示例复制）。工具只启动项目postgres-test/redis-test，结束后只停止本次新启动的服务，不删除卷；故障演练不得与其他使用相同服务的检查并行。

## GitHub工作流

`.github/workflows/ci.yml`在push、pull_request或手动触发时运行，Ubuntu24.04上前端与Java分别检查，均成功后浏览器下载已验收的API JAR联调。保留JUnit报告7天、浏览器证据7天、API中间产物1天；无部署或发布步骤。

官方Actions固定具体提交，版本及提交已核实：[checkout](https://github.com/actions/checkout)、[setup-node](https://github.com/actions/setup-node)、[setup-java](https://github.com/actions/setup-java)、[upload-artifact](https://github.com/actions/upload-artifact)、[download-artifact](https://github.com/actions/download-artifact)。Wrapper保持LF，兼容Windows与Linux。工作流由官方[actionlint](https://github.com/rhysd/actionlint/releases)1.7.12检查；本地下载包已校验SHA256，工具不提交。

## 验收边界

本地测试证据及恢复顺序见[阶段验收记录](stage-1-acceptance.md)。干净源码验证使用Git暂存树导出到新目录，不带node_modules、target或dist，再固定安装并执行完整检查；本地工具、Docker和Chrome为显式外部前置条件。

仓库已推送，GitHub hosted runner修正后运行37168830294的前端、后端、浏览器均成功，不能把语法检查当作云端通过。用户决定延期iOS账号/设备准备与原生测试；两端JS导出通过不代表iOS开发构建或设备验收通过。
