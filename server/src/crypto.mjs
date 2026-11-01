const encoder = new TextEncoder();
const decoder = new TextDecoder();
export const bytesToBase64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
export const base64ToBytes = value => Uint8Array.from(atob(value), c => c.charCodeAt(0));
export async function sha256(value) {
 const bytes = await crypto.subtle.digest('SHA-256',encoder.encode(value));
 return [...new Uint8Array(bytes)].map(n=>n.toString(16).padStart(2,'0')).join('');
}
export function equal(a,b) {
 if (typeof a!=='string'||typeof b!=='string'||a.length!==b.length) return false;
 let difference=0; for(let i=0;i<a.length;i++) difference|=a.charCodeAt(i)^b.charCodeAt(i);
 return difference===0;
}
export function normalizeBody(value) {
 return value.normalize('NFKC').replace(/ي/g,'ی').replace(/ك/g,'ک')
  .replace(/[\p{Extended_Pictographic}\u200d\ufe0f]/gu,'').replace(/[\p{P}\p{Z}\s]+/gu,' ').trim();
}
async function dataKey(env) {
 const raw=base64ToBytes(env.DATA_ENCRYPTION_KEY||'');
 if(raw.length!==32) throw new Error('DATA_ENCRYPTION_KEY must be a base64 32-byte key');
 return crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']);
}
export async function encrypt(value,env) {
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const cipher=await crypto.subtle.encrypt({name:'AES-GCM',iv},await dataKey(env),encoder.encode(value));
 return 'v1:'+bytesToBase64(iv)+':'+bytesToBase64(cipher);
}
export async function decrypt(value,env) {
 const [version,iv,cipher]=value.split(':');
 if(version!=='v1') throw new Error('Unsupported encrypted field');
 return decoder.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:base64ToBytes(iv)},await dataKey(env),base64ToBytes(cipher)));
}
export async function hmac(value,secret) {
 if(!secret||secret.length<32) throw new Error('Missing session signing key');
 const key=await crypto.subtle.importKey('raw',encoder.encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign']);
 return bytesToBase64(await crypto.subtle.sign('HMAC',key,encoder.encode(value)));
}
