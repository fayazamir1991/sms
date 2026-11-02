import {hmac,equal,bytesToBase64,base64ToBytes} from './crypto.mjs';
import {likePattern,normalizeSearch,compactSearchColumn} from './catalog-search.mjs';
const response=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}});
export async function browseCatalog(request,env,installation){
 const url=new URL(request.url),q=url.searchParams.get('q')||'',category=url.searchParams.get('category')||'همه';
 if(q.length>300||!['همه','کوتاه','صمیمی','قدردانی','دلتنگی','شعر'].includes(category))return response({error:'عبارت یا دستهٔ جست‌وجو معتبر نیست.'},400);
 const epoch=(await env.DB.prepare("SELECT seeded_at FROM seed_runs WHERE version='catalog-v1'").first())?.seeded_at||0;
 let cursor;const supplied=url.searchParams.get('cursor');
 if(supplied){
  try{
   if(supplied.length>6000)throw new Error();const [payload,signature]=supplied.split('.');
   if(!equal(signature,await hmac(payload,env.SESSION_SIGNING_KEY)))throw new Error();
   cursor=JSON.parse(new TextDecoder().decode(base64ToBytes(payload)));
   if(cursor.installation!==installation.id||cursor.q!==normalizeSearch(q)||cursor.category!==category||cursor.epoch!==epoch||Date.now()-cursor.started>24*60*60*1000)throw new Error();
  }catch{return response({error:'جست‌وجو منقضی شده؛ دوباره جست‌وجو کن.'},410);}
 }else{
  const max=(await env.DB.prepare('SELECT COALESCE(MAX(rowid),0) AS id FROM catalog_messages').first()).id;
  cursor={installation:installation.id,q:normalizeSearch(q),category,epoch,started:Date.now(),max,seed:crypto.getRandomValues(new Uint32Array(1))[0]%2147483647,rank:-1,last:''};
 }
 let where='c.active=1 AND c.rowid<=? AND c.created_at<=?',args=[cursor.seed,cursor.max,cursor.started];
 if(category!=='همه'){where+=' AND c.category=?';args.push(category);}
 if(q){where+=` AND ${compactSearchColumn("COALESCE(NULLIF(c.search_text,''),c.body)")} LIKE ? ESCAPE char(92)`;args.push(likePattern(q));}
 const sql=`WITH candidates AS (SELECT c.id,c.body,c.category,o.source,o.author,o.source_url,o.rights,o.language,o.license_url,((c.rowid*1103515245+?)%2147483647) AS rank FROM catalog_messages c LEFT JOIN catalog_origins o ON o.catalog_id=c.id WHERE ${where}) SELECT * FROM candidates WHERE rank>? OR (rank=? AND id>?) ORDER BY rank,id LIMIT 21`;
 args.push(cursor.rank,cursor.rank,cursor.last);const {results}=await env.DB.prepare(sql).bind(...args).all();
 const more=results.length>20,rows=results.slice(0,20);let next=null;
 if(more){const last=rows.at(-1);const payload=bytesToBase64(new TextEncoder().encode(JSON.stringify({...cursor,rank:last.rank,last:last.id})));next=payload+'.'+await hmac(payload,env.SESSION_SIGNING_KEY);}
 return response({messages:rows.map(({rank,...row})=>row),next_cursor:next,has_more:more,source:'server'});
}
