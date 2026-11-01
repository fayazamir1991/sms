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

def docker(*args, capture=True):
    return subprocess.run(['docker', *args], check=True, text=True, capture_output=capture).stdout

with tempfile.TemporaryDirectory() as directory:
    env = Path(directory) / 'runtime.env'
    env.write_text('\n'.join([
        'PUBLIC_URL=https://sms.example.test',
        'CATALOG_NIGHTLY_ENABLED=false',
        'DATA_ENCRYPTION_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode(),
        'SESSION_SIGNING_KEY=' + secrets.token_urlsafe(48),
        'ADMIN_TOKEN_SHA256=' + hashlib.sha256(password.encode()).hexdigest(),
        'SMOKE_ADMIN_PASSWORD=' + password,
    ]) + '\n')
    env.chmod(0o600)

    def start():
        docker('run', '--rm', '-d', '--name', name, '--mount', f'type=volume,src={volume},dst=/data', '--env-file', str(env), image)
        for _ in range(45):
            probe = subprocess.run(['docker', 'exec', name, 'node', '-e', "fetch('http://127.0.0.1:8080/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"], capture_output=True)
            if probe.returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError('Container failed readiness probe')

    smoke = r'''
import assert from 'node:assert/strict';
const origin='https://sms.example.test',base='http://127.0.0.1:8080';
const login=await fetch(base+'/api/admin/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json'},body:JSON.stringify({password:process.env.SMOKE_ADMIN_PASSWORD})});
assert.equal(login.status,200);
const cookie=login.headers.get('set-cookie').split(';')[0],{csrf}=await login.json();
const headers={Cookie:cookie,Origin:origin,'X-CSRF':csrf,'Content-Type':'application/json'};
const stats=await (await fetch(base+'/api/admin/stats',{headers})).json();
assert.ok(stats.catalog>=1002);
assert.equal((await fetch(base+'/admin')).status,200);
const font=await fetch(base+'/fonts/Vazirmatn.woff2');assert.equal(font.status,200);assert.equal(font.headers.get('content-type'),'font/woff2');assert.equal(Buffer.from(await font.arrayBuffer()).subarray(0,4).toString(),'wOF2');
assert.match(await (await fetch(base+'/admin.js')).text(),/use strict/);
assert.equal((await fetch(base+'/api/admin/stats')).status,401);
if(process.env.SMOKE_PHASE==='write'){
 const auth='Bearer 141d6c45-f0b2-4c6b-8d6f-d31bcc1acd4e.'+'c'.repeat(64);
 const post=(path,body)=>fetch(base+path,{method:'POST',headers:{Authorization:auth,'Content-Type':'application/json'},body:JSON.stringify(body)});
 assert.equal((await post('/api/v1/installations/register',{app_version:'0.3.0'})).status,201);
 const suggestions=await (await fetch(base+'/api/v1/messages/suggestions?limit=5',{headers:{Authorization:auth}})).json();assert.equal(suggestions.messages.length,5);
 const m={id:'521ac542-0c04-44f2-9b7b-3dd365df89ef',body:'کنارت آرامم 🫂❤️',recipient:'+989121234567',sim_id:7,status:'SENT',revision:3,approved_at:1000,submitted_at:2000,events:[]};
 for(let n=0;n<2;n++)assert.equal((await post('/api/v1/sync',{messages:[m]})).status,200);
 const catalog=await (await fetch(base+'/api/admin/tables/catalog',{headers})).json();
 const row=catalog.rows[0];
 assert.equal((await fetch(base+'/api/admin/catalog',{method:'POST',headers,body:JSON.stringify({...row,body:'متن ویرایش‌شده در آزمون 🌷',active:true})})).status,200);
}else{
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
