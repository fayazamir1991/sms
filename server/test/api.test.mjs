import test from 'node:test';
import assert from 'node:assert/strict';
import {handle} from '../src/worker.mjs';
import {sha256,bytesToBase64} from '../src/crypto.mjs';
import {TestD1} from './d1.mjs';

const origin='https://sms.example.test';
const installation='141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e';
const secret='a'.repeat(64);
const auth=`Bearer ${installation}.${secret}`;
const adminPassword='a-long-random-development-test-password';
async function environment(){return {DB:new TestD1(),DATA_ENCRYPTION_KEY:bytesToBase64(new Uint8Array(32)),SESSION_SIGNING_KEY:'local-test-session-signing-key-never-use-in-production',ADMIN_TOKEN_SHA256:await sha256(adminPassword)};}
const request=(path,body,headers={})=>new Request(origin+path,{method:body===undefined?'GET':'POST',headers:{Authorization:auth,...(body===undefined?{}:{'Content-Type':'application/json'}),...headers},body:body===undefined?undefined:JSON.stringify(body)});
async function register(env,headers={}){return handle(request('/api/v1/installations/register',{app_version:'0.3.0',device_hash:'d'.repeat(64)},headers),env);}
const message=(revision=1,status='APPROVED')=>({id:'521ac542-0c04-44f2-9b7b-3dd365df89ef',body:'دوستت دارم؛ با تو روزم قشنگ‌تر است ❤️',recipient:'+989121234567',sim_id:7,revision,status,approved_at:1793510000000,planned_at:1793511000000,submitted_at:status==='SENT'?1793511000000:null,events:[{id:'84e67d3b-b2fd-41d6-8942-4283508b9b58',status,revision,occurred_at:1793510000000}]});

test('registration is idempotent and an installation cannot be claimed with another credential',async()=>{
 const env=await environment();
 assert.equal((await register(env)).status,201);assert.equal((await register(env)).status,200);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM installations').first()).n,1);
 assert.equal((await register(env,{Authorization:`Bearer ${installation}.${'b'.repeat(64)}`})).status,409);
 env.DB.close();
});

test('suggestions require installation authentication and respect active categories',async()=>{
 const env=await environment();await register(env);
 await env.DB.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,?,?)').bind(crypto.randomUUID(),'لبخندت را دوست دارم 😊','test-1','کوتاه',1,Date.now()).run();
 assert.equal((await handle(request('/api/v1/messages/suggestions',{},{Authorization:''}),env)).status,404);
 assert.equal((await handle(request('/api/v1/messages/suggestions',undefined,{Authorization:''}),env)).status,401);
 const response=await handle(request('/api/v1/messages/suggestions?category='+encodeURIComponent('کوتاه')+'&limit=5'),env);
 assert.equal(response.status,200);assert.equal((await response.json()).messages.length,1);
 env.DB.close();
});

test('sync encrypts private message data and retries do not duplicate messages, events or sent logs',async()=>{
 const env=await environment();await register(env);const sent=message(4,'SENT');
 for(let i=0;i<2;i++){const r=await handle(request('/api/v1/sync',{messages:[sent]}),env);assert.equal(r.status,200);assert.equal((await r.json()).acks[0].revision,4);}
 const row=await env.DB.prepare('SELECT * FROM queued_messages').first();
 assert.ok(!row.body_cipher.includes('دوستت'));assert.ok(!row.recipient_cipher.includes('98912'));
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM queued_messages').first()).n,1);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM message_events').first()).n,1);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM sent_logs').first()).n,1);
 const stale=message(2,'SCHEDULED');stale.events=[];
 assert.equal((await handle(request('/api/v1/sync',{messages:[stale]}),env)).status,200);
 assert.equal((await env.DB.prepare('SELECT status FROM queued_messages').first()).status,'SENT');
 env.DB.close();
});

test('an immutable approved destination cannot be replaced during synchronization',async()=>{
 const env=await environment();await register(env);
 await handle(request('/api/v1/sync',{messages:[message()]}),env);
 const changed=message(2);changed.recipient='+989351234567';
 assert.equal((await handle(request('/api/v1/sync',{messages:[changed]}),env)).status,409);
 assert.equal((await env.DB.prepare('SELECT revision FROM queued_messages').first()).revision,1);
 env.DB.close();
});

test('one installation cannot alter another installation records by supplying its id',async()=>{
 const env=await environment();await register(env);await handle(request('/api/v1/sync',{messages:[message()]}),env);
 const other='815078b1-10e5-4f15-a404-d331fb7b30f9';
 await register(env,{Authorization:`Bearer ${other}.${secret}`});
 const m=message();m.installation_id=installation;
 assert.equal((await handle(request('/api/v1/sync',{messages:[m]},{Authorization:`Bearer ${other}.${secret}`}),env)).status,200);
 assert.equal((await env.DB.prepare('SELECT COUNT(*) AS n FROM queued_messages').first()).n,2);
 env.DB.close();
});

test('admin records require a session and mutations also require same-origin CSRF verification',async()=>{
 const env=await environment();await register(env);await handle(request('/api/v1/sync',{messages:[message(3,'SENT')]}),env);
 assert.equal((await handle(request('/api/admin/tables/queue'),env)).status,401);
 const login=await handle(request('/api/admin/login',{password:adminPassword},{Origin:origin}),env);
 assert.equal(login.status,200);const {csrf}=await login.json(),cookie=login.headers.get('Set-Cookie').split(';')[0];
 const headers={Cookie:cookie,Origin:origin,'X-CSRF':csrf};
 const table=await handle(request('/api/admin/tables/queue',undefined,headers),env);
 assert.equal(table.status,200);const data=await table.json();assert.equal(data.rows[0].recipient,message().recipient);assert.equal(data.rows[0].body,message().body);
 assert.equal((await handle(request('/api/admin/catalog',{body:'قدر لبخندت را می‌دانم 🌷',category:'قدردانی'},{Cookie:cookie,Origin:origin}),env)).status,403);
 assert.equal((await handle(request('/api/admin/catalog',{body:'قدر لبخندت را می‌دانم 🌷',category:'قدردانی'},headers),env)).status,200);
 assert.equal((await handle(request('/api/admin/login',{password:adminPassword},{Origin:'https://bad.example'}),env)).status,403);
 env.DB.close();
});

test('a database outage returns a retryable error without echoing personal data',async()=>{
 const env=await environment();env.DB={prepare(){throw new Error('private diagnostic with recipient');}};
 const response=await handle(request('/api/v1/sync',{messages:[message()]}),env);
 assert.equal(response.status,503);assert.ok(!(await response.text()).includes('private diagnostic'));
});

test('suggestion batches prefer content not previously offered to the same installation',async()=>{
 const env=await environment();await register(env);
 for(let i=0;i<12;i++)await env.DB.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,1,?)').bind(crypto.randomUUID(),'پیام تازه '+i+' 💕','rotation-'+i,'کوتاه',Date.now()).run();
 const get=async()=>new Set((await (await handle(request('/api/v1/messages/suggestions?limit=5'),env)).json()).messages.map(r=>r.id));
 const first=await get(),second=await get();assert.equal(first.size,5);assert.equal(second.size,5);assert.ok([...first].every(id=>!second.has(id)));
 env.DB.close();
});

test('browse pages contain 20 distinct items and a stable cursor appends without repeats',async()=>{
 const env=await environment();await register(env);
 for(let i=0;i<47;i++)await env.DB.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at,search_text) VALUES(?,?,?,?,1,?,?)').bind(crypto.randomUUID(),'دلم کنار تو آرام است '+i,'browse-'+i,'کوتاه',Date.now(),'دلم کنار تو آرام است '+i).run();
 let path='/api/v1/messages/browse',seen=new Set(),sizes=[];
 for(;;){const response=await handle(request(path),env);assert.equal(response.status,200);const page=await response.json();sizes.push(page.messages.length);for(const row of page.messages){assert.ok(!seen.has(row.id));seen.add(row.id);}if(!page.has_more)break;path='/api/v1/messages/browse?cursor='+encodeURIComponent(page.next_cursor);}
 assert.deepEqual(sizes,[20,20,7]);assert.equal(seen.size,47);env.DB.close();
});
test('substring search matches the middle of a phrase, normalizes Persian and treats wildcard input literally',async()=>{
 const env=await environment();await register(env);
 const {normalizeSearch}=await import('../src/catalog-search.mjs');
 for(const [i,body]of ['قبل از بَاران کنار تو آرامم بعد از باران','پیشوند كي خوب است پسوند','عشق 100% با تو','عشق عادی'].entries())await env.DB.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at,search_text) VALUES(?,?,?,?,1,?,?)').bind(crypto.randomUUID(),body,'search-'+i,'صمیمی',Date.now(),normalizeSearch(body)).run();
 const query=async q=>(await (await handle(request('/api/v1/messages/browse?q='+encodeURIComponent(q)),env)).json()).messages;
 assert.equal((await query('باران')).length,1);assert.equal((await query('کی')).length,1);assert.equal((await query('%')).length,1);assert.equal((await query("' OR 1=1 --")).length,0);env.DB.close();
});
test('browse cursor cannot be used with a different query or modified signature',async()=>{
 const env=await environment();await register(env);for(let i=0;i<21;i++)await env.DB.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,1,?)').bind(crypto.randomUUID(),'پیام '+i,'cursor-'+i,'کوتاه',Date.now()).run();
 const first=await (await handle(request('/api/v1/messages/browse'),env)).json();
 assert.equal((await handle(request('/api/v1/messages/browse?q=other&cursor='+encodeURIComponent(first.next_cursor)),env)).status,410);
 assert.equal((await handle(request('/api/v1/messages/browse?cursor='+encodeURIComponent(first.next_cursor+'bad')),env)).status,410);env.DB.close();
});
