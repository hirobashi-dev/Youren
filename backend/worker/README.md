# Java worker 工程基础

```powershell
.\backend\build.ps1 clean verify
.\backend\run.ps1 -Service worker
```

与API共用SPRING_DATASOURCE及Redis配置，仅探测依赖，不消费业务任务或开启HTTP。
启动默认等待1500ms，失败/超时非零退出；重复关闭安全。
API/worker均不自动Flyway改表，迁移走独立受控入口。

3项生命周期单元测试及2项真实JAR进程测试。真实进程在固定摘要的官方
Java21 Linux容器运行，依赖为临时PostgreSQL/Redis。
分别发送SIGTERM和SIGINT，要求10秒内退出、stopped事件恰好一次、
无137强杀退出、无残留JDBC连接；失败启动检查非零退出与无秘密日志。
Windows本机正常构建/启动可用，OS信号证据来自Linux容器，不能称为Windows信号验收。
所有临时容器/网络由测试关闭，不操作其他项目容器。
