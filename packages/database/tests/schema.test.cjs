const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {PGlite}=require('@electric-sql/pglite');
const migration=path.join(__dirname,'../prisma/migrations/202610030001_initial/migration.sql');
test('migration enforces identity, capacity, conversation and retention constraints',async()=>{
 const db=new PGlite();
 try {
  await db.exec(fs.readFileSync(migration,'utf8'));
  const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
  await db.query(`INSERT INTO accounts(id,email_normalized) VALUES ($1,'one@example.test'),($2,'two@example.test')`,[a,b]);
  await assert.rejects(db.query(`INSERT INTO accounts(email_normalized) VALUES ('one@example.test')`),{code:'23505'});
  await assert.rejects(db.query(`INSERT INTO principals(kind) VALUES ('account')`),{code:'23514'});
  await assert.rejects(db.query(`INSERT INTO blocks(blocker_id,blocked_id) VALUES ($1,$1)`,[a]),{code:'23514'});
  await assert.rejects(db.query(`INSERT INTO conversations(type,account_low_id,account_high_id) VALUES ('direct',$2,$1)`,[a,b]),{code:'23514'});
  await db.query(`INSERT INTO conversations(type,account_low_id,account_high_id) VALUES ('direct',$1,$2)`,[a,b]);
  const match=(await db.query(`INSERT INTO matches(account_low_id,account_high_id) VALUES ($1,$2) RETURNING id`,[a,b])).rows[0].id;
  await db.query(`INSERT INTO conversations(type,match_id,account_low_id,account_high_id) VALUES ('dating',$1,$2,$3)`,[match,a,b]);
  await assert.rejects(db.query(`INSERT INTO conversations(type,account_low_id,account_high_id) VALUES ('direct',$1,$2)`,[a,b]),{code:'23505'});
  await assert.rejects(db.query(`INSERT INTO groups(creator_id,owner_id,name) VALUES ($1,$1,'测试群')`,[a]),{code:'23514'});
 } finally {await db.close();}
});
