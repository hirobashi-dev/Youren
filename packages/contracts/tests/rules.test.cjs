// 验证可执行合同规则，既覆盖正常边界，也防止客户端越权字段进入DTO。
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const SwaggerParser=require('@apidevtools/swagger-parser');
const Ajv=require('ajv/dist/2020');
const formats=require('ajv-formats');
const doc=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../openapi.json'),'utf8'));
const ajv=new Ajv({strict:false,allErrors:true});formats(ajv);
const segmenter=new Intl.Segmenter('zh',{granularity:'grapheme'});
ajv.addKeyword({keyword:'x-grapheme-max',type:'string',schemaType:'number',validate:(limit,value)=>[...segmenter.segment(value)].length<=limit});
ajv.addSchema(doc,'contract');
const validate=(name,value)=>ajv.compile({$ref:`contract#/components/schemas/${name}`})(value);
const id='11111111-1111-7111-8111-111111111111';
test('OpenAPI 3.1结构、引用和全部JSON Schema可验证',async()=>{
  await SwaggerParser.validate(structuredClone(doc));
  for(const name of Object.keys(doc.components.schemas))ajv.compile({$ref:`contract#/components/schemas/${name}`});
  for(const item of Object.values(doc.paths))for(const op of Object.values(item))for(const response of Object.values(op.responses))if(response.content)ajv.compile({...response.content['application/json'].schema,components:doc.components});
});
test('全部路径有唯一操作ID，路径参数必填，204没有正文',()=>{
  const ids=new Set();let count=0;
  for(const [route,item] of Object.entries(doc.paths)){
    assert.ok(route.startsWith('/v1/'));
    for(const op of Object.values(item)){
      count++;assert.ok(!ids.has(op.operationId));ids.add(op.operationId);
      for(const [,name] of route.matchAll(/\{([^}]+)\}/g))assert.ok(op.parameters.some(p=>p.name===name&&p.in==='path'&&p.required));
      if(op.responses[204])assert.equal(op.responses[204].content,undefined);
    }
  }
  assert.equal(count,92);
});
test('详细设计REST目录全部覆盖，请求示例符合对应DTO',()=>{
  const design=fs.readFileSync(path.resolve(__dirname,'../../../docs/superpowers/specs/2026-10-03-social-app-database-api-design.md'),'utf8').split('## 7.')[1].split('## 8.')[0];
  for(const [,method,route] of design.matchAll(/\b(GET|POST|PUT|PATCH|DELETE) (\/[^\s；|`]+)/g))assert.ok(doc.paths[`/v1${route}`]?.[method.toLowerCase()],`${method} ${route}缺失`);
  for(const item of Object.values(doc.paths))for(const op of Object.values(item)){
    const content=op.requestBody?.content['application/json'];
    if(content?.example)assert.ok(validate(content.schema.$ref.split('/').at(-1),content.example));
  }
});
test('游客身份采用或关系，普通私聊无需恋爱资格，管理令牌独立',()=>{
  assert.deepEqual(doc.paths['/v1/posts'].post.security,[{GuestToken:[]},{UserBearer:[]}]);
  assert.equal(doc.paths['/v1/direct-conversations'].post['x-authorization-level'],'U');
  assert.equal(doc.paths['/v1/dating/candidates'].get['x-authorization-level'],'D');
  assert.deepEqual(doc.paths['/v1/admin/reports'].get.security,[{AdminBearer:[]}]);
});
test('年龄仅接受主动勾选，不接受verified或未勾选',()=>{
  assert.ok(validate('AgeInput',{accepted:true,statementVersion:'1'}));
  assert.ok(!validate('AgeInput',{accepted:false,statementVersion:'1'}));
  assert.ok(!validate('AgeInput',{accepted:true,statementVersion:'1',verified:true}));
});
test('文字限制按可见字符执行，图片限额和空回复可检测',()=>{
  assert.ok(validate('MessageInput',{clientMessageId:id,body:'👨‍👩‍👧‍👦'.repeat(1000)}));
  assert.ok(!validate('MessageInput',{clientMessageId:id,body:'字'.repeat(1001)}));
  assert.ok(!validate('MessageInput',{clientMessageId:id,body:'   '}));
  assert.ok(validate('ReplyInput',{mediaIds:[id]}));assert.ok(!validate('ReplyInput',{}));
  assert.ok(validate('UploadInput',{purpose:'board',mime:'image/png',sizeBytes:5000000}));
  assert.ok(!validate('UploadInput',{purpose:'board',mime:'image/gif',sizeBytes:5000001}));
  assert.ok(!validate('ReplyInput',{mediaIds:Array.from({length:4},(_,i)=>id.replace(/^1/,String(i)))}));
});
test('私聊来源、bigint字符串和乐观锁不可绕过',()=>{
  assert.ok(validate('DirectInput',{targetAccountId:id,source:'publicProfile'}));
  assert.ok(!validate('DirectInput',{targetAccountId:id,source:'groupMember'}));
  assert.ok(validate('DirectInput',{targetAccountId:id,source:'groupMember',sourceGroupId:id}));
  assert.ok(validate('ReadInput',{lastReadSequence:'9007199254740993'}));
  assert.ok(!validate('ReadInput',{lastReadSequence:2}));
  const update=doc.paths['/v1/events/{eventId}'].patch;
  assert.ok(update.parameters.some(p=>p.name==='If-Match'&&p.required));assert.ok(update.responses[412]);assert.ok(update.responses[428]);
  assert.ok(doc.paths['/v1/groups/{groupId}/owner-transfers/{transferId}/accept'].post.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));
});
test('公开DTO和终态占位拒绝私有资料字段',()=>{
  assert.equal(doc.components.schemas.PublicProfile.additionalProperties,false);
  assert.equal(doc.components.schemas.PublicProfile.properties.email,undefined);
  assert.equal(doc.components.schemas.EventPublic.properties.meetingInstructions,undefined);
  assert.ok(validate('OwnContent',{kind:'posts',id,status:'expired',unavailable:true}));
  assert.ok(!validate('OwnContent',{kind:'posts',id,status:'expired',unavailable:true,body:'应删除的正文'}));
});
