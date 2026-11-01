// Download only during the image build; serve the font from our own origin.
import {mkdirSync,writeFileSync} from 'node:fs';
const revision='6e553e33489a8f9dfaccc76860a2e3f3c1e66de7';
const base='https://raw.githubusercontent.com/rastikerdar/vazirmatn/'+revision+'/';
const folder=new URL('../public/fonts/',import.meta.url);mkdirSync(folder,{recursive:true});
for(const [source,target] of [['fonts/webfonts/Vazirmatn%5Bwght%5D.woff2','Vazirmatn.woff2'],['OFL.txt','OFL.txt']]){
 const response=await fetch(base+source,{signal:AbortSignal.timeout(60000)});
 if(!response.ok)throw new Error('Font download failed: '+response.status);
 const bytes=new Uint8Array(await response.arrayBuffer());
 if(target.endsWith('.woff2')&&new TextDecoder().decode(bytes.slice(0,4))!=='wOF2')throw new Error('Invalid WOFF2 file');
 writeFileSync(new URL(target,folder),bytes);
}
console.log('Vazirmatn and OFL license prepared for self-hosting.');
