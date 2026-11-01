import {suitable} from './catalog-import.mjs';
async function request(url,token,fetcher){
 const response=await fetcher(url,{headers:{Authorization:'Bearer '+token},redirect:'error',signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw new Error('Social API request failed');
 const reader=response.body.getReader(),chunks=[];let size=0;
 try{for(;;){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>1_000_000)throw new Error('Social response too large');chunks.push(value);}}finally{await reader.cancel();}
 return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
export async function collectSocial(input=process.env,fetcher=fetch){
 const rows=[],failures=[],status={x:'needs_connection',instagram:'needs_connection'};
 const authorized=input.SOCIAL_REUSE_AUTHORIZED==='true';
 const add=(text,author,url,source)=>{
  if(!authorized||!text)return;
  const language=/[\u0600-\u06ff]/.test(text)?'fa':'en';
  text=text.replace(/https?:\/\/\S+/g,'').replace(/#[\p{L}\p{N}_]+/gu,'').trim();
  if(suitable(text,language))rows.push({text,author,source,source_url:url,source_label:source,language,rights:'Publisher reuse authorization configured by owner',category:text.length<160?'کوتاه':'صمیمی',source_revision:'official-api'});
 };
 if(input.X_BEARER_TOKEN&&input.X_COLLECTION_ENABLED==='true')try{
  const url=new URL('https://api.x.com/2/tweets/search/recent');url.searchParams.set('query',input.X_SEARCH_QUERY||'(عاشقانه OR "love you") -is:retweet');url.searchParams.set('max_results','10');url.searchParams.set('tweet.fields','author_id,lang');url.searchParams.set('expansions','author_id');url.searchParams.set('user.fields','username');
  const data=await request(url,input.X_BEARER_TOKEN,fetcher);const users=new Map((data.includes?.users||[]).map(u=>[u.id,u.username]));
  for(const post of data.data||[])add(post.text,users.get(post.author_id)||post.author_id,'https://x.com/i/status/'+post.id,'x');status.x=authorized?'connected':'needs_reuse_authorization';
 }catch{status.x='api_error';failures.push('social:x');}
 if(input.INSTAGRAM_ACCESS_TOKEN&&/^\d+$/.test(input.INSTAGRAM_USER_ID||'')&&input.INSTAGRAM_COLLECTION_ENABLED==='true')try{
  const version=input.INSTAGRAM_API_VERSION||'v22.0';if(!/^v\d+\.0$/.test(version))throw new Error('Invalid API version');
  const url=new URL('https://graph.instagram.com/'+version+'/'+input.INSTAGRAM_USER_ID+'/media');url.searchParams.set('fields','id,caption,permalink,username');url.searchParams.set('limit','25');
  const data=await request(url,input.INSTAGRAM_ACCESS_TOKEN,fetcher);
  for(const post of data.data||[])if(post.permalink?.startsWith('https://www.instagram.com/'))add(post.caption,post.username||input.INSTAGRAM_USER_ID,post.permalink,'instagram');status.instagram=authorized?'connected_authorized_account':'needs_reuse_authorization';
 }catch{status.instagram='api_error';failures.push('social:instagram');}
 return {rows,failures,status,successes:Object.values(status).filter(s=>s.startsWith('connected')).length};
}
