// 验证合同而非业务实现：首先要求文件存在，再检查结构、权限与DTO边界。
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
test('OpenAPI合同文件存在且可解析',()=>{
 const doc=JSON.parse(fs.readFileSync(path.join(__dirname,'../openapi.json'),'utf8'));
 assert.equal(doc.openapi,'3.1.0');
 assert.ok(doc.paths['/v1/direct-conversations']);
});
