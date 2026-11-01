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
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1009);
  const row=await db.prepare('SELECT id FROM catalog_messages LIMIT 1').first();
  await db.prepare('UPDATE catalog_messages SET body=? WHERE id=?').bind('متن مدیر محفوظ می‌ماند 🌷',row.id).run();
  const env={DB:db,DATA_ENCRYPTION_KEY:Buffer.alloc(32,2).toString('base64'),SESSION_SIGNING_KEY:'a'.repeat(48),ADMIN_TOKEN_SHA256:await sha256('test-password-only')};
  const authorization='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'a'.repeat(64);
  const post=(path,body)=>new Request('https://sms.example.test'+path,{method:'POST',headers:{Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify(body)});
  assert.equal((await handle(post('/api/v1/installations/register',{app_version:'0.3.0'}),env)).status,201);
  const message={id:crypto.randomUUID(),body:'با تو زندگی زیباست 🌷❤️',recipient:'+989121234567',sim_id:7,status:'SENT',revision:3,approved_at:1000,submitted_at:2000,events:[]};
  assert.equal((await handle(post('/api/v1/sync',{messages:[message]}),env)).status,200);
  db.close();db=new SQLiteStore(file);db.migrate();
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM app_migrations').first()).n,2);
  assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,1009);
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
