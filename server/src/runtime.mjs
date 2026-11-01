import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {handle} from './worker.mjs';
import {SQLiteStore} from './sqlite.mjs';

export function settings(input=process.env){
 let bundled={};
 try{if(input.SERVER_RUNTIME_SECRETS)bundled=JSON.parse(input.SERVER_RUNTIME_SECRETS);}
 catch{throw new Error('SERVER_RUNTIME_SECRETS must be a valid JSON object.');}
 const config={};
 for(const key of ['DATA_ENCRYPTION_KEY','SESSION_SIGNING_KEY','ADMIN_TOKEN_SHA256'])config[key]=input[key]||bundled[key];
 if(!/^[A-Za-z0-9+/]{43}=$/.test(config.DATA_ENCRYPTION_KEY||'')||Buffer.from(config.DATA_ENCRYPTION_KEY,'base64').length!==32)
  throw new Error('Set DATA_ENCRYPTION_KEY to the preserved base64 32-byte key.');
 if(typeof config.SESSION_SIGNING_KEY!=='string'||config.SESSION_SIGNING_KEY.length<32)throw new Error('Set the preserved SESSION_SIGNING_KEY (at least 32 characters).');
 if(!/^[a-f0-9]{64}$/.test(config.ADMIN_TOKEN_SHA256||''))throw new Error('Set ADMIN_TOKEN_SHA256 to the SHA-256 hash of the admin password.');
 let url;try{url=new URL(input.PUBLIC_URL);}catch{throw new Error('Set PUBLIC_URL to the external HTTPS address of this service.');}
 const local=input.NODE_ENV!=='production'&&['127.0.0.1','localhost'].includes(url.hostname)&&url.protocol==='http:';
 if((url.protocol!=='https:'&&!local)||url.username||url.password||url.search||url.hash||url.pathname!=='/')throw new Error('PUBLIC_URL must be an HTTPS origin with no path or credentials.');
 const port=Number(input.PORT||8080);if(!Number.isInteger(port)||port<0||port>65535)throw new Error('PORT is invalid.');
 return {...config,publicUrl:url.origin,port,host:input.HOST||'0.0.0.0',database:resolve(input.DATA_DIR||'/data','delneveshte.sqlite')};
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
 try{db.migrate();}catch(error){db.close();throw error;}
 const env={...config,DB:db,ASSETS:assets()};
 const server=createServer({maxHeaderSize:16384},async(incoming,outgoing)=>{
  try{
   if(!incoming.url?.startsWith('/')||incoming.url.startsWith('//')){outgoing.writeHead(400);outgoing.end();return;}
   const parts=[];let size=0;
   for await(const chunk of incoming){size+=chunk.length;if(size>256000){outgoing.writeHead(413);outgoing.end();return;}parts.push(chunk);}
   const method=incoming.method||'GET',headers=new Headers();
   for(const [key,value] of Object.entries(incoming.headers))if(value!==undefined)headers.set(key,Array.isArray(value)?value.join(','):value);
   const request=new Request(config.publicUrl+incoming.url,{method,headers,...(!['GET','HEAD'].includes(method)?{body:Buffer.concat(parts)}:{})});
   // Readiness reflects the persistent database, not only an open TCP port.
   if(new URL(request.url).pathname==='/healthz')await db.prepare('SELECT 1').first();
   const response=await handle(request,env);
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
 return {server,db,async close(){await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));db.close();}};
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===resolve(process.argv[1])){
 try{
  const app=await startServer();console.log('Delneveshte ready on port '+app.server.address().port+'; database migrations applied.');
  let closing=false;
  const stop=()=>{if(closing)return;closing=true;app.close().then(()=>process.exit(0),()=>process.exit(1));setTimeout(()=>process.exit(1),15000).unref();};
  process.on('SIGTERM',stop);process.on('SIGINT',stop);
 }catch(error){console.error('Startup failed: '+error.message);process.exitCode=1;}
}
