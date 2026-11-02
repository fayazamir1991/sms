import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {gunzipSync} from 'node:zlib';
import {SQLiteStore} from '../src/sqlite.mjs';
import {ensureToneCatalog} from '../src/tone-catalog-seed.mjs';
import {tonePair,colloquialize} from '../src/catalog-tones.mjs';
import {handle} from '../src/worker.mjs';
import {sha256} from '../src/crypto.mjs';

test('2000 new paired compositions are distinct, Persian, reproducible and separate from poetry',()=>{
 const rows=gunzipSync(readFileSync(new URL('../data/tones/original-v1.ndjson.gz',import.meta.url))).toString('utf8').trim().split('\n').map(JSON.parse);
 assert.equal(rows.length,2000);assert.equal(new Set(rows.map(r=>r.id)).size,2000);
 for(const key of ['body_formal','body_colloquial']){
  assert.equal(new Set(rows.map(r=>r[key].replace(/[^\p{L}\p{N}]/gu,''))).size,2000);
  for(const row of rows){assert.ok(row[key].length>50&&row[key].length<450);assert.ok(!/[A-Za-z]|https?:|نمناک|گنجور/.test(row[key]));assert.match(row[key],/\p{Extended_Pictographic}/u);assert.notEqual(row.category,'شعر');}
 }
 assert.deepEqual(tonePair('یک شعر با آغوش و بوسه','شعر'),{body_formal:null,body_colloquial:null});
 assert.equal(colloquialize('دلم یک بوسه می‌خواهد.'),'دلم یه بوس می‌خواد.');
});

test('real SQLite backfill preserves old bodies, IDs, admin edits and private tables; reseed is idempotent',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-tones-'));let db;
 try{
  db=new SQLiteStore(join(dir,'db'));db.migrate();
  const old=(await db.prepare('SELECT id,body FROM catalog_messages ORDER BY id').all()).results;assert.equal(old.length,1002);
  const first=await ensureToneCatalog(db);assert.equal(first.backfilled,1002);assert.equal(first.added,2000);
  assert.equal((await db.prepare("SELECT COUNT(*) AS n FROM catalog_messages WHERE category!='شعر' AND body_formal IS NOT NULL AND body_colloquial IS NOT NULL").first()).n,3002);
  assert.deepEqual((await db.prepare('SELECT id,body FROM catalog_messages WHERE created_at=(SELECT MIN(created_at) FROM catalog_messages) ORDER BY id').all()).results,old);
  const newer=await db.prepare("SELECT catalog_id AS id FROM catalog_origins WHERE source_revision='original-tones-v1' LIMIT 1").first();
  await db.prepare('UPDATE catalog_messages SET body_formal=?,body_colloquial=?,active=0 WHERE id=?').bind('متن رسمی مدیر','متن محاوره‌ای مدیر',newer.id).run();
  // A missing field is repaired, without overwriting a manually authored sibling field.
  await db.prepare('UPDATE catalog_messages SET body_formal=?,body_colloquial=NULL WHERE id=?').bind('رسمی ویرایش‌شده',old[0].id).run();
  db.close();db=new SQLiteStore(join(dir,'db'));db.migrate();assert.equal((await ensureToneCatalog(db)).added,0);
  const edited=await db.prepare('SELECT body_formal,body_colloquial,active FROM catalog_messages WHERE id=?').bind(newer.id).first();assert.deepEqual(edited,{body_formal:'متن رسمی مدیر',body_colloquial:'متن محاوره‌ای مدیر',active:0});
  assert.equal((await db.prepare('SELECT body_formal FROM catalog_messages WHERE id=?').bind(old[0].id).first()).body_formal,'رسمی ویرایش‌شده');
 }finally{db?.close();rmSync(dir,{recursive:true,force:true});}
});

test('real SQLite searches only selected tone, normalizes Persian, keeps poetry identical and binds cursors to tone',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'sms-tone-search-'));let db;
 try{
  db=new SQLiteStore(join(dir,'db'));db.migrate();
  const env={DB:db,SESSION_SIGNING_KEY:'s'.repeat(48)},auth='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'a'.repeat(64);
  await handle(new Request('https://test.example/api/v1/installations/register',{method:'POST',headers:{Authorization:auth},body:JSON.stringify({app_version:'test'})}),env);
  const request=q=>handle(new Request('https://test.example/api/v1/messages/browse?'+new URLSearchParams(q),{headers:{Authorization:auth}}),env);
  for(let i=0;i<24;i++)await db.prepare('INSERT INTO catalog_messages(id,body,body_formal,body_colloquial,fingerprint,category,active,created_at) VALUES(?,?,?,?,?,?,1,?)').bind(crypto.randomUUID(),'قدیمی','دلم آغوش نقره‌فام تو را می‌خواهد '+i,'دلم بغل نقره‌فام تو رو می‌خواد '+i,await sha256('tone-'+i),'صمیمی',Date.now()).run();
  const prose=async(tone,q)=>(await (await request({tone,q,category:'صمیمی'})).json());
  assert.equal((await prose('colloquial','نقرهفام')).messages.length,20);
  assert.equal((await prose('colloquial','آغوش نقره')).messages.length,0);
  assert.equal((await prose('formal','بغل نقره')).messages.length,0);
  const first=await prose('formal','آغوش نقره');assert.equal(first.messages.length,20);
  assert.equal((await request({tone:'colloquial',q:'آغوش نقره',category:'صمیمی',cursor:first.next_cursor})).status,410);
  const next=await (await request({tone:'formal',q:'آغوش نقره',category:'صمیمی',cursor:first.next_cursor})).json();assert.equal(next.messages.length,4);assert.ok(next.messages.every(r=>!first.messages.some(p=>p.id===r.id)));
  const poem='شعر یگانهٔ آزمایشی از دِل‌تنگی';await db.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,1,?)').bind(crypto.randomUUID(),poem,'poem','شعر',Date.now()).run();
  for(const tone of ['formal','colloquial']){const page=await (await request({tone,q:'دلتنگی',category:'شعر'})).json();assert.equal(page.messages[0].body,poem);}
  assert.equal((await request({tone:'arbitrary'})).status,400);
 }finally{db?.close();rmSync(dir,{recursive:true,force:true});}
});
