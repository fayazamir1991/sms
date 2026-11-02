import {writeFileSync,readFileSync} from 'node:fs';
import {gunzipSync,gzipSync} from 'node:zlib';
import {catalog} from './build-seed.mjs';
import {tonePair} from '../src/catalog-tones.mjs';
const original=catalog().map(r=>({category:r.category,...tonePair(r.body,r.category)}));
const newer=gunzipSync(readFileSync(new URL('../data/tones/original-v1.ndjson.gz',import.meta.url))).toString('utf8').trim().split('\n').map(l=>{const r=JSON.parse(l);return {category:r.category,body_formal:r.body_formal,body_colloquial:r.body_colloquial};});
writeFileSync(new URL('../../app/src/main/res/raw/catalog_tones.gz',import.meta.url),gzipSync(JSON.stringify([...original,...newer]),{level:9}));
console.log('Bundled '+(original.length+newer.length)+' paired prose messages for offline use.');
