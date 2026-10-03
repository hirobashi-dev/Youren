# 数据库结构

本包依据[数据库与 API 详细设计](../../docs/superpowers/specs/2026-10-03-social-app-database-api-design.md)，提供54个Prisma模型、PostgreSQL初始迁移及数据库约束测试。当前不含API业务服务、生产连接或自动删除worker。

## 文件

- `prisma/schema.prisma`：表、字段、外键、唯一约束及客户端模型；生成文件。
- `prisma/migrations/202610030001_initial/migration.sql`：完整建表、索引、检查、触发器和默认运营配置，事务化执行。
- `tools/model.cjs`：字段、关系及常规约束的规范源。
- `sql/constraints.sql`：PostgreSQL专用部分索引、跨表规则、UTC日历年到期计算。
- `tests/`：在全新内存PGlite数据库中执行迁移和约束测试，不访问外部数据库。

## 安装和验证

本包锁定Prisma 6.19.0、PGlite 0.5.8，要求Node≥20.12；这是此阶段经过验证的工具组合，不使用浮动latest。后续API实现时统一评估版本迁移和运行时客户端。

在本目录执行：

```powershell
npm ci
npm run build:schema
npm run check:generated
npm run validate
npm test
```

validate脚本只设置本地虚构连接字符串做Prisma语法校验，不连接数据库。测试覆盖身份唯一性、外键、会话类型隔离、群主/人数/会话原子创建、单个开放入群区间、跨帖回复、图片大小/用途/独占绑定、年龄声明状态和闰年保留期限。

## 在独立开发数据库应用

设置指向**专门开发数据库**的DATABASE_URL后，在本目录执行：

```powershell
npx prisma migrate deploy --schema prisma/schema.prisma
```

本次未执行这条真实服务器命令，未写入任何用户数据库。首次可使用迁移部署；已有生产库必须评审增量迁移，不能直接复制初始SQL或重置。迁移将创建Prisma迁移记录，后续不能另用psql重复执行相同初始SQL。不要使用`prisma db push`代替migration，它不能完整表达CHECK、部分索引与跨表触发器。修改规范源后重新生成、验证，再为已经部署的库追加迁移，禁止修改已部署迁移。

## 结构与业务边界

- SQL自动计算首次公开/发送后的一个UTC日历年，夹住闰年日期，编辑不续期；定时清理S3/缓存/备份重放仍须worker。到期查询过滤仍须API。
- 群、owner成员、准确active_count及唯一会话必须在同事务写入。解散使用readonly，closed用于不允许读取；API仍须检查发送与读取资格。
- 100人、拥有5群、每日创建2群、未回复前3条及每天新联系10人已作为policy_configs初始值。每日用东京自然日；每日额度、群拥有数、接收设置、转让同意和名额竞争须业务服务在账号/活动/群锁下更新，不能仅凭有表就视为已实现。
- 普通direct与dating会话独立唯一，dating配对字段与match必须一致。年龄self_declared不是verified，资格与UUIDv7时间窗在服务层检查。
- 跨对象图片绑定由数据库锁与触发器保护；单连接表的position范围保证张数上限。真实格式、EXIF、审核、像素解码和可见字符长度在受控媒体/内容服务中验证，SQL不能替代这些操作。
- FK使用RESTRICT，清理前需按顺序清空收据等可空引用或保留去标识占位，再删除关联资源；不能按账号级CASCADE绕过图片/审计清理。
- `updated_at`由后续仓储写入（本次只有插入默认值）；消费方使用更新时必须显式写入，不能当作已提供数据库自动更新时间。

PGlite验证是真实PostgreSQL引擎的隔离执行，但本次尚未进行独立PostgreSQL服务器、多连接竞态、Prisma Client实际读写、升级迁移及性能测试；这些在业务服务实现阶段必做。未生成全日本地区/兴趣种子，避免编造地区数据；导入字典前公开资料允许region为空，活动发布必须有有效地区FK。

参考：[PGlite文档](https://pglite.dev/docs/)；Prisma固定版本由package-lock.json管理，使用本包已验证的命令，不将新版文档命令混入旧工具链。
