import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
export class TestD1 {
 constructor(){this.sqlite=new DatabaseSync(':memory:');this.sqlite.exec(readFileSync(new URL('../migrations/0001_schema.sql',import.meta.url),'utf8'));this.sqlite.exec(readFileSync(new URL('../migrations/0003_catalog_imports.sql',import.meta.url),'utf8'));this.sqlite.exec(readFileSync(new URL('../migrations/0004_web_frontier.sql',import.meta.url),'utf8'));}
 prepare(sql){return new Statement(this.sqlite,sql);}
 async batch(statements){this.sqlite.exec('BEGIN');try{const out=[];for(const s of statements)out.push(await s.execute());this.sqlite.exec('COMMIT');return out;}catch(e){this.sqlite.exec('ROLLBACK');throw e;}}
 close(){this.sqlite.close();}
}
class Statement {
 constructor(db,sql,args=[]){this.db=db;this.sql=sql;this.args=args;}
 bind(...args){return new Statement(this.db,this.sql,args);}
 async all(){const results=this.db.prepare(this.sql).all(...this.args).map(r=>({...r}));return {success:true,results,meta:{changes:0}};}
 async first(){return (await this.all()).results[0]||null;}
 async run(){const r=this.db.prepare(this.sql).run(...this.args);return {success:true,results:[],meta:{changes:Number(r.changes)}};}
 async execute(){return /^\s*SELECT\b/i.test(this.sql)?this.all():this.run();}
}
