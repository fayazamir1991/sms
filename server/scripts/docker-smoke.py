"""Exercise the real image, TLS proxy origin, private logs and a replacement container."""
import base64
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time

image = sys.argv[1] if len(sys.argv) > 1 else 'delneveshte-test'
name = 'sms-smoke-' + secrets.token_hex(5)
volume = name + '-data'
password = secrets.token_urlsafe(32)
release_token = secrets.token_urlsafe(48)

def docker(*args, capture=True):
    result = subprocess.run(['docker', *args], text=True, capture_output=capture)
    if result.returncode:
        if capture:
            print(result.stderr, file=sys.stderr)
        raise RuntimeError('Docker smoke command failed')
    return result.stdout

with tempfile.TemporaryDirectory() as directory:
    env = Path(directory) / 'runtime.env'
    env.write_text('\n'.join([
        'PUBLIC_URL=https://sms.example.test',
        'CATALOG_NIGHTLY_ENABLED=false',
        'DATA_ENCRYPTION_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode(),
        'SESSION_SIGNING_KEY=' + secrets.token_urlsafe(48),
        'ADMIN_TOKEN_SHA256=' + hashlib.sha256(password.encode()).hexdigest(),
        'APP_RELEASE_UPLOAD_TOKEN_SHA256=' + hashlib.sha256(release_token.encode()).hexdigest(),
        'SMOKE_ADMIN_PASSWORD=' + password,
        'SMOKE_RELEASE_UPLOAD_TOKEN=' + release_token,
    ]) + '\n')
    env.chmod(0o600)

    def start():
        # `docker stop` with --rm removes a container asynchronously; remove it
        # explicitly before reusing the same name with the persistent volume.
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        docker('run', '--rm', '-d', '--name', name, '--mount', f'type=volume,src={volume},dst=/data', '--env-file', str(env), image)
        for _ in range(45):
            probe = subprocess.run(['docker', 'exec', name, 'node', '-e', "fetch('http://127.0.0.1:8080/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"], capture_output=True)
            if probe.returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError('Container failed readiness probe')

    smoke = r'''
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const origin='https://sms.example.test',base='http://127.0.0.1:8080';
const login=await fetch(base+'/api/admin/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({password:process.env.SMOKE_ADMIN_PASSWORD})});
assert.equal(login.status,200);
const cookie=login.headers.get('set-cookie').split(';')[0],{csrf}=await login.json();
const headers={Cookie:cookie,Origin:origin,'X-CSRF':csrf,'Content-Type':'application/json'};
const stats=await (await fetch(base+'/api/admin/stats',{headers})).json();
assert.ok(stats.catalog>=3002,'The paired catalog and 2000 new messages must be seeded before readiness');
const {readFileSync}=await import('node:fs');
const corpus=JSON.parse(readFileSync('./data/imports/ganjoor-report.json','utf8'));
const {gunzipSync}=await import('node:zlib');
const {contentKey}=await import('./src/catalog-import.mjs');
const excerpts=gunzipSync(readFileSync('./data/imports/ganjoor-v1.ndjson.gz')).toString('utf8').trim().split('\n').map(line=>JSON.parse(line));
const canonicalCount=new Set(excerpts.map(row=>contentKey(row.text))).size;
const bank=await (await fetch(base+'/api/admin/reports?table=catalog&days=7',{headers})).json();
assert.equal(bank.timeline.points.length,7);assert.equal(bank.groups.length,8);
assert.equal(bank.groups.find(g=>g.id==='source').rows.find(r=>r.key==='ganjoor').value,canonicalCount,'Every canonically unique selected couplet must reach the real database');
assert.equal(bank.groups.find(g=>g.id==='category').rows.reduce((n,r)=>n+r.value,0),stats.catalog);
assert.equal((await fetch(base+'/api/admin/reports?table=catalog')).status,401);
for(const table of ['installations','queue','sent','events','imports','webpages']){
 const response=await fetch(base+'/api/admin/reports?table='+table+'&days=30',{headers});assert.equal(response.status,200);assert.equal((await response.json()).timeline.points.length,30);
}
console.log('Verified selected Ganjoor poems in SQLite: '+canonicalCount+' (raw selections: '+corpus.selected+')');
assert.equal((await fetch(base+'/admin')).status,200);
const font=await fetch(base+'/fonts/Vazirmatn.woff2');assert.equal(font.status,200);assert.equal(font.headers.get('content-type'),'font/woff2');assert.equal(Buffer.from(await font.arrayBuffer()).subarray(0,4).toString(),'wOF2');
assert.match(await (await fetch(base+'/admin.js')).text(),/use strict/);
assert.equal((await fetch(base+'/api/admin/stats')).status,401);
if(process.env.SMOKE_PHASE==='write'){
 const apk=Buffer.from('test-apk-persisted-across-containers'),sha256=createHash('sha256').update(apk).digest('hex');
 const publish=await fetch(base+'/api/admin/app-releases',{method:'POST',headers:{Authorization:'Bearer '+process.env.SMOKE_RELEASE_UPLOAD_TOKEN,'X-Release-Version':'0.25.0','X-APK-SHA256':sha256,'Content-Type':'application/vnd.android.package-archive'},body:apk});assert.equal(publish.status,201);
 const release=await (await fetch(base+'/api/app/latest')).json();assert.equal(release.version,'0.25.0');assert.equal(release.sha256,sha256);
 assert.deepEqual(Buffer.from(await (await fetch(release.url)).arrayBuffer()),apk);
 const auth='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'c'.repeat(64);
 const post=(path,body)=>fetch(base+path,{method:'POST',headers:{Authorization:auth,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post('/api/v1/installations/register',{app_version:'0.3.0'})).status,201);
 const suggestions=await (await fetch(base+'/api/v1/messages/suggestions?limit=5',{headers:{Authorization:auth}})).json();assert.equal(suggestions.messages.length,5);
 const browse=await (await fetch(base+'/api/v1/messages/browse',{headers:{Authorization:auth}})).json();assert.equal(browse.messages.length,20);
 for(const row of browse.messages)if(row.category!=='شعر'){assert.ok(row.body_formal&&row.body_colloquial);assert.equal(row.body,row.body_colloquial);}
 const formal=await (await fetch(base+'/api/v1/messages/browse?tone=formal&category='+encodeURIComponent('صمیمی'),{headers:{Authorization:auth}})).json();
 assert.equal(formal.messages.length,20);for(const row of formal.messages)assert.equal(row.body,row.body_formal);
 const composed=bank.groups.find(g=>g.id==='source').rows.find(r=>r.key==='دل‌نوشته؛ نگارش مستقل');assert.equal(composed.value,2000);
 for(const q of ['عشق',browse.messages[0].body.slice(0,12)]){
  const search=await (await fetch(base+'/api/v1/messages/browse?q='+encodeURIComponent(q),{headers:{Authorization:auth}})).json();assert.ok(search.messages.length>0,'Real Docker search must find '+q);
 }
 console.log('PASS: real Docker browse returns 20 messages and search finds عشق plus text from a displayed message.');
 const m={id:'521ac542-0c04-44f2-9b7b-3dd365df89ef',body:'کنارت آرامم 🫂❤️',recipient:'+989121234567',sim_id:7,status:'SENT',revision:3,approved_at:1000,submitted_at:2000,events:[]};
 for(let n=0;n<2;n++)assert.equal((await post('/api/v1/sync',{messages:[m]})).status,200);
 const catalog=await (await fetch(base+'/api/admin/tables/catalog',{headers})).json();
 const row=catalog.rows[0];
 assert.equal((await fetch(base+'/api/admin/catalog',{method:'POST',headers,body:JSON.stringify({...row,body:'متن ویرایش‌شده در آزمون 🌷',active:true})})).status,200);
}else{
 const release=await (await fetch(base+'/api/app/latest')).json();assert.equal(release.version,'0.25.0');
 assert.deepEqual(Buffer.from(await (await fetch(release.url)).arrayBuffer()),Buffer.from('test-apk-persisted-across-containers'));
 assert.equal(stats.installations,1);assert.equal(stats.sent,1);
 const sent=await (await fetch(base+'/api/admin/tables/sent',{headers})).json();assert.equal(sent.rows[0].recipient,'+989121234567');assert.equal(sent.rows[0].body,'کنارت آرامم 🫂❤️');
 const rows=await (await fetch(base+'/api/admin/tables/catalog',{headers})).json();assert.ok(rows.rows.some(r=>r.body==='متن ویرایش‌شده در آزمون 🌷'));
}
console.log('Container checks passed: '+process.env.SMOKE_PHASE);
'''
    try:
        start()
        print(docker('exec', '-e', 'SMOKE_PHASE=write', name, 'node', '--input-type=module', '-e', smoke).strip())
        docker('stop', name)
        start()
        print(docker('exec', '-e', 'SMOKE_PHASE=read', name, 'node', '--input-type=module', '-e', smoke).strip())
        print('PASS: replacement container preserved the seed, admin edits, installation and encrypted sent log.')
    finally:
        subprocess.run(['docker', 'rm', '-f', name], capture_output=True)
        subprocess.run(['docker', 'volume', 'rm', volume], capture_output=True)
