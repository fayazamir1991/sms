import {sha256,equal,normalizeBody,encrypt,decrypt,hmac,bytesToBase64,base64ToBytes} from './crypto.mjs';

const STATES=new Set(['APPROVED','SCHEDULED','IMMEDIATE','SENDING','SENT','FAILED','UNCERTAIN','CANCELLED']);
const CATEGORIES=new Set(['کوتاه','صمیمی','قدردانی','دلتنگی','شعر']);
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HEX=/^[0-9a-f]{64}$/i;
const COOKIE='__Host-delneveshte_admin';
class HttpError extends Error {constructor(status,message){super(message);this.status=status;}}
const json=(body,status=200,headers={})=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',...headers}});
const requireValue=(condition,message='درخواست نامعتبر است.')=>{if(!condition)throw new HttpError(400,message);};
const number=(value,min=0,max=Number.MAX_SAFE_INTEGER)=>Number.isSafeInteger(value)&&value>=min&&value<=max;
async function readJson(request){
 requireValue(Number(request.headers.get('Content-Length')||0)<=256_000,'حجم درخواست بیش از حد مجاز است.');
 const raw=await request.text();requireValue(raw.length<=256_000,'حجم درخواست بیش از حد مجاز است.');
 try{const value=JSON.parse(raw);requireValue(value&&typeof value==='object'&&!Array.isArray(value));return value;}
 catch(error){if(error instanceof HttpError)throw error;throw new HttpError(400,'JSON نامعتبر است.');}
}
function credentials(request){
 const match=/^Bearer ([0-9a-f-]{36})\.([0-9a-f]{64})$/i.exec(request.headers.get('Authorization')||'');
 if(!match||!UUID.test(match[1]))throw new HttpError(401,'شناسهٔ نصب معتبر نیست.');
 return {id:match[1].toLowerCase(),secret:match[2].toLowerCase()};
}
async function authenticate(request,env){
 const credential=credentials(request);
 const installation=await env.DB.prepare('SELECT * FROM installations WHERE id=?').bind(credential.id).first();
 if(!installation||!installation.enabled||!equal(installation.token_hash,await sha256(credential.secret)))throw new HttpError(401,'دسترسی نصب تأیید نشد.');
 return installation;
}
async function register(request,env){
 const {id,secret}=credentials(request),body=await readJson(request);
 requireValue(typeof body.app_version==='string'&&body.app_version.length<=64);
 requireValue(body.device_hash==null||HEX.test(body.device_hash));
 const tokenHash=await sha256(secret),now=Date.now();
 const old=await env.DB.prepare('SELECT token_hash,enabled FROM installations WHERE id=?').bind(id).first();
 if(old&&(!old.enabled||!equal(old.token_hash,tokenHash)))throw new HttpError(409,'این شناسهٔ نصب قبلاً ثبت شده است.');
 await env.DB.prepare(`INSERT INTO installations(id,device_hash,token_hash,app_version,created_at,last_seen_at)
 VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET app_version=excluded.app_version,last_seen_at=excluded.last_seen_at
 WHERE installations.token_hash=excluded.token_hash AND installations.enabled=1`).bind(id,body.device_hash||null,tokenHash,body.app_version,now,now).run();
 const saved=await env.DB.prepare('SELECT token_hash FROM installations WHERE id=?').bind(id).first();
 if(!saved||!equal(saved.token_hash,tokenHash))throw new HttpError(409,'شناسهٔ نصب تکراری است.');
 return json({installation_id:id,registered:true},old?200:201);
}
async function suggestions(request,env){
 await authenticate(request,env);
 const url=new URL(request.url),category=url.searchParams.get('category')||'همه';
 const limit=Number(url.searchParams.get('limit')||'20');
 requireValue(number(limit,1,50));requireValue(category==='همه'||CATEGORIES.has(category));
 const query=category==='همه'
  ?env.DB.prepare('SELECT c.id,c.body,c.category,o.source,o.author,o.source_url,o.rights,o.language,o.original_text,o.original_language,o.translation_provider,o.license_url FROM catalog_messages c LEFT JOIN catalog_origins o ON o.catalog_id=c.id WHERE c.active=1 ORDER BY RANDOM() LIMIT ?').bind(limit)
  :env.DB.prepare('SELECT c.id,c.body,c.category,o.source,o.author,o.source_url,o.rights,o.language,o.original_text,o.original_language,o.translation_provider,o.license_url FROM catalog_messages c LEFT JOIN catalog_origins o ON o.catalog_id=c.id WHERE c.active=1 AND c.category=? ORDER BY RANDOM() LIMIT ?').bind(category,limit);
 const {results}=await query.all();return json({messages:results,source:'server'});
}
function validateMessage(m){
 requireValue(m&&UUID.test(m.id)&&STATES.has(m.status));
 requireValue(typeof m.body==='string'&&m.body.trim().length>0&&m.body.length<=2400);
 requireValue(typeof m.recipient==='string'&&/^\+?[0-9]{7,15}$/.test(m.recipient));
 requireValue(number(m.revision,1)&&number(m.sim_id,0,2147483646)&&number(m.approved_at,1));
 for(const key of ['planned_at','submitted_at'])requireValue(m[key]==null||number(m[key]));
 requireValue(Array.isArray(m.events)&&m.events.length<=5);
 for(const e of m.events)requireValue(e&&UUID.test(e.id)&&STATES.has(e.status)&&number(e.revision,1,m.revision)&&number(e.occurred_at,1));
}
async function sync(request,env){
 const installation=await authenticate(request,env),body=await readJson(request);
 requireValue(Array.isArray(body.messages)&&body.messages.length>0&&body.messages.length<=5);
 requireValue(new Set(body.messages.map(m=>m.id)).size===body.messages.length);
 const statements=[],acks=[],now=Date.now();
 for(const m of body.messages){
  validateMessage(m);
  const hash=await sha256(JSON.stringify([m.body,m.recipient,m.sim_id,m.approved_at]));
  const old=await env.DB.prepare('SELECT content_hash FROM queued_messages WHERE installation_id=? AND id=?').bind(installation.id,m.id).first();
  if(old&&!equal(old.content_hash,hash))throw new HttpError(409,'محتوای یک پیام ثبت‌شده قابل تغییر نیست.');
  const encryptedBody=await encrypt(m.body,env),encryptedRecipient=await encrypt(m.recipient,env);
  statements.push(env.DB.prepare(`INSERT INTO queued_messages(installation_id,id,body_cipher,recipient_cipher,content_hash,status,sim_id,revision,approved_at,planned_at,submitted_at,updated_at)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(installation_id,id) DO UPDATE SET
   status=excluded.status,revision=excluded.revision,planned_at=excluded.planned_at,submitted_at=excluded.submitted_at,updated_at=excluded.updated_at
   WHERE queued_messages.revision<excluded.revision AND queued_messages.content_hash=excluded.content_hash`)
   .bind(installation.id,m.id,encryptedBody,encryptedRecipient,hash,m.status,m.sim_id,m.revision,m.approved_at,m.planned_at||null,m.submitted_at||null,now));
  for(const e of m.events)statements.push(env.DB.prepare(`INSERT OR IGNORE INTO message_events(installation_id,id,message_id,status,revision,occurred_at) VALUES(?,?,?,?,?,?)`)
   .bind(installation.id,e.id,m.id,e.status,e.revision,e.occurred_at));
  statements.push(env.DB.prepare(`INSERT OR IGNORE INTO sent_logs(installation_id,message_id,sent_at,received_at)
   SELECT installation_id,id,COALESCE(submitted_at,?),? FROM queued_messages WHERE installation_id=? AND id=?
   AND (status='SENT' OR EXISTS(SELECT 1 FROM message_events WHERE installation_id=? AND message_id=? AND status='SENT'))`)
   .bind(now,now,installation.id,m.id,installation.id,m.id));
  acks.push({id:m.id,revision:m.revision,event_ids:m.events.map(e=>e.id)});
 }
 statements.push(env.DB.prepare('UPDATE installations SET last_seen_at=? WHERE id=?').bind(now,installation.id));
 await env.DB.batch(statements);
 return json({acks,server_time:now});
}
function sameOrigin(request){
 if(request.headers.get('Origin')!==new URL(request.url).origin)throw new HttpError(403,'مبدأ درخواست مجاز نیست.');
}
async function adminSession(request,env){
 const cookie=(request.headers.get('Cookie')||'').split(';').map(s=>s.trim()).find(s=>s.startsWith(COOKIE+'='));
 if(!cookie)throw new HttpError(401,'ورود به پنل لازم است.');
 try{
  const [payload,signature]=decodeURIComponent(cookie.slice(COOKIE.length+1)).split('.');
  if(!equal(signature,await hmac(payload,env.SESSION_SIGNING_KEY)))throw new Error('signature');
  const session=JSON.parse(new TextDecoder().decode(base64ToBytes(payload)));
  if(!number(session.expires)||session.expires<Date.now()||typeof session.id!=='string')throw new Error('expired');
  return session;
 }catch{throw new HttpError(401,'نشست منقضی شده است.');}
}
async function adminLogin(request,env){
 sameOrigin(request);
 const body=await readJson(request);requireValue(typeof body.password==='string'&&body.password.length<=256);
 if(!HEX.test(env.ADMIN_TOKEN_SHA256||''))throw new HttpError(503,'ورود مدیر هنوز پیکربندی نشده است.');
 if(!equal(await sha256(body.password),env.ADMIN_TOKEN_SHA256))throw new HttpError(401,'رمز مدیریت نادرست است.');
 const session={id:crypto.randomUUID(),expires:Date.now()+8*60*60*1000};
 const payload=bytesToBase64(new TextEncoder().encode(JSON.stringify(session)));
 const token=payload+'.'+await hmac(payload,env.SESSION_SIGNING_KEY);
 return json({csrf:await hmac('csrf:'+session.id,env.SESSION_SIGNING_KEY)},200,{'Set-Cookie':`${COOKIE}=${encodeURIComponent(token)}; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800`});
}
async function adminApi(request,env,path){
 const session=await adminSession(request,env);
 if(request.method!=='GET'){
  sameOrigin(request);
  if(!equal(request.headers.get('X-CSRF')||'',await hmac('csrf:'+session.id,env.SESSION_SIGNING_KEY)))throw new HttpError(403,'اعتبار درخواست تأیید نشد.');
 }
 if(path==='/api/admin/session')return json({csrf:await hmac('csrf:'+session.id,env.SESSION_SIGNING_KEY)});
 if(path==='/api/admin/logout'&&request.method==='POST')return json({ok:true},200,{'Set-Cookie':`${COOKIE}=; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`});
 if(path==='/api/admin/stats'&&request.method==='GET'){
  const results=await env.DB.batch([
   env.DB.prepare('SELECT COUNT(*) AS total,COUNT(DISTINCT device_hash) AS devices FROM installations'),
   env.DB.prepare("SELECT COUNT(*) AS total FROM queued_messages WHERE status IN ('APPROVED','SCHEDULED')"),
   env.DB.prepare('SELECT COUNT(*) AS total FROM sent_logs'),env.DB.prepare('SELECT COUNT(*) AS total FROM catalog_messages WHERE active=1')
  ]);
  return json({installations:results[0].results[0].total,devices:results[0].results[0].devices,queue:results[1].results[0].total,sent:results[2].results[0].total,catalog:results[3].results[0].total});
 }
 if(path==='/api/admin/catalog'&&request.method==='POST'){
  const b=await readJson(request);requireValue(typeof b.body==='string'&&b.body.trim().length>0&&b.body.length<=2400&&CATEGORIES.has(b.category));
  requireValue(b.id==null||UUID.test(b.id));requireValue(b.active==null||typeof b.active==='boolean');
  requireValue(!/https?:|www\.|[a-z0-9-]+\.(com|ir|net|org)\b/i.test(b.body)&&!/[a-z]{3,}/i.test(b.body),'متن پیام باید فارسی و بدون لینک باشد.');
  const id=b.id||crypto.randomUUID(),fp=await sha256(normalizeBody(b.body));
  const existing=await env.DB.prepare('SELECT id FROM catalog_messages WHERE fingerprint=?').bind(fp).first();
  if(existing&&existing.id!==id)throw new HttpError(409,'این متن قبلاً در بانک پیام‌ها ثبت شده است.');
  await env.DB.prepare(`INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,?,?)
   ON CONFLICT(id) DO UPDATE SET body=excluded.body,fingerprint=excluded.fingerprint,category=excluded.category,active=excluded.active`)
   .bind(id,b.body.trim(),fp,b.category,b.active===false?0:1,Date.now()).run();return json({id});
 }
 if(path.startsWith('/api/admin/tables/')&&request.method==='GET'){
  const table=path.split('/').pop(),url=new URL(request.url),page=Number(url.searchParams.get('page')||1);
  requireValue(number(page,1,100000));
  const queries={
   webpages:'SELECT source,url,fetched_at,done FROM catalog_web_pages ORDER BY fetched_at,url',
   imports:'SELECT job,run_key,status,started_at,finished_at,added,detail FROM catalog_jobs ORDER BY started_at DESC',
   catalog:'SELECT c.id,c.body,c.category,c.active,c.created_at,o.source,o.source_url,o.author,o.language,o.rights FROM catalog_messages c LEFT JOIN catalog_origins o ON o.catalog_id=c.id ORDER BY c.created_at DESC,c.id',
   installations:'SELECT id,device_hash,app_version,enabled,created_at,last_seen_at FROM installations ORDER BY created_at DESC,id',
   queue:'SELECT installation_id,id,body_cipher,recipient_cipher,status,sim_id,revision,approved_at,planned_at,submitted_at,updated_at FROM queued_messages ORDER BY updated_at DESC,id',
   events:'SELECT installation_id,id,message_id,status,revision,occurred_at FROM message_events ORDER BY occurred_at DESC,id',
   sent:'SELECT l.installation_id,l.message_id,l.sent_at,l.received_at,q.body_cipher,q.recipient_cipher,q.status FROM sent_logs l JOIN queued_messages q ON q.installation_id=l.installation_id AND q.id=l.message_id ORDER BY l.received_at DESC,l.message_id'
  };
  if(!queries[table])throw new HttpError(404,'جدول پیدا نشد.');
  const {results}=await env.DB.prepare(queries[table]+' LIMIT 51 OFFSET ?').bind((page-1)*50).all();
  const rows=results.slice(0,50);
  for(const row of rows)if(row.body_cipher){row.body=await decrypt(row.body_cipher,env);row.recipient=await decrypt(row.recipient_cipher,env);delete row.body_cipher;delete row.recipient_cipher;}
  return json({rows,page,has_more:results.length>50});
 }
 throw new HttpError(404,'مسیر پیدا نشد.');
}
export async function handle(request,env){
 const path=new URL(request.url).pathname.replace(/\/$/,'')||'/';
 try{
  if(path==='/healthz')return json({ok:true,service:'delneveshte'});
  if(!env.DB)throw new HttpError(503,'دیتابیس سرور آماده نیست.');
  if(path==='/api/v1/installations/register'&&request.method==='POST')return await register(request,env);
  if(path==='/api/v1/messages/suggestions'&&request.method==='GET')return await suggestions(request,env);
  if(path==='/api/v1/sync'&&request.method==='POST')return await sync(request,env);
  if(path==='/api/admin/login'&&request.method==='POST')return await adminLogin(request,env);
  if(path.startsWith('/api/admin/'))return await adminApi(request,env,path);
  if(path.startsWith('/api/'))throw new HttpError(404,'مسیر پیدا نشد.');
  if(!env.ASSETS)throw new HttpError(404,'پنل پیدا نشد.');
  const assetUrl=new URL(request.url);if(path==='/'||path==='/admin')assetUrl.pathname='/index.html';
  const asset=await env.ASSETS.fetch(new Request(assetUrl,request));
  const headers=new Headers(asset.headers);headers.set('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
  headers.set('X-Content-Type-Options','nosniff');headers.set('Referrer-Policy','same-origin');
  return new Response(asset.body,{status:asset.status,headers});
 }catch(error){
  return json({error:error instanceof HttpError?error.message:'سرور موقتاً پاسخ‌گو نیست؛ داده‌ها را روی گوشی نگه دار و بعداً دوباره تلاش کن.'},error instanceof HttpError?error.status:503);
 }
}
export default {fetch:handle};
