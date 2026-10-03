# Java、Spring Boot、Maven与MyBatis实施调整

> 当前有效的后端改修计划，替代第1阶段原TypeScript后端任务。执行时使用executing-plans逐任务测试与提交；开发已恢复，逐任务测试与提交。

**目标：** 以Java/Spring Boot重建后端基础，Maven构建和测试、MyBatis数据库访问，复用数据库SQL、前端与OpenAPI合同。

**状态：** 开发已恢复，前端保留TypeScript。Java/Spring Boot、Maven、MyBatis已获确认。版本固定为JDK21.0.12.1+1、Spring Boot3.5.16、MyBatis Starter3.0.5、Maven3.9.9；JDK独立下载校验，不改系统默认。Flyway为工程默认，实时WebSocket＋JSON协议仍待确认。

**依据：** [最新技术方案](../specs/2026-10-03-social-app-technical-design.md)、[数据库/API设计](../specs/2026-10-03-social-app-database-api-design.md)、[八阶段路线](2026-10-03-phased-development-acceptance.md)、[现有证据与恢复记录](../../development/stage-1-acceptance.md)。

## 工程布局和职责

| 拟新增文件/目录 | 用途 |
|---|---|
| `backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/maven-wrapper.properties` | 聚合模块、固定Maven发行版与校验，Boot BOM和测试/格式插件 |
| `backend/shared/pom.xml`、`src/main/java/jp/youren/shared/config/RuntimeSettings.java`、`logging/SafeLog.java` | 服务端配置验证和白名单日志 |
| `backend/shared/src/test/java/jp/youren/shared/RuntimeSettingsTest.java`、`SafeLogTest.java` | 缺失配置、超时/端口及秘密不进入日志测试 |
| `backend/database/pom.xml`、`src/main/java/jp/youren/database/DatabaseConfiguration.java`、`mapper/HealthMapper.java` | DataSource、MyBatis扫描、SQL参数和事务统一配置 |
| `backend/database/src/main/resources/mapper/HealthMapper.xml`、`db/migration/V1__initial.sql` | Mapper SQL与Flyway初始迁移 |
| `backend/database/src/test/java/jp/youren/database/MigrationIT.java`、`MyBatisCrudIT.java`、`ConstraintIT.java` | 空库/重复部署、Java读写与事务回滚、54表/索引/触发器和数据库约束 |
| `backend/api/pom.xml`、`src/main/java/jp/youren/api/ApiApplication.java`、`health/HealthController.java`、`health/HealthService.java` | Spring Boot API启动及保持原health路径 |
| `backend/api/src/main/resources/application.yml`、`src/test/java/jp/youren/api/HealthControllerTest.java`、`ReadinessIT.java` | 配置和健康200/503/有限超时/恢复验收 |
| `backend/worker/pom.xml`、`src/main/java/jp/youren/worker/WorkerApplication.java`、`WorkerLifecycle.java` | 独立worker、依赖连接与关闭，无业务消费 |
| `backend/worker/src/test/java/jp/youren/worker/WorkerLifecycleTest.java`、`WorkerDependenciesIT.java` | 启动失败、重复关闭、真实连接和进程退出 |
| `backend/README.md`、`.github/workflows/ci.yml` | Java启动、双工具链CI及报告说明 |

表中`src/...`相对同一行的Maven模块。API/worker依赖shared和database；Java领域逻辑按模块组织，不让前端导入Java实现。修改根README、工程检查/编排及忽略规则，让npm只负责前端/合同，Maven负责后端；不能要求Java模块拥有npm脚本。当前TS源码在Java验证通过前保留且不扩展业务。

## MyBatis访问规则

- 使用官方`mybatis-spring-boot-starter`，与所选Boot主版本匹配；参考[官方兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。MyBatis已确认，不自动引入MyBatis-Plus或JPA。
- Mapper接口与XML namespace/方法一致；显式resultMap或受控驼峰映射，不把数据库行直接作为公开DTO。复杂事务与锁查询放XML便于审查。
- 输入值使用`#{...}`参数绑定；禁止用户输入进入`${...}`拼接。排序列、目标表等不可参数化标识由服务端枚举白名单选择。[Mapper说明](https://mybatis.org/mybatis-3/sqlmap-xml.html)
- Spring服务层协调事务，与Mapper使用同一DataSource/事务管理器；不在Mapper中手动commit，也不在持锁事务等待外部邮件/审核/推送。按既有锁序实现并发，outbox和业务写入同事务。[事务说明](https://mybatis.org/spring/transactions.html)
- UUID、timestamptz、bigint、JSONB与枚举在Java端定义明确映射；UTC时间使用Instant等明确类型，消息sequence对外仍是字符串。JSONB按需定义TypeHandler并测试，不依赖字符串隐式转换。
- 不通过持久层自动生成/修改表。首阶段仅实现健康和代表性测试读写Mapper，其他业务Mapper随阶段2至7开发，不预先生成54套空接口。

## 恢复后的任务顺序

### 1. Maven与依赖基线

- [x] 核实本机JDK与JAVA_HOME，固定JDK、Boot、MyBatis Starter、Maven和插件版本；保留前端固定Node环境。
- [x] 创建四模块及Wrapper，先写缺配置/日志测试，确认因实现缺失而失败，再实现最小shared模块。
- [x] Maven Enforcer约束工具版本；Surefire执行`*Test`，Failsafe绑定integration-test与verify执行`*IT`，JUnit Jupiter自动发现测试。XML配置与关键逻辑加入中文注释。
- [x] 干净安装/编译和shared测试通过，报告确认不是0测试；差分审查后提交`Add Maven backend foundation`。

### 2. MyBatis与SQL迁移接续

- [x] 从现有初始SQL生成Flyway V1，保持54表、约束、函数、索引、触发器、默认配置与中文说明；由Flyway管理事务，外层BEGIN/COMMIT若调整须验证可执行SQL等价。
- [x] 在全新隔离PostgreSQL运行初始/重复迁移及Mapper CRUD，验证唯一/CHECK/FK、跨表约束、UTC日历年与回滚；不能仅以旧Prisma测试代替Java验证。
- [x] 既有开发/测试库带`_prisma_migrations`；切换前核对结构/迁移版本，再受控baseline到对应Flyway版本。关闭自动baseline和clean，不对已有表重放V1，不删除用户数据；若结构不匹配，停止并报告。
- [x] 写迁移接续记录，之后只用Flyway管理新增DDL；SQL来源和规范源调整完成前保留旧包只读参考，禁止同时运行Prisma和Flyway变更同一库。
- [x] 数据库IT和重复迁移通过后提交`Add MyBatis access and SQL migration continuity`。

### 3. Java API健康和HTTP合同

- [x] 重建`GET /health/live`、`GET /health/ready`，响应仍是安全`status`对象；依赖失败503、有限超时、恢复200。前端URL和health响应保持一致。
- [x] MockMvc/真实HTTP测试先失败再实现；使用真实MyBatis/PostgreSQL及Redis验证就绪。连接设置使用JDBC URL、用户名/密码独立变量，禁止把原Prisma URL直接当JDBC URL。
- [x] 配置脱敏、受限CORS、关闭连接、异常输出检查通过；增加Java DTO序列化/错误映射合同测试，继续运行Node OpenAPI检查。
- [x] 构建及测试报告确认后提交`Replace API foundation with Spring Boot`。

### 4. Java worker生命周期

- [x] 独立Boot worker进程复用shared/database，无业务任务消费。启动失败返回非零退出，连接只检查授权测试目标。
- [x] 先测试失败释放、重复关闭及超时，再实现；JUnit/Testcontainers验证真实依赖，实际启动/信号关闭检查无残留线程和连接。
- [x] 验证对应Java任务1至4通过后，单独评审移除旧TS API/worker/runtime及其npm依赖/检查；保留历史测试记录，不留两套后端默认入口。
- [x] 差分和回归通过，提交`Replace worker lifecycle with Java`。

### 5. 前端兼容回归

- [x] 保留后台React/Vite页面；以Java API验证失败/重试/恢复，补齐超时/取消测试和窄屏操作。旧截图/测试保持历史身份。
- [x] 更新根启动说明、恢复记录和前后端环境字段；Node只作为前端与合同工具，不再是Java服务运行要求。
- [x] 前端组件/浏览器和合同测试通过后记录本地版本。

### 6. 手机端与两端构建

- [ ] 按原阶段1任务6开发Expo手机端，调用相同HTTP合同；Java切换不要求改成原生Java Android应用。
- [ ] 组件、JS导出、Android/iOS开发构建及设备操作分别记录；缺账号/设备不标平台验收通过。实时协议在阶段4前确认，手机工程骨架不提前绑定Socket.IO。

### 7. 双工具链CI与整体验收

- [ ] 后端CI固定JDK，执行Maven Wrapper verify；前端CI固定Node，执行类型、格式、测试、构建和OpenAPI校验。根编排对任何子检查失败返回非零。
- [ ] 数据库集成采用Testcontainers或明确隔离服务；缺必需Docker时报失败，不能自动跳过IT。验证Surefire/Failsafe报告数量、结果及容器清理。
- [ ] 第1阶段12项门槛保留，但Prisma读写验收改为MyBatis读写/事务/迁移接续；Java后端证据必须重新取得。通过后再继续阶段2。

## 构建、测试和启动命令

Wrapper和pom已建立；Windows推荐backend/build.ps1临时选择JDK21。以下直接Wrapper命令需当前终端设置正确JAVA_HOME：

```powershell
.\backend\mvnw.cmd -f backend/pom.xml test
.\backend\mvnw.cmd -f backend/pom.xml verify
.\backend\mvnw.cmd -f backend/pom.xml -pl api -am test
.\backend\mvnw.cmd -f backend/pom.xml -pl worker -am verify
```

`test`执行Surefire测试；`verify`包括Failsafe集成检查和打包，不使用`-DskipTests`作验收。Linux/macOS用`./backend/mvnw -f backend/pom.xml verify`。不能仅跑integration-test就声称完整verify通过。[Maven生命周期](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)

从backend目录先执行`.\mvnw.cmd install`建立模块依赖，再用`.\mvnw.cmd -f api/pom.xml spring-boot:run`或worker对应pom启动；install亦运行verify检查，不跳过测试。数据库迁移采用单独受控命令/任务，生产API和worker禁用自动改表。计划配置字段为SPRING_DATASOURCE_URL（jdbc:postgresql://...）、SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD、SPRING_DATA_REDIS_URL和SERVER_PORT；Java不假设自动加载Node `.env`，不在URL或日志回显密码。旧DATABASE_URL仅用于历史TS/Prisma重现。

## 完成门槛与边界

- [ ] 固定Maven/JDK干净检出可构建，报告中实际测试数符合清单。
- [ ] MyBatis类型/参数绑定、真实CRUD/事务/约束和Flyway接续通过；SQL结构及业务规则不漂移。
- [ ] Java API/worker全部基础验收通过；旧TS已测记录未冒称Java结果。
- [ ] 前端/合同兼容、错误与序号序列化一致；秘密无日志/版本控制泄漏。
- [ ] 新增中文注释、对应测试和每任务本地提交完整；阶段整体未满足仍标未完成。

历史TS代码在对应Java验收通过前保留；按任务顺序继续MyBatis、API和worker，不能跳到原手机任务6或冒称旧证据为Java结果。
