import test from 'node:test';
import assert from 'node:assert/strict';
import {catalog,seedSql} from '../scripts/build-seed.mjs';
import {TestD1} from './d1.mjs';
test('initial deployment creates at least 1000 distinct messages and does not duplicate or overwrite on retry',async()=>{
 const data=catalog(),seed=seedSql(),db=new TestD1();
 assert.ok(data.length>=1000);assert.equal(new Set(data.map(r=>r.fingerprint)).size,data.length);
 db.sqlite.exec(seed.sql);db.sqlite.exec(seed.sql);
 assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM catalog_messages').first()).n,data.length);
 assert.equal((await db.prepare('SELECT COUNT(*) AS n FROM seed_runs').first()).n,1);
 await db.prepare('UPDATE catalog_messages SET body=?,fingerprint=? WHERE id=?').bind('متن ویرایش‌شده توسط مدیر 🌷','changed-by-admin',data[0].id).run();
 db.sqlite.exec(seed.sql);
 assert.equal((await db.prepare('SELECT body FROM catalog_messages WHERE id=?').bind(data[0].id).first()).body,'متن ویرایش‌شده توسط مدیر 🌷');
 db.close();
});
