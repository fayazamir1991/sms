import {DatabaseSync} from 'node:sqlite';
import {mkdirSync,readFileSync,chmodSync} from 'node:fs';
import {dirname} from 'node:path';

/** Same query interface as D1, backed by a single durable SQLite connection. */
export class SQLiteStore {
 constructor(filename){
  mkdirSync(dirname(filename),{recursive:true,mode:0o700});
  this.sqlite=new DatabaseSync(filename);
  chmodSync(filename,0o600);
  this.sqlite.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;');
 }
 prepare(sql){return new Statement(this.sqlite,sql);}
 async batch(statements){
  // Do not await inside the transaction: concurrent HTTP requests must not join it.
  this.sqlite.exec('BEGIN IMMEDIATE');
  try{const results=statements.map(s=>s.execute());this.sqlite.exec('COMMIT');return results;}
  catch(error){this.sqlite.exec('ROLLBACK');throw error;}
 }
 migrate(){
  this.sqlite.exec('CREATE TABLE IF NOT EXISTS app_migrations(name TEXT PRIMARY KEY,applied_at INTEGER NOT NULL)');
  const rebuilding=!this.sqlite.prepare("SELECT 1 FROM app_migrations WHERE name='0008_full_rebuild.sql'").get();
  for(const name of ['0001_schema.sql','0002_initial_catalog.sql','0003_catalog_imports.sql','0004_web_frontier.sql','0005_source_metadata.sql','0006_clean_catalog.sql','0007_catalog_offers.sql','0008_full_rebuild.sql','0009_catalog_search.sql','0010_reports.sql']){
   this.sqlite.exec('BEGIN IMMEDIATE');
   try{
    if(!this.sqlite.prepare('SELECT 1 FROM app_migrations WHERE name=?').get(name)){
     this.sqlite.exec(readFileSync(new URL('../migrations/'+name,import.meta.url),'utf8'));
     this.sqlite.prepare('INSERT INTO app_migrations(name,applied_at) VALUES(?,?)').run(name,Date.now());
    }
    this.sqlite.exec('COMMIT');
   }catch(error){this.sqlite.exec('ROLLBACK');throw error;}
  }
  if(rebuilding)this.sqlite.exec('PRAGMA wal_checkpoint(TRUNCATE); VACUUM; PRAGMA wal_checkpoint(TRUNCATE);');
 }
 close(){this.sqlite.close();}
}
class Statement {
 constructor(db,sql,args=[]){this.db=db;this.sql=sql;this.args=args;}
 bind(...args){return new Statement(this.db,this.sql,args);}
 execute(){
  const statement=this.db.prepare(this.sql);
  if(statement.columns().length>0)return {success:true,results:statement.all(...this.args).map(row=>({...row})),meta:{changes:0}};
  const result=statement.run(...this.args);return {success:true,results:[],meta:{changes:Number(result.changes)}};
 }
 async all(){return this.execute();}
 async first(){return this.execute().results[0]||null;}
 async run(){return this.execute();}
}
