import {readFileSync,writeFileSync} from 'node:fs';
import {gzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
const base=new URL('../data/tones/',import.meta.url);
const lines=name=>readFileSync(new URL(name,base),'utf8').trim().split('\n').filter(l=>l&&!l.startsWith('#')).map(l=>l.split('|'));
export function toneCatalog(){
 const scenes=lines('scenes.txt'),endings=lines('endings.txt');
 const rows=scenes.flatMap(([theme,category,emoji,formal,colloquial],i)=>endings.map(([f,c],j)=>({
  id:uuid(`original-tones-v1:${i}:${j}`),theme,category,body_formal:`${formal} ${f} ${emoji}`,body_colloquial:`${colloquial} ${c} ${emoji}`,
  source:'دل‌نوشته؛ نگارش مستقل',author:'بانک دل‌نوشته',rights:'original-composition',source_revision:'original-tones-v1',
  title:`${theme}؛ نگارش مستقل بر پایهٔ مضمون‌های عاطفی، بدون نقل‌قول یا ترجمهٔ اثر`
 })));
 if(rows.length<2000)throw new Error('At least 2000 paired messages required');
 for(const key of ['body_formal','body_colloquial']){
  const seen=new Set();for(const row of rows){const body=row[key],normalized=body.normalize('NFKC').replace(/[^\p{L}\p{N}]/gu,'');
   if(seen.has(normalized)||body.length>450||body.length<50||/[A-Za-z]|https?:|www\./i.test(body))throw new Error('Invalid or duplicate '+key+': '+row.id);seen.add(normalized);
  }
 }
 return rows;
}
function uuid(seed){const h=createHash('sha256').update(seed).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-5${h.slice(13,16)}-a${h.slice(17,20)}-${h.slice(20,32)}`;}
const rows=toneCatalog(),text=rows.map(r=>JSON.stringify(r)).join('\n')+'\n';
writeFileSync(new URL('original-v1.ndjson.gz',base),gzipSync(text,{level:9}));
writeFileSync(new URL('report.json',base),JSON.stringify({version:'original-tones-v1',messages:rows.length,variants:rows.length*2,themes:[...new Set(rows.map(r=>r.theme))],scene_count:lines('scenes.txt').length,ending_count:lines('endings.txt').length,method:'100 authored paired scenes × 20 authored paired reflections; finite deterministic compositions, not 2000 independent stories or runtime AI',sha256:createHash('sha256').update(text).digest('hex')},null,2)+'\n');
console.log('Built '+rows.length+' original messages, each in two registers.');
