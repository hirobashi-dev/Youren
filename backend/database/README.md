# MyBatis 与迁移接续

当前仅实现健康Mapper和工程验收账号Mapper，未开放业务接口。
XML输入全部使用参数绑定，UUID使用显式TypeHandler；Spring事务共用DataSource。

## 验证

```powershell
.\backend\build.ps1 clean verify
node backend/database/tools/sync-initial.cjs --check
```

11项数据库集成测试使用Testcontainers2.0.5及临时PostgreSQL16.6。
需要可用Docker，缺环境直接失败；不读取外部数据库URL。测试重置schema只发生于本次创建的容器。
覆盖54表、默认配额、重复迁移、唯一/CHECK/FK、群延迟完整性、UTC日历年保留、
UUID、参数绑定、CRUD、回滚和旧迁移接续。`target/failsafe-reports/`记录实际数量。

## V1来源与后续DDL

V1保留既有初始SQL及中文说明，仅移除外层BEGIN/COMMIT，由Flyway管理事务。
`sync-initial.cjs --check`验证来源一致；交接期间默认不修改SQL。
正式采用后冻结V1，只新增V2等迁移，不再用Prisma migrate修改同一数据库。
旧Prisma schema/工具暂作为历史参考，待Java API/worker验收后整理，不混用迁移入口。

## 旧库接续

独立入口`jp.youren.database.MigrationCommand`支持`audit-legacy`（只读）、
`adopt-legacy`（显式baseline版本1）、`migrate`（空库V1或后续增量）。
使用README中的独立Java环境及SPRING_DATASOURCE配置；不要把密码放命令行或JDBC URL。
API/worker须禁用自动Flyway，迁移由单独受控步骤执行。

操作前停止并发DDL及Prisma迁移，备份目标数据库，并确认地址、账号及数据库名。
审计比较表、列、默认值、约束、索引、函数、触发器和初始配额的逻辑指纹，
并核对唯一一条成功Prisma迁移、已知checksum且没有回滚；不一致时立即停止。
指纹来自临时V1验证库，`db/v1-schema.sha256`保存；原SQL的LF/CRLF校验值
在`db/legacy-checksums.txt`，仅容忍换行差异。旁置本说明解释无注释校验文件。
禁止自动baseline和clean。接续只新增Flyway历史，保留业务数据及旧迁移历史，
随后migrate不能重放V1。正式库/非本项目库需另行明确授权。
