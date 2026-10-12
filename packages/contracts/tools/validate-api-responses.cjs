// 使用现行OpenAPI逐份检查真实HTTP响应；只读取测试导出的无凭证结果，二进制下载另由后端测试验证。
const fs=require('node:fs');
const Ajv=require('ajv/dist/2020');
const addFormats=require('ajv-formats');
const document=require('../openapi.json');
const samples=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
let checked=0;
for(const sample of samples){
  const route=Object.keys(document.paths).find(path=>new RegExp('^'+path.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(sample.path));
  if(!route || !document.paths[route][sample.method])continue;
  const response=document.paths[route][sample.method].responses[sample.status];
  if(!response)throw new Error(`${sample.method} ${route}: 未约定状态 ${sample.status}`);
  const schema=response.content?.['application/json']?.schema;
  if(!schema)continue;
  const ajv=new Ajv({strict:false,allErrors:true});addFormats(ajv);
  const validate=ajv.compile({...schema,components:document.components});
  if(!validate(sample.body))throw new Error(`${sample.method} ${route} ${sample.status}: ${JSON.stringify(validate.errors)}`);
  checked++;
}
if(checked===0)throw new Error('没有实际HTTP响应供校验');
console.log(`真实HTTP响应合同校验通过：${checked} 份`);
