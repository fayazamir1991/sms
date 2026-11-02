import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {SQLiteStore} from '../src/sqlite.mjs';
import {settings} from '../src/runtime.mjs';
import {handle} from '../src/worker.mjs';
import {sha256} from '../src/crypto.mjs';

test('Docker configuration requires stable secrets and a public HTTPS origin',()=>{
 const env={NODE_ENV:'production',PUBLIC_URL:'https://sms.example.test',SERVER_RUNTIME_SECRETS:JSON.stringify({DATA_ENCRYPTION_KEY:Buffer.alloc(32,1).toString('base64'),SESSION_SIGNING_KEY:'a'.repeat(48),ADMIN_TOKEN_SHA256:'b'.repeat(64)})};
 const result=settings(env);assert.equal(result.port,8080);assert.equal(result.database,'/data/delneveshte.sqlite');
 for(const PUBLIC_URL of ['http://example.com','https://example.com/path','https://user:pass@example.com'])assert.throws(()=>settings({...env,PUBLIC_URL}));
 assert.throws(()=>settings({...env,SERVER_RUNTIME_SECRETS:'{}'}));assert.throws(()=>settings({...env,SERVER_RUNTIME_SECRETS:'bad'}));
});

test('persistent SQLite migrations and seed preserve admin edits and private logs across restarts',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-runtime-')),file=join(dir,'messages.sqlite');let db;
 try{
  db=new SQLiteStore(file);db.migrate();
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1002);
  const row=await db.prepare('SELECT id FROM catalog_messages LIMIT 1').first();
  await db.prepare('UPDATE catalog_messages SET body=? WHERE id=?').bind('متن مدیر محفوظ می‌ماند 🌷',row.id).run();
  const env={DB:db,DATA_ENCRYPTION_KEY:Buffer.alloc(32,2).toString('base64'),SESSION_SIGNING_KEY:'a'.repeat(48),ADMIN_TOKEN_SHA256:await sha256('test-password-only')};
  const authorization='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'a'.repeat(64);
  const post=(path,body)=>new Request('https://sms.example.test'+path,{method:'POST',headers:{Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await handle(post('/api/v1/installations/register',{app_version:'0.3.0'}),env)).status,201);
  const message={id:crypto.randomUUID(),body:'با تو زندگی زیباست 🌷❤️',recipient:'+989121234567',sim_id:7,status:'SENT',revision:3,approved_at:1000,submitted_at:2000,events:[]};
  assert.equal((await handle(post('/api/v1/sync',{messages:[message]}),env)).status,200);
  db.close();db=new SQLiteStore(file);db.migrate();
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM app_migrations').first()).n,11);
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1002);
  assert.equal((await db.prepare('SELECT body FROM catalog_messages WHERE id=?').bind(row.id).first()).body,'متن مدیر محفوظ می‌ماند 🌷');
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM sent_logs').first()).n,1);
  const privateRow=await db.prepare('SELECT body_cipher,recipient_cipher FROM queued_messages').first();
  assert.ok(!privateRow.body_cipher.includes(message.body));assert.ok(!privateRow.recipient_cipher.includes(message.recipient));
 }finally{db?.close();rmSync(dir,{recursive:true,force:true});}
});

test('SQLite batch rolls back every write when one statement fails',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-transaction-')),db=new SQLiteStore(join(dir,'db.sqlite'));db.migrate();
 try{
  const before=(await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n;
  await assert.rejects(db.batch([db.prepare('DELETE FROM catalog_messages'),db.prepare('INSERT INTO nonexistent_table VALUES(1)')]));
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,before);
 }finally{db.close();rmSync(dir,{recursive:true,force:true});}
});

test('catalog rebuild deletes old suggestions once and preserves installation records',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-reset-')),db=new SQLiteStore(join(dir,'db.sqlite'));db.migrate();
 try{
  await db.prepare("INSERT INTO installations(id,token_hash,app_version,created_at,last_seen_at) VALUES('preserved','secret-hash','0.4.0',1,1)").run();
  await db.prepare("INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES('old','https://old.example old body','old','شعر',1,1)").run();
  await db.prepare("DELETE FROM app_migrations WHERE name='0006_clean_catalog.sql'").run();db.migrate();
  assert.equal(await db.prepare("SELECT id FROM catalog_messages WHERE id='old'").first(),null);
  assert.equal((await db.prepare("SELECT id FROM installations WHERE id='preserved'").first()).id,'preserved');
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1002);
  const row=await db.prepare('SELECT id FROM catalog_messages LIMIT 1').first();await db.prepare('UPDATE catalog_messages SET body=? WHERE id=?').bind('ویرایش پس از بازسازی',row.id).run();db.migrate();assert.equal((await db.prepare('SELECT body FROM catalog_messages WHERE id=?').bind(row.id).first()).body,'ویرایش پس از بازسازی');
 }finally{db.close();rmSync(dir,{recursive:true,force:true});}
});

test('explicit full rebuild clears installations, synced queue, event logs and catalog exactly once',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-full-reset-')),db=new SQLiteStore(join(dir,'db.sqlite'));db.migrate();
 try{
  await db.prepare("INSERT INTO installations(id,token_hash,app_version,created_at,last_seen_at) VALUES('erase','hash','old',1,1)").run();
  await db.prepare("INSERT INTO queued_messages(installation_id,id,body_cipher,recipient_cipher,content_hash,status,sim_id,revision,approved_at,updated_at) VALUES('erase','msg','cipher','cipher','hash','SENT',1,1,1,1)").run();
  await db.prepare("INSERT INTO sent_logs VALUES('erase','msg',1,1)").run();
  await db.prepare("INSERT INTO message_events VALUES('erase','event','msg','SENT',1,1)").run();
  await db.prepare("DELETE FROM app_migrations WHERE name='0008_full_rebuild.sql'").run();db.migrate();
  for(const table of ['installations','queued_messages','sent_logs','message_events'])assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM '+table).first()).n,0);
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1002);
 }finally{db.close();rmSync(dir,{recursive:true,force:true});}
});

test('production SQLite returns CTE browse/search rows and paginates all real seeded messages',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-browse-')),db=new SQLiteStore(join(dir,'db.sqlite'));db.migrate();
 try{
  const env={DB:db,SESSION_SIGNING_KEY:'x'.repeat(48)},authorization='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'d'.repeat(64);
  const request=(path,body)=>new Request('https://sms.example.test'+path,{method:body?'POST':'GET',headers:{Authorization:authorization,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
  assert.equal((await handle(request('/api/v1/installations/register',{app_version:'0.9.0'}),env)).status,201);
  const first=await (await handle(request('/api/v1/messages/browse'),env)).json();assert.equal(first.messages.length,20);assert.ok(first.next_cursor);
  const next=await (await handle(request('/api/v1/messages/browse?cursor='+encodeURIComponent(first.next_cursor)),env)).json();assert.equal(next.messages.length,20);assert.equal(new Set([...first.messages,...next.messages].map(r=>r.id)).size,40);
  for(const q of ['عشق',first.messages[0].body.slice(0,12)]){
   const response=await handle(request('/api/v1/messages/browse?q='+encodeURIComponent(q)),env);assert.equal(response.status,200);
   const page=await response.json();assert.ok(page.messages.length>0,'Expected production search hits for '+q);
  }
  const cte=await db.prepare('WITH sample AS (SELECT 42 AS n) SELECT n FROM sample').all();assert.equal(cte.results[0].n,42);
 }finally{db.close();rmSync(dir,{recursive:true,force:true});}
});
