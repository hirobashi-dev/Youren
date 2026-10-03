const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {randomUUID}=require('node:crypto');
const {PGlite}=require('@electric-sql/pglite');
test('PostgreSQL retention, member boundaries and media constraints',async suite=>{
 const db=new PGlite(); const a=randomUUID(),group=randomUUID(),conversation=randomUUID(),principal=randomUUID(),post=randomUUID();
 const reject=sql=>assert.rejects(sql,{code:'23514'});
 try {
  await db.exec(fs.readFileSync(path.join(__dirname,'../prisma/migrations/202610030001_initial/migration.sql'),'utf8'));
  await db.query(`INSERT INTO accounts(id,email_normalized) VALUES ($1,'test@example.test')`,[a]);
  await db.query(`INSERT INTO principals(id,kind,account_id) VALUES ($1,'account',$2)`,[principal,a]);
  await suite.test('all 54 tables and six policy defaults exist',async()=>{
   assert.equal((await db.query(`SELECT count(*)::integer n FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'`)).rows[0].n,54);
   const policies=(await db.query('SELECT key,value FROM policy_configs')).rows;assert.equal(policies.length,6);assert.equal(policies.find(p=>p.key==='ordinary_group_capacity').value,100);
  });
  await suite.test('group owner and conversation must commit atomically',async()=>{
   await db.transaction(async tx=>{
    await tx.query(`INSERT INTO groups(id,creator_id,owner_id,name,active_count) VALUES ($1,$2,$2,'测试群',1)`,[group,a]);
    await tx.query(`INSERT INTO group_members(group_id,account_id,role) VALUES ($1,$2,'owner')`,[group,a]);
    await tx.query(`INSERT INTO conversations(id,type,group_id) VALUES ($1,'group',$2)`,[conversation,group]);
   });
   await reject(db.query(`UPDATE groups SET active_count=101 WHERE id=$1`,[group]));
   await db.query(`UPDATE groups SET status='readonly',dissolved_at=now() WHERE id=$1`,[group]);
   assert.equal((await db.query('SELECT status FROM groups WHERE id=$1',[group])).rows[0].status,'readonly');
  });
  await suite.test('single open membership and ordered history intervals',async()=>{
   const member=(await db.query('SELECT id FROM group_members WHERE group_id=$1',[group])).rows[0].id;
   await db.query('INSERT INTO membership_periods(group_member_id,start_sequence) VALUES ($1,1)',[member]);
   await assert.rejects(db.query('INSERT INTO membership_periods(group_member_id,start_sequence) VALUES ($1,2)',[member]),{code:'23505'});
   await reject(db.query('UPDATE membership_periods SET end_sequence=0,left_at=now() WHERE group_member_id=$1',[member]));
  });
  await suite.test('UTC calendar year and editing does not renew expiry',async()=>{
   await db.query(`INSERT INTO posts(id,author_principal_id,title,body,status,published_at) VALUES ($1,$2,'测试','正文','visible','2028-02-29T08:00:00Z')`,[post,principal]);
   const expires=(await db.query('SELECT expires_at FROM posts WHERE id=$1',[post])).rows[0].expires_at;assert.equal(expires.toISOString(),'2029-02-28T08:00:00.000Z');
   await db.query(`UPDATE posts SET body='修改',expires_at='2035-01-01' WHERE id=$1`,[post]);
   assert.equal((await db.query('SELECT expires_at FROM posts WHERE id=$1',[post])).rows[0].expires_at.toISOString(),expires.toISOString());
   await reject(db.query(`UPDATE posts SET published_at=now() WHERE id=$1`,[post]));
   await db.query(`INSERT INTO messages(conversation_id,sender_id,client_message_id,sequence,body,sent_at) VALUES ($1,$2,$3,1,'聊天','2028-02-29T08:00:00Z')`,[conversation,a,randomUUID()]);
   assert.equal((await db.query('SELECT expires_at FROM messages')).rows[0].expires_at.toISOString(),'2029-02-28T08:00:00.000Z');
  });
  await suite.test('reply cannot cross posts',async()=>{
   const other=(await db.query(`INSERT INTO posts(author_principal_id,title,body) VALUES ($1,'另一个','正文') RETURNING id`,[principal])).rows[0].id;
   const reply=(await db.query(`INSERT INTO comments(post_id,author_principal_id,body) VALUES ($1,$2,'回复') RETURNING id`,[post,principal])).rows[0].id;
   await reject(db.query(`INSERT INTO comments(post_id,author_principal_id,reply_to_id,body) VALUES ($1,$2,$3,'越界')`,[other,principal,reply]));
  });
  await suite.test('media limits, purpose and cross-object exclusive binding',async()=>{
   const asset=(await db.query(`INSERT INTO media_assets(owner_principal_id,purpose,object_key,status,mime,size_bytes,upload_expires_at) VALUES ($1,'board','test.jpg','uploaded','image/jpeg',1000,now()) RETURNING id`,[principal])).rows[0].id;
   await reject(db.query('UPDATE media_assets SET size_bytes=5000001 WHERE id=$1',[asset]));
   await db.query('INSERT INTO post_media(post_id,media_id,position) VALUES ($1,$2,0)',[post,asset]);
   await reject(db.query('INSERT INTO post_media(post_id,media_id,position) VALUES ($1,$2,9)',[post,asset]));
   const reply=(await db.query('SELECT id FROM comments LIMIT 1')).rows[0].id;
   await reject(db.query('INSERT INTO comment_media(comment_id,media_id,position) VALUES ($1,$2,0)',[reply,asset]));
   await reject(db.query(`INSERT INTO profiles(account_id,nickname,avatar_media_id) VALUES ($1,'头像',$2)`,[a,asset]));
  });
  await suite.test('self declaration never accepts verified status',async()=>{
   await reject(db.query(`INSERT INTO age_declarations(account_id,status,statement_version) VALUES ($1,'verified','v1')`,[a]));
   await db.query(`INSERT INTO age_declarations(account_id,statement_version) VALUES ($1,'v1')`,[a]);
   await db.query(`UPDATE age_declarations SET status='withdrawn',withdrawn_at='2026-10-03T08:00:00Z' WHERE account_id=$1`,[a]);
   assert.equal((await db.query('SELECT expires_at FROM age_declarations')).rows[0].expires_at.toISOString(),'2027-10-03T08:00:00.000Z');
  });
  await suite.test('event capacity, start boundaries and post-start left status',async()=>{
   await db.query(`INSERT INTO regions(code,level,name_ja,name_zh) VALUES ('JP-13','prefecture','東京都','东京都')`);
   const event=(await db.query(`INSERT INTO events(creator_id,title,description,region_code,starts_at,ends_at,registration_deadline,capacity,confirmed_count,cancellation_policy) VALUES ($1,'散步','说明','JP-13','2026-10-18T01:00:00Z','2026-10-18T03:00:00Z','2026-10-17T09:00:00Z',2,2,'开始前可取消') RETURNING id`,[a])).rows[0].id;
   await reject(db.query('UPDATE events SET capacity=1 WHERE id=$1',[event]));
   await reject(db.query('UPDATE events SET registration_deadline=ends_at WHERE id=$1',[event]));
   await db.query(`INSERT INTO event_registrations(event_id,account_id,status,accepted_event_version,left_at) VALUES ($1,$2,'left',1,now())`,[event,a]);
   assert.equal((await db.query('SELECT confirmed_count FROM events WHERE id=$1',[event])).rows[0].confirmed_count,2);
   await reject(db.query(`INSERT INTO group_owner_transfers(group_id,from_account_id,to_account_id,status) VALUES ($1,$2,$2,'accepted')`,[group,a]));
  });
 } finally {await db.close();}
});
