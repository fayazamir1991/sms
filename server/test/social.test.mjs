import test from 'node:test';import assert from 'node:assert/strict';
import {collectSocial} from '../src/social-collector.mjs';
test('missing social credentials do not send requests or claim a connection',async()=>{let calls=0;const result=await collectSocial({},async()=>{calls++;});assert.equal(calls,0);assert.equal(result.status.x,'needs_connection');assert.equal(result.status.instagram,'needs_connection');});
test('authorized X posts retain author and permalink, but require separate reuse authorization',async()=>{
 const input={X_COLLECTION_ENABLED:'true',X_BEARER_TOKEN:'test-only'};
 const fetcher=async(url,options)=>{assert.equal(new URL(url).hostname,'api.x.com');assert.equal(options.headers.Authorization,'Bearer test-only');return new Response(JSON.stringify({data:[{id:'42',author_id:'7',text:'My love for you grows brighter every day.'}],includes:{users:[{id:'7',username:'writer'}]}}));};
 assert.equal((await collectSocial(input,fetcher)).rows.length,0);
 const allowed=await collectSocial({...input,SOCIAL_REUSE_AUTHORIZED:'true'},fetcher);assert.equal(allowed.rows.length,1);assert.equal(allowed.rows[0].source_url,'https://x.com/i/status/42');assert.equal(allowed.rows[0].author,'writer');
});
test('Instagram reads only the configured authorized account',async()=>{
 const result=await collectSocial({INSTAGRAM_COLLECTION_ENABLED:'true',INSTAGRAM_ACCESS_TOKEN:'test-only',INSTAGRAM_USER_ID:'123',SOCIAL_REUSE_AUTHORIZED:'true'},async url=>{assert.equal(new URL(url).pathname,'/v22.0/123/media');return new Response(JSON.stringify({data:[{caption:'همسرم، دوستت دارم و کنار تو زندگی برایم زیباتر است.',username:'writer',permalink:'https://www.instagram.com/p/test/'}]}));});assert.equal(result.rows.length,1);assert.equal(result.status.instagram,'connected_authorized_account');
});
