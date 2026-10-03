# OpenAPI 接口合同

[openapi.json](openapi.json) 为 OpenAPI 3.1.0 合同，包含92个 REST 操作，统一 `/v1` 前缀。中文 `description` 说明字段和规则；JSON不支持注释，因此注释维护于生成源及本说明。可以导入支持OpenAPI 3.1的Swagger Editor、Postman等工具评审。

## 使用与验证

需要 Node.js >=20.12。进入本目录执行：

```powershell
npm ci
npm run build
npm run check:generated
npm test
```

`src/schemas.cjs`维护DTO，`src/operations.cjs`维护接口清单，`tools/generate.cjs`生成合同。修改源文件后重新生成，不手工编辑JSON。测试验证OpenAPI结构、引用、JSON Schema、详细设计接口覆盖、请求示例及权限/长度/图片/版本边界。

## 调用约定

- 账号使用 `Authorization: Bearer <accessToken>`，游客使用 `X-Guest-Token`；普通请求只能提供一种身份。管理端使用独立令牌及角色。游客和账号在安全要求中为“或”关系。
- 写入需要幂等键的操作显式要求 `Idempotency-Key`；消息使用UUIDv7 `clientMessageId`去重。版本更新要求带引号的 `If-Match: "1"`；缺失428、冲突412。恋爱资料首次创建使用 `"0"`。
- 普通私聊不要求18岁声明或匹配。恋爱访问须主动开启并有效声明18岁；声明不是身份核验。资源所有权、成员区间、名额、拉黑、频率和跨字段规则仍须服务端检查。
- 列表响应包含 `data`、`page`、`requestId`；bigint序号为字符串。消息扫描返回 `scannedThroughSequence`。204没有正文，202只表示受理。
- `x-grapheme-max`按Unicode可见字符计算，常规代码生成器不会自动执行；前后端应先NFC正规化，再用共享校验器执行。静态图片5,000,000字节上限；实际解码还需限制20MP、去EXIF，展示长边1920及目标1MB。
- 原始发布时间或发送时间起一个UTC日历年删除，编辑不续期。公开DTO不含邮箱、集合地点或稳定游客主体；删除/过期内容仅保留无正文占位。

## 范围与待确认项

本文件是合同，不是已启动的API服务，也不验证数据库竞争、JWT签名、实时WebSocket、存储清理或业务事务。实时协议仍参照[详细设计](../../docs/superpowers/specs/2026-10-03-social-app-database-api-design.md)。

邮箱注册已确认；验证码登录细节、偏好字段、额外资料长度、审核动作及幂等保存窗口仍需评审。相关操作带待确认说明；偏好首稿只接受空对象。服务器路径为相对地址，正式环境必须使用HTTPS。
