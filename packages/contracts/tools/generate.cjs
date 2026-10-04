// 从明确的DTO和操作清单生成合同；检查模式禁止静默覆盖手工修改。
const fs=require('node:fs');
const path=require('node:path');
const {schemas,ref,obj,arr,str,uuid}=require('../src/schemas.cjs');
const {operations}=require('../src/operations.cjs');
const envelope=s=>obj('统一成功响应，requestId用于脱敏追踪。',{data:s,requestId:str('请求追踪ID。')});
const pagination=obj('不透明游标分页。',{nextCursor:{type:['string','null']},hasMore:{type:'boolean'}});
for(const operation of operations.filter(x=>x.list)){
  const name=`${operation.result}Page`;
  schemas[name]=obj('仅返回有权、未过期的数据。',{data:arr(ref(operation.result)),page:pagination,requestId:str('请求追踪ID。')});
}
// 本人终态内容只允许占位字段；刷新只返回轮换令牌，不重复暴露账号资料。
schemas.OwnContent.oneOf[0].properties.status.enum=['pending','visible','rejected','hidden'];
schemas.RefreshSession=obj('刷新后的新令牌对。',{accessToken:str('短期秘密令牌。'),refreshToken:str('新单次刷新秘密令牌。')});
schemas.ProfileVersion=obj('本人普通资料更新结果。',{profile:ref('PublicProfile'),version:{type:'integer',minimum:1}});
schemas.Me.properties.profileVersion={type:['integer','null'],minimum:1,description:'普通资料当前版本；尚无资料时为null。供本人资料If-Match更新使用。'};
schemas.Me.required.push('profileVersion');
delete schemas.EventPatchInput.properties.saveAsDraft;
const doc={openapi:'3.1.0',jsonSchemaDialect:'https://json-schema.org/draft/2020-12/schema',info:{title:'Youren API 接口合同',version:'0.1.0',description:'面向日本中国用户的移动应用合同草案。接口尚未实现。文字长度按Unicode可见字符计算；x-grapheme-max需要服务端校验。所有对象访问必须重新验证权限。保留期为原始公开/发送时间起一个UTC日历年，闰日钳位，编辑不续期。标记待确认的内容不是已批准需求。'},servers:[{url:'/',description:'相对于未来部署地址；不表示已有运行服务。'}],paths:{},components:{securitySchemes:{UserBearer:{type:'http',scheme:'bearer',bearerFormat:'JWT',description:'注册账号访问令牌；不得接受管理端audience。'},GuestToken:{type:'apiKey',in:'header',name:'X-Guest-Token',description:'本机游客秘密凭证，禁止放入URL或日志。'},AdminBearer:{type:'http',scheme:'bearer',bearerFormat:'JWT',description:'独立管理端audience和角色，普通账号令牌无效。'}},schemas}};
const auth={P:[],G:[{GuestToken:[]},{UserBearer:[]}],U:[{UserBearer:[]}],D:[{UserBearer:[]}],A:[{AdminBearer:[]}]};
// 错误名称沿用详细设计；额外幂等/配额错误是工程级细化。
const errors={400:['INVALID_CURSOR','INVALID_REQUEST'],401:['AUTH_REQUIRED','SESSION_REVOKED'],403:['ACCOUNT_RESTRICTED','DATING_REQUIRED','FORBIDDEN'],404:['CONTENT_UNAVAILABLE'],409:['EVENT_FULL','EVENT_CLOSED','GROUP_FULL','MEDIA_NOT_READY','REQUEST_IN_PROGRESS','RESULT_EXPIRED','QUOTA_EXCEEDED','IDEMPOTENCY_CONFLICT'],412:['VERSION_CONFLICT'],422:['VALIDATION_FAILED'],428:['VERSION_REQUIRED'],429:['RATE_LIMITED'],503:['TEMPORARILY_UNAVAILABLE']};
const examples={AgeInput:{accepted:true,statementVersion:'2026-10-03'},PostInput:{title:'东京周末一起散步',body:'寻找喜欢散步的朋友。',category:'city'},ReplyInput:{body:'我也感兴趣。'},DirectInput:{targetAccountId:'11111111-1111-7111-8111-111111111111',source:'publicProfile'},MessageInput:{clientMessageId:'11111111-1111-7111-8111-111111111111',body:'你好，很高兴认识你。'},RegistrationInput:{acceptedEventVersion:1,acceptedRules:true}};
for(const o of operations){
  const params=[...o.path.matchAll(/\{([^}]+)\}/g)].map(([,name])=>({name,in:'path',required:true,schema:name==='kind'?{type:'string',enum:['posts','comments']}:uuid(),description:'标识不代表访问授权。'}));
  params.push(...(o.query||[]));
  if(o.idem)params.push({name:'Idempotency-Key',in:'header',required:true,schema:uuid(),description:'同一主体、操作及请求内容的重试复用同键；不同内容同键返回409。具体保存窗口可配置。'});
  if(o.version)params.push({name:'If-Match',in:'header',required:true,schema:{type:'string',pattern:o.path==='/me/dating-profile'?'^"(0|[1-9][0-9]*)"$':'^"[1-9][0-9]*"$'},description:'带双引号的资源版本；缺失428，不匹配412。恋爱首次创建使用"0"。'});
  const result=o.path==='/auth/refresh'?'RefreshSession':o.path==='/me/profile'?'ProfileVersion':o.result;
  let success=o.list?ref(`${result}Page`):envelope(typeof result==='string'?ref(result):result);
  if(o.messagePage)success=obj('消息扫描可跨过不可见序号，避免客户端反复获取空页。',{...schemas.MessagePage.properties,scannedThroughSequence:{type:'string',pattern:'^(0|[1-9][0-9]*)$'} });
  const status=o.status||200;
  const responses={[status]:{description:status===204?'成功，无响应正文。':'成功；异步202仅表示受理。',...(status!==204?{content:{'application/json':{schema:success}}}:{})}};
  if(o.alternate)responses[o.alternate]={description:'返回已有或幂等结果。',content:{'application/json':{schema:success}}};
  const codes=new Set([400,422,429,503,...(o.auth!=='P'?[401,403]:[]),...(o.path.includes('{')?[404]:[]),...(o.method!=='get'?[409]:[]),...(o.version?[412,428]:[]),...(o.errors||[])]);
  for(const code of codes)responses[code]={description:errors[code].join(' / '),...(code===429?{headers:{'Retry-After':{description:'重试等待秒数。',schema:{type:'integer',minimum:0}}}}:{}),content:{'application/json':{schema:{allOf:[ref('Error'),{properties:{code:{type:'string',enum:errors[code]}}}]}}}};
  const operation={operationId:`${o.method}_${o.path.replace(/[{}]/g,'').replace(/[^a-zA-Z0-9]+/g,'_').replace(/^_/, '')}`,summary:o.summary,description:[o.description,o.owner?'必须验证当前主体对资源的所有权/管理资格。':null,o.auth==='D'?'要求主动开启恋爱、有效self_declared年龄声明及对应访问资格；不得称为verified。':null,o.auth==='A'?'独立管理员身份和对应角色授权；审计角色只读。':null,o.decision?`待确认：${o.decision}`:null].filter(Boolean).join('\n')||'每次读取及写入重新验证身份、对象状态和权限。',tags:[o.path.split('/')[1]],security:o.optionalAuth?[{},...auth.G]:auth[o.auth],parameters:params,responses,'x-authorization-level':o.auth,...(o.decision?{'x-decision-status':'pending'}:{})};
  if(o.input)operation.requestBody={required:true,description:'只接受明确字段；所有字符串按纯文本处理。',content:{'application/json':{schema:ref(o.input),...(examples[o.input]?{example:examples[o.input]}:{})}}};
  (doc.paths[`/v1${o.path}`]??={})[o.method]=operation;
}
const output=path.resolve(__dirname,'../openapi.json');
const serialized=JSON.stringify(doc,null,2)+'\n';
if(process.argv.includes('--check')){if(!fs.existsSync(output)||fs.readFileSync(output,'utf8')!==serialized)throw new Error('合同与源文件不一致，请运行npm run build。');console.log('合同生成一致性检查通过。');}
else {fs.writeFileSync(output,serialized);console.log(`已生成 ${operations.length} 个操作的 OpenAPI 合同。`);}
