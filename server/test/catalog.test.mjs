import test from 'node:test';
import assert from 'node:assert/strict';
import {TestD1} from './d1.mjs';
import {insertExcerpts,contentKey,ganjoorExcerpts,suitable} from '../src/catalog-import.mjs';
import {runNightly,tehranSlot,fetchJson} from '../src/catalog-jobs.mjs';
const row={text:'دلبر من، با تو در آغوش عشق آرام می‌گیرم\nبه دیدارت شکوفا می‌شود هر روز دل من',author:'شاعر تست',source:'taw-bio',source_url:'https://taw-bio.ir/test/sh1',language:'fa',rights:'public-domain',title:'تست'};
test('catalog imports deduplicate normalized text, preserve admin edits and keep source credits',async()=>{
 const db=new TestD1();try{
 assert.equal(await insertExcerpts(db,[row,{...row,author:'نام دیگر'}]),1);
 const original=await db.prepare('SELECT * FROM catalog_messages').first();assert.equal(original.body,row.text);assert.ok(!original.body.includes('taw-bio.ir'));assert.equal((await db.prepare('SELECT source_url FROM catalog_origins').first()).source_url,row.source_url);
 await db.prepare('UPDATE catalog_messages SET body=?,active=0 WHERE id=?').bind('ویرایش مدیر',original.id).run();
 assert.equal(await insertExcerpts(db,[row]),0);assert.equal((await db.prepare('SELECT active FROM catalog_messages').first()).active,0);
 assert.equal(contentKey('دلِ من، عاشق توست ❤️'),contentKey('دل من عاشق توست 🌷'));
 }finally{db.close();}
});
test('catalog writes and origin records commit together, and each run obeys its limit',async()=>{
 const db=new TestD1();try{
 const rows=Array.from({length:6},(_,i)=>({...row,text:row.text+'\n'+['یکم','دوم','سوم','چهارم','پنجم','ششم'][i]}));
 assert.equal(await insertExcerpts(db,rows,2),2);assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_origins').first()).n,2);
 assert.equal(await insertExcerpts(db,[{...row,source_url:'http://localhost/private'}]),0);
 }finally{db.close();}
});
test('Ganjoor publication requires a semantically reviewed exact couplet, not the word love',async()=>{
 const {readFileSync}=await import('node:fs');const reviewed=JSON.parse(readFileSync(new URL('../data/ganjoor-reviewed.json',import.meta.url),'utf8'))[0];
 const uri=new URL(reviewed.source_url),index=Number(uri.hash.slice(3))-1;
 const poem={Title:'تست',FullUrl:uri.pathname,Verses:reviewed.text.split('\n').map((text,i)=>({VOrder:i+1,CoupletIndex:index,Position:i?'Left':'Right',Text:text})),PoemSummary:'شرح معاصر'};
 assert.equal(ganjoorExcerpts(poem,reviewed.author).length,1);
 poem.Verses[0].Text='این سخن درباره عشق عرفانی و فلسفه است';assert.equal(ganjoorExcerpts(poem,reviewed.author).length,0);
});
test('nightly scheduling uses Tehran date and retries outages without claiming success',async()=>{
 assert.equal(tehranSlot(new Date('2026-11-01T22:29:00Z')).due,false);
 assert.equal(tehranSlot(new Date('2026-11-01T22:31:00Z')).day,'2026-11-02');
 assert.equal(tehranSlot(new Date('2026-11-01T22:31:00Z')).due,true);
 const db=new TestD1();let calls=0;try{
 const result=await runNightly(db,{now:new Date('2026-11-01T22:31:00Z'),fetcher:async()=>{calls++;throw new Error('offline');}});
 assert.equal(result.partial,true);assert.equal((await db.prepare('SELECT status FROM catalog_jobs').first()).status,'partial');
 const count=calls;assert.equal((await runNightly(db,{now:new Date('2026-11-01T22:31:00Z'),fetcher:async()=>{calls++;}})).skipped,true);assert.equal(calls,count);
 }finally{db.close();}
});
test('nightly success persists and prevents a second run for the same day',async()=>{
 const db=new TestD1();try{
 const fetcher=async url=>new Response(url.endsWith('/robots.txt')?'User-agent: *':url.includes('quotes.json')?'[]':url.includes('wikiquote.org')?'<html></html>':JSON.stringify(url.includes('poetrydb.org')?[]:{Poems:[]}),{headers:{'Content-Type':'application/json'}});
 assert.equal((await runNightly(db,{now:new Date('2026-11-01T22:31:00Z'),fetcher})).partial,false);
 assert.equal((await runNightly(db,{now:new Date('2026-11-01T22:31:00Z'),fetcher})).skipped,true);
 }finally{db.close();}
});
test('remote response limits are enforced before parsing',async()=>{
 await assert.rejects(fetchJson('https://example.test',async()=>new Response('x'.repeat(2000001))),/too large/);
});
