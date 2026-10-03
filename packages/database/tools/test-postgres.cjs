// 使用独立临时PostgreSQL实例验证迁移与Prisma读写，不接受外部数据库URL。
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {spawn,spawnSync}=require('node:child_process');
const {randomUUID}=require('node:crypto');
const assert=require('node:assert/strict');
const net=require('node:net');
const {Client}=require('pg');
const root=path.resolve(__dirname,'..');
const bin=process.env.PG_BIN_DIR||'C:\\Program Files\\PostgreSQL\\16\\bin';
const executable=name=>path.join(bin,name+(process.platform==='win32'?'.exe':''));
function run(command,args,env=process.env){
 const result=spawnSync(command,args,{cwd:root,env,encoding:'utf8',windowsHide:true});
 if(result.error)throw result.error;
 if(result.status!==0)throw Error(`${path.basename(command)} failed: ${result.stderr||result.stdout}`);
 return result.stdout;
}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function freePort(){const server=net.createServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});const port=server.address().port;await new Promise(resolve=>server.close(resolve));return port;}
(async()=>{
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'youren-postgres-'));
 const data=path.join(temp,'data'),port=await freePort();
 let server,sql,prisma,ready=false;
 const checks=[];
 try{
  run(executable('initdb'),['-D',data,'--username=youren_test','--auth=trust','--encoding=UTF8','--locale=C']);
  // 仅绑定loopback，使用随机端口和当前测试专用空数据目录。
  const log=fs.openSync(path.join(temp,'postgres.log'),'a');
  server=spawn(executable('postgres'),['-D',data,'-h','127.0.0.1','-p',String(port)],{stdio:['ignore',log,log],windowsHide:true});fs.closeSync(log);
  let startupError;server.once('error',e=>startupError=e);
  for(let attempt=0;attempt<100;attempt++){
   if(startupError)throw startupError;
   const result=spawnSync(executable('pg_isready'),['-h','127.0.0.1','-p',String(port)],{windowsHide:true,stdio:'ignore'});
   if(result.status===0){ready=true;break;}await delay(100);
  }
  if(!ready)throw Error('Temporary PostgreSQL failed to become ready: '+fs.readFileSync(path.join(temp,'postgres.log'),'utf8'));
  const env={...process.env,DATABASE_URL:`postgresql://youren_test@127.0.0.1:${port}/postgres?schema=public`};
  const cli=require.resolve('prisma/build/index.js');
  // migrate deploy而不是直接执行SQL，验证实际Prisma迁移历史与重复部署。
  run(process.execPath,[cli,'migrate','deploy','--schema','prisma/schema.prisma'],env);
  run(process.execPath,[cli,'migrate','deploy','--schema','prisma/schema.prisma'],env);
  run(process.execPath,[cli,'generate','--schema','prisma/schema.prisma'],env);
  sql=new Client({host:'127.0.0.1',port,user:'youren_test',database:'postgres'});await sql.connect();
  const {PrismaClient}=require('../generated/client');prisma=new PrismaClient({datasources:{db:{url:env.DATABASE_URL}}});
  const migrations=await sql.query('SELECT count(*)::integer n FROM _prisma_migrations WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL');assert.equal(migrations.rows[0].n,1);checks.push('empty migration and repeated deploy');
  const tables=await sql.query(`SELECT count(*)::integer n FROM information_schema.tables WHERE table_schema='public' AND table_name<>'_prisma_migrations' AND table_type='BASE TABLE'`);assert.equal(tables.rows[0].n,54);checks.push('54 tables and migration history');
  const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
  await prisma.accounts.create({data:{id:a,email_normalized:'one@example.test'}});
  await prisma.accounts.create({data:{id:b,email_normalized:'two@example.test'}});
  await assert.rejects(prisma.accounts.create({data:{email_normalized:'one@example.test'}}),e=>e.code==='P2002');
  await prisma.accounts.update({where:{id:a},data:{auth_version:2}});assert.equal((await prisma.accounts.findUnique({where:{id:a}})).auth_version,2);checks.push('Prisma create read update and unique rejection');
  const principal=await prisma.principals.create({data:{kind:'account',account_id:a}});
  const posted=await prisma.posts.create({data:{author_principal_id:principal.id,title:'保留期测试',body:'原始正文',status:'visible',published_at:new Date('2028-02-29T08:00:00Z')}});
  assert.equal(posted.expires_at.toISOString(),'2029-02-28T08:00:00.000Z');
  const edited=await prisma.posts.update({where:{id:posted.id},data:{body:'编辑正文',expires_at:new Date('2035-01-01T00:00:00Z')}});assert.equal(edited.expires_at.toISOString(),posted.expires_at.toISOString());
  assert.equal((await prisma.posts.findUnique({where:{id:posted.id},include:{rel_author_principal_id:true}})).rel_author_principal_id.account_id,a);checks.push('Prisma relation and calendar-year trigger');
  await assert.rejects(sql.query(`INSERT INTO blocks(blocker_id,blocked_id) VALUES ($1,$1)`,[a]),{code:'23514'});
  await assert.rejects(sql.query(`INSERT INTO principals(kind,account_id) VALUES ('account',$1)`,[randomUUID()]),{code:'23503'});
  await assert.rejects(sql.query(`INSERT INTO age_declarations(account_id,status,statement_version) VALUES ($1,'verified','v1')`,[a]),{code:'23514'});checks.push('CHECK foreign key and self-declaration rejection');
  const match=await prisma.matches.create({data:{account_low_id:a,account_high_id:b}});
  await prisma.conversations.create({data:{type:'direct',account_low_id:a,account_high_id:b}});
  await prisma.conversations.create({data:{type:'dating',match_id:match.id,account_low_id:a,account_high_id:b}});checks.push('independent direct and dating conversations');
  const groupId=randomUUID();
  // 延迟约束在提交时检查：群、群主、准确人数与会话必须完整。
  await prisma.$transaction(async tx=>{
   await tx.groups.create({data:{id:groupId,creator_id:a,owner_id:a,name:'事务测试群',active_count:1}});
   await tx.groupMembers.create({data:{group_id:groupId,account_id:a,role:'owner'}});
   await tx.conversations.create({data:{type:'group',group_id:groupId}});
  });
  await assert.rejects(sql.query('UPDATE groups SET active_count=101 WHERE id=$1',[groupId]),{code:'23514'});
  const invalidGroup=randomUUID();await assert.rejects(prisma.groups.create({data:{id:invalidGroup,creator_id:a,owner_id:a,name:'缺少成员'}}));assert.equal(await prisma.groups.findUnique({where:{id:invalidGroup}}),null);checks.push('Prisma transaction and deferred constraint rollback');
  const disposable=await prisma.accounts.create({data:{email_normalized:'delete@example.test'}});await prisma.accounts.delete({where:{id:disposable.id}});assert.equal(await prisma.accounts.findUnique({where:{id:disposable.id}}),null);checks.push('Prisma delete');
  const version=(await sql.query('SHOW server_version')).rows[0].server_version;
  console.log(JSON.stringify({postgresVersion:version,tableCount:54,checks,externalDatabaseTouched:false},null,2));
 }finally{
  // 清理仅限本脚本mkdtemp创建的实例与目录；不按端口查找或停止其他服务。
  if(prisma)await prisma.$disconnect();if(sql)await sql.end();
  if(ready)run(executable('pg_ctl'),['-D',data,'stop','-m','fast','-w']);
  else if(server&&server.exitCode===null)server.kill();
  if(path.dirname(temp)!==os.tmpdir()||!path.basename(temp).startsWith('youren-postgres-'))throw Error('Unsafe cleanup path');
  fs.rmSync(temp,{recursive:true,force:true});
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
