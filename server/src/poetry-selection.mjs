import {readFileSync} from 'node:fs';
const rules=JSON.parse(readFileSync(new URL('../data/ganjoor-balanced-rules.json',import.meta.url),'utf8'));
const clean=value=>value.normalize('NFKD').replace(/\p{M}/gu,'').replace(/ي/g,'ی').replace(/ك/g,'ک').replace(/\u200c/g,' ').replace(/[^\p{L}\p{N}\s]/gu,' ').replace(/\s+/g,' ').trim();
export function emotionalCouplet(text,context=''){
 const t=clean(text);if(new RegExp(rules.reject,'u').test(t)||new RegExp(rules.reject_context,'u').test(clean(context)))return null;
 const signals=Object.entries(rules.signals).filter(([,s])=>new RegExp(s.pattern,'u').test(t)).map(([key])=>key);
 const score=signals.reduce((sum,key)=>sum+rules.signals[key].weight,0);
 const personal=signals.includes('address')&&['speaker','relationship','imagery'].some(s=>signals.includes(s));
 const emotional=['longing','together'].some(s=>signals.includes(s))||(signals.includes('joy')&&['affection','relationship'].some(s=>signals.includes(s)));
 const relational=['affection','relationship','longing'].some(s=>signals.includes(s))||new RegExp(rules.intimacy,'u').test(t);
 return personal&&emotional&&relational&&score>=rules.minimum_score?{score,signals}:null;
}
