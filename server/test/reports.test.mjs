import test from 'node:test';import assert from 'node:assert/strict';
import {TestD1} from './d1.mjs';import {reportData} from '../src/admin-reports.mjs';import {handle} from '../src/worker.mjs';import {emotionalCouplet} from '../src/poetry-selection.mjs';import {insertExcerpts} from '../src/catalog-import.mjs';
test('report totals cover the whole catalog and daily gaps are zero-filled in Tehran time',async()=>{
 const db=new TestD1(),now=Date.parse('2026-11-02T01:00:00+03:30');try{
 for(let i=0;i<122;i++){
  const id=crypto.randomUUID();await db.prepare('INSERT INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,?,?)').bind(id,'private-body-marker-'+i,'report-'+i,i<60?'شعر':'صمیمی',i%5?1:0,i===121?now:now-86400000).run();
  if(i<60)await db.prepare('INSERT INTO catalog_origins(catalog_id,content_key,source,source_url,author,language,rights,imported_at,selection_signals) VALUES(?,?,?,?,?,?,?,?,?)').bind(id,'content-'+i,'ganjoor','https://ganjoor.net/private-source','poet','fa','public-domain',now,'["longing","together"]').run();
 }
 const report=await reportData(db,'catalog',7,now),groups=Object.fromEntries(report.groups.map(g=>[g.id,g]));
 assert.equal(groups.category.rows.reduce((n,r)=>n+r.value,0),122);assert.equal(groups.source.rows.reduce((n,r)=>n+r.value,0),122);assert.equal(groups.emotion.rows.reduce((n,r)=>n+r.value,0),120);
 assert.equal(report.timeline.points.length,7);assert.equal(report.timeline.points.at(-1).day,'2026-11-02');assert.equal(report.timeline.points.at(-1).value,1);assert.equal(report.timeline.points.at(-2).value,121);assert.equal(report.timeline.points[0].value,0);
 assert.ok(!JSON.stringify(report).includes('private-body'));assert.ok(!JSON.stringify(report).includes('private-source'));
 }finally{db.close();}
});
test('all report types support an empty dataset and unknown report keys are rejected',async()=>{
 const db=new TestD1();try{for(const name of ['catalog','installations','queue','sent','events','imports','webpages']){const report=await reportData(db,name,30);assert.equal(report.timeline.points.length,30);assert.equal(report.timeline.points.reduce((n,r)=>n+r.value,0),0);}assert.equal(await reportData(db,'__proto__'),null);assert.equal((await handle(new Request('https://example.test/api/admin/reports'),{DB:db})).status,401);}finally{db.close();}
});
test('balanced poetry selection accepts personal emotion without relying on love alone',()=>{
 assert.ok(emotionalCouplet('من با تو در آغوش محبت آرامم\nاز شوق دیدارت دلم شادمان است'));
 assert.ok(emotionalCouplet('دلم از هجر تو دلتنگ و پریشان است\nچشم من مشتاق تو و روی تو خندان است'));
 for(const text of ['عشق تو قضای آسمانی است\nوصل تو بقای جاودانی است','برآید به دست تو هوش پدرش\nاز آن درد گردد پر از کینه سرش','در دولت پادشاه عشق آباد است\nاین حکمت فلسفه بنیاد است','ور به دست تو آمده است اجلم\nقد رضیت بما جری قلما'])assert.equal(emotionalCouplet(text),null,text);
});
test('balanced poetry expands the catalog and preserves selection metadata and administrator edits',async()=>{
 const db=new TestD1();try{
 const text='من با تو در آغوش محبت آرامم\nاز شوق دیدارت دلم شادمان است',decision=emotionalCouplet(text);
 const row={text,author:'شاعر کلاسیک',title:'غزل',source:'ganjoor',source_url:'https://ganjoor.net/test/1',language:'fa',rights:'public-domain',selection_policy:'balanced-v3',selection_score:decision.score,selection_signals:decision.signals};
 assert.equal(await insertExcerpts(db,[row]),1);assert.equal((await db.prepare('SELECT selection_policy FROM catalog_origins').first()).selection_policy,'balanced-v3');
 await db.prepare('UPDATE catalog_messages SET body=?,active=0').bind('ویرایش مدیر').run();assert.equal(await insertExcerpts(db,[row]),0);assert.equal((await db.prepare('SELECT body FROM catalog_messages').first()).body,'ویرایش مدیر');
 }finally{db.close();}
});
