# OpenAPI接口合同实施计划

目标：将已确认数据库/API详细设计转为OpenAPI3.1 JSON，提供中文字段说明、身份权限、请求响应与可执行合同检查。

依据：[详细设计](../specs/2026-10-03-social-app-database-api-design.md)。结构：packages/contracts/src维护模型和操作，tools生成与验证，tests验证权限/约束/覆盖；JSON中用description代替非法注释。

- [x] 合同测试先确认缺失文件失败。
- [x] 编写全部REST操作、DTO与安全/错误/分页/幂等/版本协议。
- [x] OpenAPI解析、JSON Schema边界及详细设计接口覆盖检查通过。
- [x] 说明合同与后端实现边界、中文注释及差分复核；验证后保存本地Git版本。

本次不部署API，不新增已确认范围外的业务接口；未确认参数通过工程建议或受限字段表达，不伪装已批准需求。
