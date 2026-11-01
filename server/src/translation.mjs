/** English is never published unchanged. Translation requires an owner-configured provider. */
export async function translateCandidates(rows,input=process.env,fetcher=fetch){
 const result=[];let requests=0;
 for(const row of rows){
  if(row.language==='fa'){result.push(row);continue;}
  if(row.language!=='en'||!input.TRANSLATE_URL||requests++>=20)continue;
  try{
   const url=new URL(input.TRANSLATE_URL);if(url.protocol!=='https:')continue;
   const response=await fetcher(url,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json'},signal:AbortSignal.timeout(20000),body:JSON.stringify({q:row.text,source:'en',target:'fa',format:'text',...(input.TRANSLATE_API_KEY?{api_key:input.TRANSLATE_API_KEY}:{})})});
   if(!response.ok)continue;const data=await response.json(),text=data.translatedText;
   if(typeof text!=='string'||text.length>650||!/[\u0600-\u06ff]/.test(text)||/[a-z]{3,}/i.test(text))continue;
   result.push({...row,text,language:'fa',original_text:row.text,original_language:'en',translation_provider:'LibreTranslate-compatible owner-configured service',rights:row.rights+'; translated from English by configured provider'});
  }catch{ /* Failed translation is excluded, never replaced by English. */ }
 }
 return result;
}
