import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {handleAppRelease} from '../src/runtime.mjs';
import {sha256} from '../src/crypto.mjs';

test('APK publishing is token protected, checksum verified and served from persistent release storage',async()=>{
 const dir=mkdtempSync(resolve(tmpdir(),'sms-app-releases-')),token='deployment-token-only';
 const config={releaseDir:dir,APP_RELEASE_UPLOAD_TOKEN_SHA256:await sha256(token),publicUrl:'https://del.darkube.ir'};
 const apk=Buffer.from('test-apk-binary'),hash=createHash('sha256').update(apk).digest('hex');
 const request=(method,headers={},body)=>new Request('https://del.darkube.ir/api/admin/app-releases',{method,headers,body});
 try{
  assert.equal((await handleAppRelease(request('POST',{},apk),config,apk)).status,401);
  assert.equal((await handleAppRelease(request('POST',{Authorization:'Bearer '+token,'X-Release-Version':'0.25.0','X-APK-SHA256':'0'.repeat(64)},apk),config,apk)).status,422);
  const uploaded=await handleAppRelease(request('POST',{Authorization:'Bearer '+token,'X-Release-Version':'0.25.0','X-APK-SHA256':hash},apk),config,apk);assert.equal(uploaded.status,201);
  const latest=await (await handleAppRelease(new Request('https://del.darkube.ir/api/app/latest'),config,Buffer.alloc(0))).json();assert.equal(latest.version,'0.25.0');assert.equal(latest.sha256,hash);
  const file=await handleAppRelease(new Request(latest.url),config,Buffer.alloc(0));assert.equal(file.status,200);assert.deepEqual(Buffer.from(await file.arrayBuffer()),apk);
  assert.equal((await handleAppRelease(request('POST',{Authorization:'Bearer '+token,'X-Release-Version':'0.25.0','X-APK-SHA256':hash},apk),config,apk)).status,409);
  assert.equal((await handleAppRelease(request('POST',{Authorization:'Bearer '+token,'X-Release-Version':'0.24.9','X-APK-SHA256':hash},apk),config,apk)).status,409);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
