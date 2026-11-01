import {mkdirSync,writeFileSync} from 'node:fs';
const {CLOUDFLARE_ACCOUNT_ID:account,CLOUDFLARE_API_TOKEN:token,SERVER_RUNTIME_SECRETS:runtimeJson}=process.env;
if(!/^[a-f0-9]{32}$/i.test(account||'')||!token||!runtimeJson)throw new Error('Configure the Cloudflare account/token and server runtime secrets in GitHub Secrets.');
const runtime=JSON.parse(runtimeJson);
if(!runtime.DATA_ENCRYPTION_KEY||!runtime.SESSION_SIGNING_KEY||!runtime.ADMIN_TOKEN_SHA256)throw new Error('Incomplete server runtime secrets');
async function api(path,options={}){
 const r=await fetch('https://api.cloudflare.com/client/v4/accounts/'+account+path,{...options,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'}});
 const value=await r.json();if(!r.ok||!value.success)throw new Error(`Cloudflare setup request failed (${r.status}); check token permissions.`);return value;
}
const name='delneveshte-sms';let database;
for(let page=1;page<=10;page++){
 const result=await api('/d1/database?per_page=100&page='+page);
 database=result.result.find(d=>d.name===name);if(database||result.result.length<100)break;
}
if(!database)database=(await api('/d1/database',{method:'POST',body:JSON.stringify({name})})).result;
const databaseId=database.uuid||database.id;
if(!/^[a-f0-9-]{36}$/i.test(databaseId||''))throw new Error('Cloudflare did not return a D1 database id');
const result=await api('/workers/subdomain');const subdomain=result.result?.subdomain;
if(!/^[a-z0-9-]+$/.test(subdomain||''))throw new Error('Enable a workers.dev subdomain in the Cloudflare Workers dashboard, then rerun this job.');
const apiUrl='https://'+name+'.'+subdomain+'.workers.dev';
mkdirSync('.local',{recursive:true});
writeFileSync('.local/wrangler.json',JSON.stringify({name,account_id:account,main:'../src/worker.mjs',compatibility_date:'2025-04-01',workers_dev:true,
 assets:{directory:'../public',binding:'ASSETS',run_worker_first:true},
 d1_databases:[{binding:'DB',database_name:name,database_id:databaseId,migrations_dir:'../migrations'}]},null,2));
writeFileSync('.local/runtime-secrets.json',JSON.stringify(runtime),{mode:0o600});
writeFileSync('.local/deployment.json',JSON.stringify({api_url:apiUrl,admin_url:apiUrl+'/admin'},null,2));
console.log('Cloudflare Free-compatible configuration prepared. No paid-plan upgrade is performed.');
console.log('Backend URL: '+apiUrl);
