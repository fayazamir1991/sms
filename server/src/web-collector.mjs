import {readFileSync} from 'node:fs';
import {suitable} from './catalog-import.mjs';
export const webSources=JSON.parse(readFileSync(new URL('../data/web-sources.json',import.meta.url),'utf8'));
export function plain(value){return value.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi,'').replace(/<[^>]+>/g,' ').replace(/&#(x[0-9a-f]+|\d+);/gi,(_,n)=>String.fromCodePoint(n[0].toLowerCase()==='x'?parseInt(n.slice(1),16):Number(n))).replace(/&nbsp;/g,' ').replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&apos;|&#39;/g,"'").replace(/\s+/g,' ').trim();}
export function extractWeb(text,source){
 if(source.mode==='discovery')return [];
 let candidates=[];
 if(source.format==='quotable'){
  const data=JSON.parse(text);if(!Array.isArray(data))throw new Error('Invalid quote dataset');
  candidates=data.filter(r=>source.authors.includes(r.author)).map(r=>({text:r.content,author:r.author,title:'quote'}));
 }else if(source.format==='rss'){
  candidates=[...text.matchAll(/<(?:item|entry)\b[^>]*>([\s\S]*?)<\/(?:item|entry)>/gi)].flatMap(m=>{
   const block=m[1],content=/<(?:content:encoded|content|description|summary)\b[^>]*>([\s\S]*?)<\/(?:content:encoded|content|description|summary)>/i.exec(block)?.[1]||'';
   return content.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g,'$1').split(/<\/(?:p|blockquote)>/i).map(value=>({text:plain(value),author:source.author||source.label,title:plain(/<title[^>]*>([\s\S]*?)<\/title>/i.exec(block)?.[1]||'')}));
  });
 }else if(source.format==='html'){
  candidates=[...text.matchAll(/<(p|blockquote|li)\b[^>]*>([\s\S]*?)<\/\1>/gi)].map(m=>{
   const value=plain(m[2]);return {text:value,author:source.authors?.find(author=>value.includes(author))||source.author,title:''};
  });
 }else throw new Error('Unsupported web source format');
 return candidates.filter(r=>typeof r.text==='string'&&r.author&&suitable(r.text,source.language)).map(r=>({...r,source:source.id,source_url:source.sourceUrl||source.url,source_label:source.label,language:source.language,rights:source.rights,license_url:source.licenseUrl||'',category:r.text.length<=160?'کوتاه':'صمیمی',source_revision:'web-live'}));
}
export function robotsAllows(text,path){
 let applicable=false;for(const raw of text.split(/\r?\n/)){
  const line=raw.replace(/#.*/,'').trim(),field=/^([^:]+):\s*(.*)$/.exec(line);if(!field)continue;
  if(field[1].toLowerCase()==='user-agent')applicable=['*','delneveshtecatalog'].includes(field[2].toLowerCase());
  if(applicable&&field[1].toLowerCase()==='disallow'&&field[2]&&(field[2]==='/'||path.startsWith(field[2].replace(/\*.*$/,''))))return false;
 }return true;
}
async function get(url,fetcher,robots=false){
 const u=new URL(url);if(u.protocol!=='https:'||u.username||u.password||/^(localhost|127\.|10\.|192\.168\.|169\.254\.|\[)/.test(u.hostname))throw new Error('Invalid public source URL');
 const r=await fetcher(url,{redirect:'error',headers:{'User-Agent':'DelneveshteCatalog/1.0 (+https://github.com/fayazamir1991/sms)'},signal:AbortSignal.timeout(20000)});if(robots&&r.status===404)return '';if(!r.ok)throw new Error('Web source HTTP '+r.status);
 const reader=r.body.getReader(),parts=[];let size=0;try{for(;;){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>2_000_000)throw new Error('Web source too large');parts.push(value);}}finally{await reader.cancel();}return Buffer.concat(parts).toString('utf8');
}
export async function collectWeb(fetcher=fetch,sources=webSources,db=null){
 const rows=[],failures=[];let successes=0;
 for(const source of sources.filter(s=>s.enabled))try{
  if(!source.rights||!['fa','en'].includes(source.language))throw new Error('Source rights/language missing');
  const u=new URL(source.url),robots=await get(u.origin+'/robots.txt',fetcher,true);
  if(!robotsAllows(robots,u.pathname))throw new Error('robots policy prohibits this source');
  const scope=source.pathPrefix||u.pathname;
  const root=await get(source.url,fetcher);rows.push(...extractWeb(root,source));
  if(db&&source.format!=='quotable'){
   const discover=async html=>{
    const existing=await db.prepare('SELECT COUNT(*) AS n FROM catalog_web_pages WHERE source=? AND done=0').bind(source.id).first();let count=existing.n;
    for(const match of html.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>|<link\b[^>]*rel=["']next["'][^>]*href=["']([^"']+)["']/gi)){
     const href=match[1]||match[3];
     if(source.mode==='discovery'&&!/عاشق|عشق|همسر|احساسی|رمانتیک|love|romantic/i.test(plain(match[2]||'')+' '+decodeURI(href)))continue;
     let next;try{next=new URL(href,u);}catch{continue;}
     if(next.origin!==u.origin||!decodeURI(next.pathname).startsWith(decodeURI(scope))||next.search||next.hash||!robotsAllows(robots,next.pathname)||count>=2000)continue;
     const result=await db.prepare('INSERT OR IGNORE INTO catalog_web_pages(source,url) VALUES(?,?)').bind(source.id,next.href).run();count+=result.meta.changes;
    }
   };
   await discover(root);
   const pending=await db.prepare('SELECT url FROM catalog_web_pages WHERE source=? AND done=0 ORDER BY fetched_at,url LIMIT 4').bind(source.id).all();
   for(const page of pending.results)try{
    if(page.url!==source.url){const html=await get(page.url,fetcher);rows.push(...extractWeb(html,{...source,sourceUrl:page.url}));await discover(html);}
    await db.prepare('UPDATE catalog_web_pages SET fetched_at=?,done=1 WHERE source=? AND url=?').bind(Date.now(),source.id,page.url).run();
   }catch{await db.prepare('UPDATE catalog_web_pages SET fetched_at=? WHERE source=? AND url=?').bind(Date.now(),source.id,page.url).run();failures.push('web-page:'+source.id);}
  }
  successes++;
 }catch{failures.push('web:'+source.id);}
 return {rows,failures,successes};
}
