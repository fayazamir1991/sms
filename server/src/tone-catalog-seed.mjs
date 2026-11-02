import {readFileSync} from 'node:fs';
import {gunzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {tonePair} from './catalog-tones.mjs';
import {normalizeSearch} from './catalog-search.mjs';
import {normalizeBody} from './crypto.mjs';
const sha=value=>createHash('sha256').update(value).digest('hex');
const version='original-tones-v1';
export const readingRefs=[
 {title:'The Gift of the Magi',author:'O. Henry',url:'https://www.gutenberg.org/ebooks/7256',theme:'هدیهٔ کوچک و توجه به دیگری'},
 {title:'Persuasion',author:'Jane Austen',url:'https://www.gutenberg.org/ebooks/105',theme:'دیدار و فرصت دوباره'},
 {title:'Pride and Prejudice',author:'Jane Austen',url:'https://www.gutenberg.org/ebooks/1342',theme:'گفت‌وگو و شناخت تدریجی'},
 {title:'گلستان، باب پنجم، حکایت ششم',author:'سعدی',url:'https://ganjoor.net/saadi/golestan/gbab5/sh6',theme:'شوق دیدار'}
];
/** Restart-safe and additive. Never rewrites an approved SMS or existing admin pair. */
export async function ensureToneCatalog(db){
 const previous=await db.prepare("SELECT id,body,category,body_formal,body_colloquial FROM catalog_messages WHERE category!='شعر' AND (body_formal IS NULL OR body_colloquial IS NULL)").all();
 for(let i=0;i<previous.results.length;i+=100){
  await db.batch(previous.results.slice(i,i+100).map(row=>{const p=tonePair(row.body,row.category);return db.prepare('UPDATE catalog_messages SET body_formal=COALESCE(body_formal,?),body_colloquial=COALESCE(body_colloquial,?) WHERE id=?').bind(p.body_formal,p.body_colloquial,row.id);}));
 }
 // Remove only the importer's added emoji, with an exact equality guard; admin edits survive.
 const poems=await db.prepare("SELECT c.id,c.body,o.original_text FROM catalog_messages c JOIN catalog_origins o ON o.catalog_id=c.id WHERE c.category='شعر' AND o.original_language='fa'").all();
 for(const p of poems.results)if(p.original_text&&p.body===p.original_text.trim()+' 💕'){
  const body=p.original_text.trim(),fp=sha(normalizeBody(body));
  if(!await db.prepare('SELECT id FROM catalog_messages WHERE fingerprint=? AND id!=?').bind(fp,p.id).first())await db.prepare('UPDATE catalog_messages SET body=?,fingerprint=?,search_text=? WHERE id=? AND body=?').bind(body,fp,normalizeSearch(body),p.id,p.body).run();
 }
 if(await db.prepare('SELECT 1 FROM seed_runs WHERE version=?').bind(version).first())return {backfilled:previous.results.length,added:0};
 const raw=gunzipSync(readFileSync(new URL('../data/tones/original-v1.ndjson.gz',import.meta.url))).toString('utf8');
 const report=JSON.parse(readFileSync(new URL('../data/tones/report.json',import.meta.url),'utf8'));
 if(sha(raw)!==report.sha256)throw new Error('Tone catalog checksum mismatch');
 const rows=raw.trim().split('\n').map(JSON.parse);if(rows.length<2000||rows.length!==report.messages)throw new Error('Incomplete tone catalog');
 let added=0;const now=Date.now();
 for(let i=0;i<rows.length;i+=50){const statements=[];
  for(const r of rows.slice(i,i+50)){
   const fp=sha(normalizeBody(r.body_formal));
   statements.push(db.prepare('INSERT OR IGNORE INTO catalog_messages(id,body,body_formal,body_colloquial,fingerprint,category,active,created_at,search_text) VALUES(?,?,?,?,?,?,1,?,?)').bind(r.id,r.body_formal,r.body_formal,r.body_colloquial,fp,r.category,now,normalizeSearch(r.body_formal)));
   statements.push(db.prepare("INSERT OR IGNORE INTO catalog_origins(catalog_id,content_key,source,source_url,author,title,language,rights,source_revision,imported_at,inspiration_refs) SELECT id,?,?,?,?,?,'fa',?,?,?,? FROM catalog_messages WHERE id=?").bind(fp,r.source,'https://github.com/fayazamir1991/sms',r.author,r.title,r.rights,version,now,JSON.stringify(readingRefs),r.id));
  }
  const result=await db.batch(statements);for(let j=0;j<result.length;j+=2)added+=result[j].meta.changes;
 }
 await db.prepare('INSERT OR IGNORE INTO seed_runs(version,count,seeded_at) VALUES(?,?,?)').bind(version,rows.length,now).run();return {backfilled:previous.results.length,added};
}
