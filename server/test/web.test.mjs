import test from 'node:test';
import assert from 'node:assert/strict';
import {extractWeb,collectWeb,robotsAllows} from '../src/web-collector.mjs';
import {insertExcerpts} from '../src/catalog-import.mjs';
import {TestD1} from './d1.mjs';
const config={id:'wikiquote-fa',enabled:true,format:'html',url:'https://fa.wikiquote.org/wiki/عشق',language:'fa',label:'ویکی‌گفتاورد',rights:'CC BY-SA 4.0',authors:['سعدی']};
const prose='<p>همسرم، کنار تو آرامم و هر روز دوستت دارم. — سعدی</p>';
test('non-poem romantic paragraphs and RSS messages are extracted with source attribution',()=>{
 const rows=extractWeb(prose,config);assert.equal(rows.length,1);assert.equal(rows[0].category,'کوتاه');assert.equal(rows[0].source,'wikiquote-fa');
 const rss=extractWeb('<rss><item><description><![CDATA[<p>همسرم، کنار تو آرامم و هر روز دوستت دارم.</p>]]></description></item></rss>',{...config,format:'rss',author:'نویسنده'});assert.equal(rss.length,1);
 assert.equal(extractWeb('<p>متن سیاسی و نامرتبط سعدی در اینجا نوشته شده است.</p>',config).length,0);
});
test('web prose is inserted into message categories rather than poetry',async()=>{
 const db=new TestD1();try{assert.equal(await insertExcerpts(db,extractWeb(prose,config)),1);assert.equal((await db.prepare('SELECT category FROM catalog_messages').first()).category,'کوتاه');}finally{db.close();}
});
test('robots restrictions prevent fetching a prohibited source page',async()=>{
 assert.equal(robotsAllows('User-agent: *\nDisallow: /wiki/','/wiki/love'),false);let calls=0;
 const result=await collectWeb(async()=>{calls++;return new Response('User-agent: *\nDisallow: /');},[config]);assert.equal(calls,1);assert.equal(result.successes,0);assert.equal(result.failures.length,1);
});
test('discovered pages persist across runs and external links are never crawled',async()=>{
 const db=new TestD1(),source={...config,url:'https://fa.wikiquote.org/wiki/love',pathPrefix:'/wiki/love'};const calls=[];
 const fetcher=async url=>{calls.push(url);return new Response(url.endsWith('/robots.txt')?'User-agent: *':prose+'<a href="/wiki/love-more">next</a><a href="https://evil.test/wiki/love">external</a>');};
 try{await collectWeb(fetcher,[source],db);assert.ok(calls.includes('https://fa.wikiquote.org/wiki/love-more'));assert.ok(!calls.some(u=>u.includes('evil.test')));calls.length=0;await collectWeb(fetcher,[source],db);assert.ok(!calls.includes('https://fa.wikiquote.org/wiki/love-more'));}finally{db.close();}
});
