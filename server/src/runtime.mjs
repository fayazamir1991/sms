import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {mkdirSync,readFileSync as readBinary,writeFileSync,renameSync,existsSync,statSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {handle} from './worker.mjs';
import {SQLiteStore} from './sqlite.mjs';
import {startCatalogJobs} from './catalog-jobs.mjs';
import {ensureToneCatalog} from './tone-catalog-seed.mjs';
import {importBundledGanjoor} from './catalog-import.mjs';

export function settings(input=process.env){
 let bundled={};
 try{if(input.SERVER_RUNTIME_SECRETS)bundled=JSON.parse(input.SERVER_RUNTIME_SECRETS);}
 catch{throw new Error('SERVER_RUNTIME_SECRETS must be a valid JSON object.');}
 const config={};
 for(const key of ['DATA_ENCRYPTION_KEY','SESSION_SIGNING_KEY','ADMIN_TOKEN_SHA256','APP_RELEASE_UPLOAD_TOKEN_SHA256'])config[key]=input[key]||bundled[key];
 if(!/^[A-Za-z0-9+/]{43}=$/.test(config.DATA_ENCRYPTION_KEY||'')||Buffer.from(config.DATA_ENCRYPTION_KEY,'base64').length!==32)
  throw new Error('Set DATA_ENCRYPTION_KEY to the preserved base64 32-byte key.');
 if(typeof config.SESSION_SIGNING_KEY!=='string'||config.SESSION_SIGNING_KEY.length<32)throw new Error('Set the preserved SESSION_SIGNING_KEY (at least 32 characters).');
 if(!/^[a-f0-9]{64}$/.test(config.ADMIN_TOKEN_SHA256||''))throw new Error('Set ADMIN_TOKEN_SHA256 to the SHA-256 hash of the admin password.');
 let url;try{url=new URL(input.PUBLIC_URL);}catch{throw new Error('Set PUBLIC_URL to the external HTTPS address of this service.');}
 const local=input.NODE_ENV!=='production'&&['127.0.0.1','localhost'].includes(url.hostname)&&url.protocol==='http:';
 if((url.protocol!=='https:'&&!local)||url.username||url.password||url.search||url.hash||url.pathname!=='/')throw new Error('PUBLIC_URL must be an HTTPS origin with no path or credentials.');
 const port=Number(input.PORT||8080);if(!Number.isInteger(port)||port<0||port>65535)throw new Error('PORT is invalid.');
 if(config.APP_RELEASE_UPLOAD_TOKEN_SHA256&&!/^[a-f0-9]{64}$/i.test(config.APP_RELEASE_UPLOAD_TOKEN_SHA256))throw new Error('APP_RELEASE_UPLOAD_TOKEN_SHA256 must be a SHA-256 hex digest.');
 const dataDir=resolve(input.DATA_DIR||'/data');return {...config,publicUrl:url.origin,port,host:input.HOST||'0.0.0.0',database:resolve(dataDir,'delneveshte.sqlite'),releaseDir:resolve(dataDir,'app-releases')};
}
function assets(){
 const paths=new Map([['/fonts/Vazirmatn.woff2',['fonts/Vazirmatn.woff2','font/woff2']],['/fonts/OFL.txt',['fonts/OFL.txt','text/plain; charset=utf-8']],['/index.html',['index.html','text/html; charset=utf-8']],['/admin.js',['admin.js','text/javascript; charset=utf-8']],['/admin.css',['admin.css','text/css; charset=utf-8']]]);
 return {async fetch(request){
  if(!['GET','HEAD'].includes(request.method))return new Response('Method not allowed',{status:405});
  const entry=paths.get(new URL(request.url).pathname);if(!entry)return new Response('Not found',{status:404});
  return new Response(request.method==='HEAD'?null:readFileSync(new URL('../public/'+entry[0],import.meta.url)),{headers:{'Content-Type':entry[1],'Cache-Control':'no-cache'}});
 }};
}
export async function startServer(input=process.env){
 const config=settings(input),db=new SQLiteStore(config.database);
 mkdirSync(config.releaseDir,{recursive:true});
 try{db.migrate();await importBundledGanjoor(db);await ensureToneCatalog(db);}catch(error){db.close();throw error;}
 const env={...config,DB:db,ASSETS:assets()};
 const server=createServer({maxHeaderSize:16384},async(incoming,outgoing)=>{
  try{
   if(!incoming.url?.startsWith('/')||incoming.url.startsWith('//')){outgoing.writeHead(400);outgoing.end();return;}
   const path=new URL(incoming.url,'http://localhost').pathname;const upload=path==='/api/admin/app-releases'&&incoming.method==='POST';
   const parts=[];let size=0;const max=upload?50*1024*1024:256000;
   for await(const chunk of incoming){size+=chunk.length;if(size>max){outgoing.writeHead(413);outgoing.end();return;}parts.push(chunk);}
   const method=incoming.method||'GET',headers=new Headers();
   for(const [key,value] of Object.entries(incoming.headers))if(value!==undefined)headers.set(key,Array.isArray(value)?value.join(','):value);
   const request=new Request(config.publicUrl+incoming.url,{method,headers,...(!['GET','HEAD'].includes(method)?{body:Buffer.concat(parts)}:{})});
   // Readiness reflects the persistent database, not only an open TCP port.
   if(new URL(request.url).pathname==='/healthz')await db.prepare('SELECT 1').first();
   let response=await handleAppRelease(request,config,Buffer.concat(parts));if(!response)response=await handle(request,env);
   outgoing.writeHead(response.status,Object.fromEntries(response.headers));
   outgoing.end(method==='HEAD'?undefined:Buffer.from(await response.arrayBuffer()));
  }catch{
   if(!outgoing.headersSent)outgoing.writeHead(503,{'Content-Type':'application/json','Cache-Control':'no-store'});
   outgoing.end(JSON.stringify({error:'Service temporarily unavailable.'}));
  }
 });
 server.requestTimeout=30000;server.headersTimeout=15000;server.keepAliveTimeout=5000;
 try{await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(config.port,config.host,resolve);});}
 catch(error){db.close();throw error;}
 const stopJobs=startCatalogJobs(db,input);
 return {server,db,async close(){await stopJobs();await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));db.close();}};
}
export async function handleAppRelease(request,config,body){
 const url=new URL(request.url),path=url.pathname;
 if(path==='/api/app/latest'&&['GET','HEAD'].includes(request.method)){
  const manifest=resolve(config.releaseDir,'latest.json');if(!existsSync(manifest))return new Response(JSON.stringify({version:'',url:'',sha256:''}),{headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
  return new Response(request.method==='HEAD'?null:readBinary(manifest),{headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}});
 }
 if(path.startsWith('/api/app/releases/')&&['GET','HEAD'].includes(request.method)){
  const name=path.slice('/api/app/releases/'.length);if(!/^delneveshte-v\d+\.\d+\.\d+\.apk$/.test(name))return new Response('Not found',{status:404});const file=resolve(config.releaseDir,name);if(!existsSync(file))return new Response('Not found',{status:404});
  return new Response(request.method==='HEAD'?null:readBinary(file),{headers:{'Content-Type':'application/vnd.android.package-archive','Content-Length':String(statSync(file).size),'Cache-Control':'public, max-age=3600','Content-Disposition':'attachment; filename="'+name+'"'}});
 }
 if(path==='/api/admin/app-releases'&&request.method==='POST'){
  const token=(request.headers.get('Authorization')||'').replace(/^Bearer /,'');if(!config.APP_RELEASE_UPLOAD_TOKEN_SHA256||!token||!safeEqual(await digest(token),config.APP_RELEASE_UPLOAD_TOKEN_SHA256.toLowerCase()))return Response.json({error:'unauthorized'},{status:401});
  const version=request.headers.get('X-Release-Version')||'',expected=(request.headers.get('X-APK-SHA256')||'').toLowerCase();if(!/^\d+\.\d+\.\d+$/.test(version)||!/^[a-f0-9]{64}$/.test(expected)||!body.length)return Response.json({error:'invalid release'},{status:400});
  const actual=await digest(body);if(!safeEqual(actual,expected))return Response.json({error:'checksum mismatch'},{status:422});const name='delneveshte-v'+version+'.apk',target=resolve(config.releaseDir,name);if(existsSync(target))return Response.json({error:'version already exists'},{status:409});const latestPath=resolve(config.releaseDir,'latest.json');if(existsSync(latestPath)){try{const current=JSON.parse(readBinary(latestPath,'utf8')).version;if(compareVersions(version,current)<=0)return Response.json({error:'release version must increase'},{status:409});}catch(error){if(error instanceof SyntaxError)return Response.json({error:'stored release manifest is invalid'},{status:500});throw error;}}
  const temp=target+'.tmp';writeFileSync(temp,body,{flag:'wx'});renameSync(temp,target);const manifest={version,sha256:actual,url:config.publicUrl+'/api/app/releases/'+name,filename:name};const mt=resolve(config.releaseDir,'latest.json.tmp');writeFileSync(mt,JSON.stringify(manifest)+'\n');renameSync(mt,resolve(config.releaseDir,'latest.json'));return Response.json({ok:true,...manifest},{status:201,headers:{'Cache-Control':'no-store'}});
 }
 return null;
}
async function digest(value){return Buffer.from(await crypto.subtle.digest('SHA-256',typeof value==='string'?new TextEncoder().encode(value):value)).toString('hex');}
function safeEqual(a,b){if(a.length!==b.length)return false;let n=0;for(let i=0;i<a.length;i++)n|=a.charCodeAt(i)^b.charCodeAt(i);return n===0;}
function compareVersions(a,b){const x=a.split('.').map(Number),y=b.split('.').map(Number);for(let i=0;i<3;i++)if((x[i]||0)!==(y[i]||0))return (x[i]||0)>(y[i]||0)?1:-1;return 0;}
if(process.argv[1]&&fileURLToPath(import.meta.url)===resolve(process.argv[1])){
 try{
  const app=await startServer();console.log('Delneveshte ready on port '+app.server.address().port+'; database migrations applied.');
  let closing=false;
  const stop=()=>{if(closing)return;closing=true;app.close().then(()=>process.exit(0),()=>process.exit(1));setTimeout(()=>process.exit(1),15000).unref();};
  process.on('SIGTERM',stop);process.on('SIGINT',stop);
 }catch(error){console.error('Startup failed: '+error.message);process.exitCode=1;}
}
