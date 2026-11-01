import {collectSocial} from './social-collector.mjs';
import {collectWeb} from './web-collector.mjs';
import {randomInt} from 'node:crypto';
import {ganjoorExcerpts,importBundledGanjoor,insertExcerpts,suitable} from './catalog-import.mjs';
const sources=[{path:'/hafez/ghazal',author:'حافظ'},{path:'/saadi/divan/ghazals',author:'سعدی'},{path:'/moulavi/shams/ghazalsh',author:'مولانا'}];
const englishAuthors=new Set(['William Shakespeare','Percy Bysshe Shelley','John Keats','Elizabeth Barrett Browning','Robert Browning','Emily Dickinson','Robert Burns','Lord Byron','George Gordon, Lord Byron','Christina Rossetti']);
export async function fetchJson(url,fetcher=fetch){
 const response=await fetcher(url,{headers:{'User-Agent':'DelneveshteCatalog/1.0 (+https://github.com/fayazamir1991/sms)'},signal:AbortSignal.timeout(20000)});if(!response.ok)throw new Error('Source HTTP '+response.status);
 const reader=response.body.getReader(),parts=[];let size=0;
 try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>2_000_000)throw new Error('Source response too large');parts.push(value);}}finally{await reader.cancel();}
 return JSON.parse(Buffer.concat(parts).toString('utf8'));
}
export function tehranSlot(now=new Date()){
 const p=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Tehran',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',hourCycle:'h23'}).formatToParts(now).map(p=>[p.type,p.value]));return {day:p.year+'-'+p.month+'-'+p.day,due:Number(p.hour)>=2};
}
export async function collectNightly(fetcher=fetch,db=null){
 const rows=[],failures=[];let successes=0;
 for(const source of sources)try{
  const base='https://cdn.jsdelivr.net/gh/ganjoor/ganjoor-data@main/poets',cat=await fetchJson(base+source.path+'/_cat.json',fetcher);if(!Array.isArray(cat.Poems))throw new Error('Invalid Ganjoor category');const poems=[...cat.Poems];
  for(let i=0;i<3&&poems.length;i++){const selected=poems.splice(randomInt(poems.length),1)[0];if(!selected.FullUrl?.startsWith(source.path+'/'))continue;rows.push(...ganjoorExcerpts(await fetchJson(base+selected.FullUrl+'.json',fetcher),source.author));}successes++;
 }catch{failures.push('ganjoor:'+source.path);}
 try{
  const poems=await fetchJson('https://poetrydb.org/title,random/love;5',fetcher);if(!Array.isArray(poems))throw new Error('Invalid PoetryDB response');
  for(const p of poems)if(englishAuthors.has(p.author)&&Array.isArray(p.lines))for(let i=0;i<p.lines.length-1;i+=2){const text=p.lines.slice(i,i+2).join('\n');if(suitable(text,'en'))rows.push({text,author:p.author,title:p.title,source:'poetrydb',source_url:'https://poetrydb.org/title/'+encodeURIComponent(p.title)+':abs',language:'en',rights:'PoetryDB CC BY 4.0; public-domain poem text',source_revision:'live'});}successes++;
 }catch{failures.push('poetrydb');}
 const web=await collectWeb(fetcher,undefined,db);rows.unshift(...web.rows);failures.push(...web.failures);successes+=web.successes;const social=await collectSocial(process.env,fetcher);rows.unshift(...social.rows);failures.push(...social.failures);successes+=social.successes;return {rows,failures,successes,social_status:social.status};
}
export async function runNightly(db,{now=new Date(),limit=20,fetcher=fetch}={}){
 const slot=tehranSlot(now);if(!slot.due)return {skipped:true};
 const old=await db.prepare('SELECT * FROM catalog_jobs WHERE job=? AND run_key=?').bind('nightly',slot.day).first();if(old?.status==='complete'||old&&Date.now()-old.started_at<30*60*1000)return {skipped:true};
 await db.prepare("INSERT INTO catalog_jobs(job,run_key,status,started_at) VALUES('nightly',?,'running',?) ON CONFLICT(job,run_key) DO UPDATE SET status='running',started_at=excluded.started_at").bind(slot.day,Date.now()).run();
 try{
  const collected=await collectNightly(fetcher,db),added=await insertExcerpts(db,collected.rows,Math.max(0,limit-(old?.added||0)));
  await db.prepare('UPDATE catalog_jobs SET status=?,finished_at=?,added=added+?,detail=? WHERE job=? AND run_key=?').bind(collected.failures.length?'partial':'complete',Date.now(),added,JSON.stringify({sources_ok:collected.successes,failed_sources:collected.failures,social_status:collected.social_status}),'nightly',slot.day).run();return {added,partial:collected.failures.length>0};
 }catch(error){await db.prepare("UPDATE catalog_jobs SET status='failed',finished_at=?,detail='Import failed; will retry' WHERE job='nightly' AND run_key=?").bind(Date.now(),slot.day).run();throw error;}
}
export function startCatalogJobs(db,input=process.env){
 let stopping=false,running=false,active=Promise.resolve();
 const tick=()=>{if(stopping||running)return;running=true;active=(async()=>{try{
  const bundled=await importBundledGanjoor(db);if(bundled.added)console.log('Ganjoor import added '+bundled.added+' catalog messages.');
  if(input.CATALOG_NIGHTLY_ENABLED!=='false'){const n=Number(input.CATALOG_NIGHTLY_LIMIT||20),limit=Number.isInteger(n)?Math.max(1,Math.min(200,n)):20;const result=await runNightly(db,{limit});if(!result.skipped)console.log('Nightly catalog import: '+JSON.stringify(result));}
 }catch{console.error('Catalog import incomplete; persistent state retained, retry scheduled.');}finally{running=false;}})();};
 const first=setTimeout(tick,3000),timer=setInterval(tick,60000);first.unref();timer.unref();return async()=>{stopping=true;clearTimeout(first);clearInterval(timer);await active;};
}
