import {createHash,randomUUID} from 'node:crypto';
import {existsSync,readFileSync} from 'node:fs';
import {gunzipSync} from 'node:zlib';
import {normalizeBody} from './crypto.mjs';
const configuredSources=JSON.parse(readFileSync(new URL('../data/web-sources.json',import.meta.url),'utf8'));
const sourceOrigins=new Map(configuredSources.filter(s=>s.enabled&&s.mode!=='discovery').map(s=>[s.id,new URL(s.sourceUrl||s.url).origin]));
const sha=value=>createHash('sha256').update(value).digest('hex');
export const contentKey=text=>sha(normalizeBody(text.normalize('NFKD').replace(/\p{M}/gu,'').replace(/\u200c/g,' ')).toLowerCase());
export function suitable(text,language='fa'){
 const t=text.normalize('NFKD').replace(/\p{M}/gu,'').replace(/ي/g,'ی').replace(/ك/g,'ک').replace(/\u200c/g,' ');
 if(t.length<25||t.length>650)return false;
 if(language==='en')return /\b(love|beloved|darling|sweetheart|kiss|embrace)\b/i.test(t)&&/\b(you|thee|thou|thy|your|our|my)\b/i.test(t)&&!/\b(death|kill|hate|rape|corpse|suicide)\b/i.test(t);
 return !/دشمن|نفرت|کشتن|خون|مرگ|جنازه|لعنت|جهنم|زنا|کافر|تجاوز|بمیر|ستمگر/.test(t)&&((/عشق|عاشق|معشوق|جانان|دلبر|دلبند|دلدار|دلداده|محبوب|دلارام/.test(t)&&/تو|ترا|لبت|لبان|زلف|آغوش|وصال|دیدار|بوسه|چشم|نگاه|یار|مهر|دلم|دل من/.test(t))||/دوستت دارم|دوست می دارم|در آغوش|بوسه.*لب|لب.*بوسه|عزیزم|همسرم|عاشقتم|لبخند تو|کنار تو/.test(t));
}
export function ganjoorExcerpts(poem,author,revision='nightly'){
 const groups=new Map();for(const v of [...(poem.Verses||[])].sort((a,b)=>a.VOrder-b.VOrder))if(['Right','Left'].includes(v.Position)){const pair=groups.get(v.CoupletIndex)||[];pair.push(v);groups.set(v.CoupletIndex,pair);}
 const rows=[];for(const [i,pair]of groups)if(pair.length===2&&pair[0].Position==='Right'&&pair[1].Position==='Left'){
  const text=pair.map(v=>v.Text.trim()).join('\n');if(suitable(text))rows.push({text,author,title:poem.Title||'',source:'ganjoor',source_url:'https://ganjoor.net'+poem.FullUrl+'#bn'+(i+1),language:'fa',rights:'classical-public-domain',source_revision:revision});
 }return rows;
}
export async function insertExcerpts(db,rows,limit=Infinity){
 let added=0;const seen=new Set();
 const originals=await db.prepare('SELECT body FROM catalog_messages WHERE id NOT IN (SELECT catalog_id FROM catalog_origins)').all();
 for(const row of originals.results)seen.add(contentKey(row.body.split(/\n[—–-]/)[0]));
 for(let start=0;start<rows.length&&added<limit;start+=100){
  const statements=[];
  for(const row of rows.slice(start,start+100)){
   if(added+statements.length/2>=limit)break;
   if(!row||!['fa','en'].includes(row.language)||typeof row.text!=='string'||!suitable(row.text,row.language)||!row.author||!row.rights)continue;
   let url;try{url=new URL(row.source_url);}catch{continue;}
   if(url.protocol!=='https:'||(!['ganjoor.net','poetrydb.org'].includes(url.hostname)&&sourceOrigins.get(row.source)!==url.origin&&!(row.source==='x'&&url.hostname==='x.com')&&!(row.source==='instagram'&&url.hostname==='www.instagram.com')))continue;
   const key=contentKey(row.text);if(seen.has(key))continue;seen.add(key);
   if(await db.prepare('SELECT 1 FROM catalog_origins WHERE content_key=?').bind(key).first())continue;
   const body=row.text.trim()+(/\p{Extended_Pictographic}/u.test(row.text)?'':' 💕');if(/نمناک|تاوبیو|تاومتن|گنجور|در این مطلب|در این مقاله|مطالب مرتبط/.test(body))continue;if(body.length>1200||/https?:|www\.|[a-z0-9-]+\.(com|ir|net|org)\b/i.test(body)||row.language!=='fa')continue;
   const id=randomUUID(),fp=sha(normalizeBody(body)),now=Date.now();
   statements.push(db.prepare('INSERT OR IGNORE INTO catalog_messages(id,body,fingerprint,category,active,created_at) VALUES(?,?,?,?,1,?)').bind(id,body,fp,['کوتاه','صمیمی'].includes(row.category)?row.category:'شعر',now));
   statements.push(db.prepare('INSERT OR IGNORE INTO catalog_origins(catalog_id,content_key,source,source_url,author,title,language,rights,source_revision,imported_at,original_text,original_language,translation_provider,license_url) SELECT id,?,?,?,?,?,?,?,?,?,?,?,?,? FROM catalog_messages WHERE fingerprint=?').bind(key,row.source,row.source_url,row.author,row.title||'',row.language,row.rights,row.source_revision||'',now,row.original_text||row.text,row.original_language||row.language,row.translation_provider||'',row.license_url||'',fp));
  }
  if(statements.length){const results=await db.batch(statements);for(let i=0;i<results.length;i+=2)added+=results[i].meta.changes;}
  await new Promise(r=>setImmediate(r));
 }return added;
}
export async function importBundledGanjoor(db){
 const file=new URL('../data/imports/ganjoor-v1.ndjson.gz',import.meta.url);if(!existsSync(file))return {available:false,added:0};
 const report=JSON.parse(readFileSync(new URL('../data/imports/ganjoor-report.json',import.meta.url),'utf8')),key='ganjoor-v1:'+report.revision;
 if((await db.prepare('SELECT status FROM catalog_jobs WHERE job=? AND run_key=?').bind('ganjoor-once',key).first())?.status==='complete')return {available:true,added:0,already_complete:true};
 const bytes=readFileSync(file);if(bytes.length>64*1024*1024||sha(bytes)!==report.output_sha256)throw new Error('Bundled Ganjoor checksum mismatch');
 const rows=gunzipSync(bytes,{maxOutputLength:256*1024*1024}).toString('utf8').trim().split('\n').filter(Boolean).map(line=>JSON.parse(line));
 if(rows.length!==report.selected||report.poems_scanned!==report.manifest_poems)throw new Error('Incomplete Ganjoor export report');
 await db.prepare("INSERT INTO catalog_jobs(job,run_key,status,started_at) VALUES(?,?,'running',?) ON CONFLICT(job,run_key) DO UPDATE SET status='running',started_at=excluded.started_at").bind('ganjoor-once',key,Date.now()).run();
 const added=await insertExcerpts(db,rows);
 await db.prepare("UPDATE catalog_jobs SET status='complete',finished_at=?,added=added+?,detail=? WHERE job=? AND run_key=?").bind(Date.now(),added,JSON.stringify({scanned:report.poems_scanned,selected:report.selected,revision:report.revision}),'ganjoor-once',key).run();return {available:true,added};
}
