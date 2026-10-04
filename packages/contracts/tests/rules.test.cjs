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

// 待审草稿状态只属于本人DTO；公开资料保持原有允许字段且禁止审核/私人信息。
test('本人草稿有独立审核状态而公开DTO拒绝草稿状态',()=>{
  const profile={id,nickname:'待审昵称',bio:'简介',avatar:null,region:null,interests:[],capabilities:{canEdit:true}};
  assert.ok(validate('OwnerProfile',{...profile,moderationStatus:'pending'}));
  assert.equal(validate('OwnerProfile',{...profile,moderationStatus:'visible'}),false);
  assert.equal(validate('PublicProfile',{...profile,moderationStatus:'pending'}),false);
  assert.equal(doc.components.schemas.Me.properties.profile.anyOf[0].$ref,'#/components/schemas/OwnerProfile');
  assert.equal(doc.components.schemas.ProfileVersion.properties.profile.$ref,'#/components/schemas/OwnerProfile');
  assert.equal(doc['x-profile-policy'].decisionStatus,'provisional-engineering-defaults');
  assert.equal(doc['x-profile-policy'].validationAuthority,'server-config');
});
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
  assert.equal(count,93);
});
// 手机端读取当前政策版本后主动同意；公共入口不得返回认证配置或秘密。
test('公开政策版本只包含服务端要求的条款与隐私版本',()=>{
  const policy=doc.paths['/v1/auth/policy']?.get;
  assert.ok(policy);
  assert.deepEqual(policy.security,[]);
  assert.ok(validate('AuthPolicy',{termsVersion:'2026-10-04',privacyVersion:'2026-10-04'}));
  assert.equal(validate('AuthPolicy',{termsVersion:'',privacyVersion:'2026-10-04'}),false);
  assert.equal(validate('AuthPolicy',{termsVersion:'2026-10-04',privacyVersion:'2026-10-04',secret:'forbidden'}),false);
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

// 首次资料尚无正版本，必须允许0；之后仍拒绝缺失、负数和未加引号的版本。
test('普通资料首次创建使用0版本，更新使用正版本且保持冲突错误',()=>{
  const op=doc.paths['/v1/me/profile'].patch;
  const schema=op.parameters.find(p=>p.name==='If-Match').schema;
  const valid=ajv.compile(schema);
  assert.ok(valid('"0"'));assert.ok(valid('"1"'));
  assert.ok(!valid('0'));assert.ok(!valid('"-1"'));
  assert.ok(op.responses[412]);assert.ok(op.responses[428]);
});

// 刷新和验证码本身是认证证明；公开路由也必须定义统一认证失败与撤销响应。
test('认证秘密无效时有401合同且成功响应不泄露存储摘要',()=>{
  for(const route of ['register','login','refresh']){
    assert.ok(doc.paths[`/v1/auth/${route}`].post.responses[401],route);
    // 验证邮箱并不解除账号限制，受限账号保持统一403语义。
    assert.ok(doc.paths[`/v1/auth/${route}`].post.responses[403],route);
  }
  const guest=doc.components.schemas.GuestSession;
  assert.ok(guest.required.includes('expiresAt'));
  assert.equal(guest.properties.secretHash,undefined);
  assert.equal(doc.components.securitySchemes.UserBearer.bearerFormat,'opaque');
});

// 六位格式与用途隔离实际校验输入，避免仅检查说明文字。
test('邮箱认证仅接受六位代码及注册必需条款，登录不能伪造账号ID',()=>{
  assert.ok(validate('LoginInput',{challengeId:id,code:'012345'}));
  assert.ok(!validate('LoginInput',{challengeId:id,code:'12345'}));
  assert.ok(!validate('LoginInput',{challengeId:id,code:'abcdef'}));
  assert.ok(!validate('LoginInput',{challengeId:id,code:'012345',accountId:id}));
  assert.ok(!validate('RegisterInput',{challengeId:id,code:'012345'}));
  assert.ok(validate('RegisterInput',{challengeId:id,code:'012345',termsVersion:'2026-10-04',privacyVersion:'2026-10-04'}));
  assert.ok(!validate('EmailCodeInput',{email:'a@example.test',purpose:'dating'}));
});

// 合同默认值用于配置对照；可配置的状态机限流行为另由真实API测试验证。
test('认证默认策略在合同中可读取且明确允许部署配置覆盖',()=>{
  assert.deepEqual(doc['x-auth-policy'],{
    configurable:true,codeDigits:6,codeTtlSeconds:600,maxWrongAttempts:5,cooldownSeconds:60,
    emailHourlyLimit:5,emailDailyLimit:20,ipHourlyLimit:30,ipDailyLimit:100,
    accessTtlSeconds:900,refreshTtlSeconds:2592000,guestTtlSeconds:2592000,
  });
});

// 响应只能公开挑战标识和等待时间，不能用附加字段枚举邮箱或泄漏验证码。
test('匿名验证码响应拒绝验证码邮箱和账号存在信息并验证时间边界',()=>{
  const challenge={challengeId:id,expiresIn:600,retryAfter:60};
  assert.ok(validate('Challenge',challenge));
  assert.ok(!validate('Challenge',{...challenge,expiresIn:0}));
  assert.ok(!validate('Challenge',{...challenge,retryAfter:-1}));
  for(const key of ['email','code','accountExists'])assert.ok(!validate('Challenge',{...challenge,[key]:'secret'}));
  assert.ok(!validate('Challenge',{challengeId:id,expiresIn:600}));
});

// 游客接续失败仍是成功登录结果；既有邮箱和新注册必须返回相同会话DTO。
test('注册200与201共享会话合同，接续失败结果仍保有登录凭证',()=>{
  const responses=doc.paths['/v1/auth/register'].post.responses;
  assert.deepEqual(responses[200].content,responses[201].content);
  const session={accessToken:'access-secret',refreshToken:'refresh-secret',claimStatus:'failed',onboardingRequired:true,
    account:{id,email:'a@example.test',status:'active',profile:null,profileVersion:null,
      settings:{allowStrangerDm:true,pushEnabled:true,lockscreenPreview:false},
      ageDeclaration:{status:'not_declared',declaredAt:null,withdrawnAt:null},onboardingRequired:true}};
  assert.ok(validate('AuthSession',session));
  assert.ok(!validate('AuthSession',{...session,claimStatus:'claimed_by_email'}));
  assert.ok(!validate('AuthSession',{...session,refreshHash:'secret'}));
  const {accessToken,...missingToken}=session;void accessToken;
  assert.ok(!validate('AuthSession',missingToken));
});

// 空更新和客户端指定所有者均不得进入服务；设置布尔值不能由字符串冒充。
test('资料和设置部分更新拒绝空请求与越权字段',()=>{
  assert.ok(validate('ProfileInput',{nickname:'测试用户',bio:'你好'}));
  assert.ok(!validate('ProfileInput',{}));
  assert.ok(!validate('ProfileInput',{nickname:'   '}));
  assert.ok(!validate('ProfileInput',{nickname:'测试',accountId:id}));
  assert.ok(validate('SettingsInput',{allowStrangerDm:false}));
  assert.ok(!validate('SettingsInput',{}));
  assert.ok(!validate('SettingsInput',{pushEnabled:'false'}));
  assert.ok(!validate('SettingsInput',{allowStrangerDm:true,accountId:id}));
});
