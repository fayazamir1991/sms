package ir.delneveshte.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local approvals remain authoritative; network responses only acknowledge uploads. */
public final class AppStore {
    private static final Object LOCK = new Object();
    private static SQLiteDatabase db;
    private final Context app;
    private final SharedPreferences prefs;

    public AppStore(Context context) {
        app = context.getApplicationContext();
        synchronized (LOCK) { if (db == null) db = new Helper(app).getWritableDatabase(); }
        prefs = app.getSharedPreferences("settings", 0);
    }
    private static class Helper extends SQLiteOpenHelper {
        Helper(Context c) { super(c, "messages.db", null, 2); }
        @Override public void onConfigure(SQLiteDatabase d) { d.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,recipient TEXT NOT NULL,simLabel TEXT,subscriptionId INTEGER,approvedAt INTEGER,plannedAt INTEGER DEFAULT 0,submittedAt INTEGER DEFAULT 0,status TEXT,detail TEXT DEFAULT '',planDay TEXT DEFAULT '',partCount INTEGER DEFAULT 0,sync_id TEXT,revision INTEGER NOT NULL DEFAULT 1,synced_revision INTEGER NOT NULL DEFAULT 0)");
            d.execSQL("CREATE TABLE parts(messageId INTEGER,part INTEGER,success INTEGER,PRIMARY KEY(messageId,part))");
            syncTables(d);
        }
        @Override public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion) {
            if(oldVersion!=1 || newVersion!=2) throw new IllegalStateException("Unsupported schema; preserving existing data");
            d.execSQL("ALTER TABLE messages ADD COLUMN sync_id TEXT");
            d.execSQL("ALTER TABLE messages ADD COLUMN revision INTEGER NOT NULL DEFAULT 1");
            d.execSQL("ALTER TABLE messages ADD COLUMN synced_revision INTEGER NOT NULL DEFAULT 0");
            syncTables(d);
            try(Cursor c=d.rawQuery("SELECT id,status,approvedAt,submittedAt FROM messages",null)) {
                while(c.moveToNext()) {
                    long id=c.getLong(0);ContentValues v=new ContentValues();
                    v.put("sync_id",UUID.randomUUID().toString());d.update("messages",v,"id=?",new String[]{""+id});
                    event(d,id,c.getString(1),1,Math.max(c.getLong(2),c.getLong(3)));
                }
            }
        }
        private void syncTables(SQLiteDatabase d) {
            d.execSQL("CREATE UNIQUE INDEX messages_sync_id ON messages(sync_id)");
            d.execSQL("CREATE TABLE sync_events(id TEXT PRIMARY KEY,message_id INTEGER NOT NULL REFERENCES messages(id),status TEXT NOT NULL,revision INTEGER NOT NULL,occurred_at INTEGER NOT NULL)");
            d.execSQL("CREATE INDEX sync_events_message ON sync_events(message_id,revision)");
            d.execSQL("CREATE TABLE catalog_cache(id TEXT PRIMARY KEY,body TEXT NOT NULL,category TEXT NOT NULL)");
            d.execSQL("CREATE INDEX catalog_cache_category ON catalog_cache(category)");
            d.execSQL("CREATE TABLE catalog_cache_state(category TEXT PRIMARY KEY,updated_at INTEGER NOT NULL)");
        }
    }
    public Settings settings() {
        Settings s=new Settings();s.recipient=prefs.getString("recipient","");s.nickname=prefs.getString("nickname","عزیزم");
        s.dailyCount=prefs.getInt("count",2);s.startMinute=prefs.getInt("start",540);s.endMinute=prefs.getInt("end",1260);
        s.subscriptionId=prefs.getInt("sim",-1);s.enabled=prefs.getBoolean("enabled",false);return s;
    }
    public void saveSettings(Settings s) {
        if(!prefs.edit().putString("recipient",s.recipient).putString("nickname",s.nickname).putInt("count",s.dailyCount)
                .putInt("start",s.startMinute).putInt("end",s.endMinute).putInt("sim",s.subscriptionId)
                .putBoolean("enabled",s.enabled).commit()) throw new IllegalStateException("ذخیره تنظیمات ناموفق بود");
    }
    private static ContentValues values(String state,String detail) {
        ContentValues v=new ContentValues();v.put("status",state);v.put("detail",detail);return v;
    }
    private static void event(SQLiteDatabase d,long id,String state,long revision,long time) {
        ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("message_id",id);
        v.put("status",state);v.put("revision",revision);v.put("occurred_at",Math.max(1,time));
        d.insertOrThrow("sync_events",null,v);
    }
    public long approve(String body,Settings s,String label) { return insert(body,s,label,"APPROVED"); }
    public long approveImmediate(String body,Settings s,String label) { return insert(body,s,label,"IMMEDIATE"); }
    private long insert(String body,Settings s,String label,String state) {
        if(!s.valid()||body==null||body.trim().isEmpty()) throw new IllegalArgumentException("متن و تنظیمات را بررسی کنید");
        long id;
        synchronized(LOCK) {
            db.beginTransaction();
            try {
                long now=System.currentTimeMillis();ContentValues v=values(state,"");
                v.put("body",body);v.put("recipient",s.recipient);v.put("subscriptionId",s.subscriptionId);
                v.put("simLabel",label);v.put("approvedAt",now);v.put("sync_id",UUID.randomUUID().toString());
                id=db.insertOrThrow("messages",null,v);event(db,id,state,1,now);db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        CloudScheduler.requestSync(app);return id;
    }
    private static Message row(Cursor c) {
        Message m=new Message();m.id=c.getLong(c.getColumnIndexOrThrow("id"));
        m.body=c.getString(c.getColumnIndexOrThrow("body"));m.recipient=c.getString(c.getColumnIndexOrThrow("recipient"));
        m.simLabel=c.getString(c.getColumnIndexOrThrow("simLabel"));m.status=c.getString(c.getColumnIndexOrThrow("status"));
        m.detail=c.getString(c.getColumnIndexOrThrow("detail"));m.planDay=c.getString(c.getColumnIndexOrThrow("planDay"));
        m.subscriptionId=c.getInt(c.getColumnIndexOrThrow("subscriptionId"));m.partCount=c.getInt(c.getColumnIndexOrThrow("partCount"));
        m.approvedAt=c.getLong(c.getColumnIndexOrThrow("approvedAt"));m.plannedAt=c.getLong(c.getColumnIndexOrThrow("plannedAt"));
        m.submittedAt=c.getLong(c.getColumnIndexOrThrow("submittedAt"));m.syncId=c.getString(c.getColumnIndexOrThrow("sync_id"));
        m.revision=c.getLong(c.getColumnIndexOrThrow("revision"));return m;
    }
    private List<Message> list(String where,String order) {
        synchronized(LOCK) {
            List<Message> out=new ArrayList<>();
            try(Cursor c=db.query("messages",null,where,null,null,null,order)) { while(c.moveToNext())out.add(row(c)); }
            return out;
        }
    }
    public Message get(long id) { List<Message> rows=list("id="+id,"id");return rows.isEmpty()?null:rows.get(0); }
    public List<Message> queue() { return list("status IN ('APPROVED','SCHEDULED')","approvedAt,id"); }
    public List<Message> history() { return list("status NOT IN ('APPROVED','SCHEDULED')","id DESC LIMIT 100"); }
    public List<Message> unscheduled() { return list("status='APPROVED'","approvedAt,id"); }
    public List<Message> scheduled() { return list("status='SCHEDULED'","plannedAt,id"); }
    public int approvedCount() { return queue().size(); }

    /** A state update, its revision and the durable outbox event commit atomically. */
    private int mutate(ContentValues v,String where,String[] args) {
        int count;
        synchronized(LOCK) {
            db.beginTransaction();
            try {
                List<Long> ids=new ArrayList<>();
                try(Cursor c=db.query("messages",new String[]{"id"},where,args,null,null,null)) { while(c.moveToNext())ids.add(c.getLong(0)); }
                count=db.update("messages",v,where,args);
                for(long id:ids) {
                    db.execSQL("UPDATE messages SET revision=revision+1 WHERE id=?",new Object[]{id});
                    Message m=get(id);event(db,id,m.status,m.revision,System.currentTimeMillis());
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        if(count>0)CloudScheduler.requestSync(app);return count;
    }
    public boolean cancel(long id) { return mutate(values("CANCELLED","لغو توسط شما"),"id=? AND status IN ('APPROVED','SCHEDULED')",new String[]{""+id})==1; }
    public void requeueFuture() {
        ContentValues v=values("APPROVED","");v.put("plannedAt",0);v.put("planDay","");
        mutate(v,"status='SCHEDULED'",null);
    }
    public void recoverUncertain(long now) {
        mutate(values("UNCERTAIN","پاسخ قطعی ارسال دریافت نشد؛ ارسال مجدد خودکار انجام نمی‌شود"),"status='SENDING' AND submittedAt<?",new String[]{""+(now-900000)});
        mutate(values("FAILED","آماده‌سازی ارسال فوری کامل نشد؛ ارسال خودکار انجام نمی‌شود"),"status='IMMEDIATE' AND approvedAt<?",new String[]{""+(now-900000)});
    }
    public void expireSchedules(long now) { mutate(values("APPROVED",""),"status='SCHEDULED' AND plannedAt<?",new String[]{""+now}); }
    public int attemptedOn(String day) {
        synchronized(LOCK) { try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM messages WHERE planDay=? AND submittedAt>0",new String[]{day})) { c.moveToFirst();return c.getInt(0); } }
    }
    public boolean schedule(long id,long at,String day) {
        ContentValues v=values("SCHEDULED","");v.put("plannedAt",at);v.put("planDay",day);
        return mutate(v,"id=? AND status IN ('APPROVED','SCHEDULED')",new String[]{""+id})==1;
    }
    public boolean claim(long id,long now,int parts) {
        if(parts<=0)return false;
        ContentValues v=values("SENDING","");v.put("submittedAt",now);v.put("partCount",parts);
        v.put("planDay",java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString());
        return mutate(v,"id=? AND status IN ('SCHEDULED','IMMEDIATE')",new String[]{""+id})==1;
    }
    public void failBeforeSend(long id,String detail) { mutate(values("FAILED",detail),"id=? AND status IN ('APPROVED','SCHEDULED','IMMEDIATE')",new String[]{""+id}); }
    public void failAttempt(long id,String detail) { mutate(values("UNCERTAIN",detail),"id=? AND status='SENDING'",new String[]{""+id}); }
    public void recordPart(long id,int index,boolean success,String detail) {
        synchronized(LOCK) {
            db.beginTransaction();
            try {
                Message m=get(id);
                if(m==null||index<0||index>=m.partCount||!(m.status.equals("SENDING")||m.status.equals("UNCERTAIN")||m.status.equals("FAILED")))return;
                ContentValues part=new ContentValues();part.put("messageId",id);part.put("part",index);part.put("success",success?1:0);
                if(db.insertWithOnConflict("parts",null,part,SQLiteDatabase.CONFLICT_IGNORE)==-1)return;
                try(Cursor c=db.rawQuery("SELECT COUNT(*),MIN(success) FROM parts WHERE messageId=?",new String[]{""+id})) {
                    c.moveToFirst();
                    if(c.getInt(1)==0&&!m.status.equals("FAILED"))mutate(values("FAILED",detail),"id=?",new String[]{""+id});
                    else if(c.getInt(1)!=0&&c.getInt(0)==m.partCount)mutate(values("SENT","ارسال به شبکه انجام شد؛ رسید تحویل نیست"),"id=?",new String[]{""+id});
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }

    public int pendingSyncCount() {
        synchronized(LOCK) { try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM messages m WHERE revision>synced_revision OR EXISTS(SELECT 1 FROM sync_events e WHERE e.message_id=m.id)",null)) { c.moveToFirst();return c.getInt(0); } }
    }
    public JSONArray pendingSync(int limit) throws JSONException {
        synchronized(LOCK) {
            JSONArray out=new JSONArray();
            for(Message m:list("revision>synced_revision OR EXISTS(SELECT 1 FROM sync_events e WHERE e.message_id=messages.id)","id LIMIT "+Math.max(1,Math.min(5,limit)))) {
                JSONObject v=new JSONObject().put("id",m.syncId).put("body",m.body).put("recipient",m.recipient)
                    .put("sim_id",m.subscriptionId).put("status",m.status).put("revision",m.revision)
                    .put("approved_at",m.approvedAt).put("planned_at",m.plannedAt).put("submitted_at",m.submittedAt);
                JSONArray events=new JSONArray();
                try(Cursor c=db.rawQuery("SELECT id,status,revision,occurred_at FROM sync_events WHERE message_id=? ORDER BY revision,id LIMIT 5",new String[]{""+m.id})) {
                    while(c.moveToNext())events.put(new JSONObject().put("id",c.getString(0)).put("status",c.getString(1))
                        .put("revision",c.getLong(2)).put("occurred_at",c.getLong(3)));
                }
                out.put(v.put("events",events));
            }
            return out;
        }
    }
    public void acknowledgeSync(String syncId,long revision,List<String> events) {
        synchronized(LOCK) {
            db.beginTransaction();
            try {
                db.execSQL("UPDATE messages SET synced_revision=MAX(synced_revision,MIN(revision,?)) WHERE sync_id=?",new Object[]{revision,syncId});
                for(String event:events)db.delete("sync_events","id=? AND revision<=? AND message_id=(SELECT id FROM messages WHERE sync_id=?)",new String[]{event,""+revision,syncId});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    public void replaceCatalog(String category,JSONArray rows) throws JSONException {
        synchronized(LOCK) {
            db.beginTransaction();
            try {
                db.delete("catalog_cache","category=?",new String[]{category});
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.getJSONObject(i);ContentValues v=new ContentValues();
                    app.getSharedPreferences("catalog_metadata",0).edit().putString(row.getString("body"),row.toString()).apply();
                    v.put("id",row.getString("id"));v.put("body",row.getString("body"));v.put("category",category);
                    db.insertWithOnConflict("catalog_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                }
                ContentValues state=new ContentValues();state.put("category",category);state.put("updated_at",System.currentTimeMillis());
                db.insertWithOnConflict("catalog_cache_state",null,state,SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    public boolean hasCachedCatalog(String category) {
        synchronized(LOCK) {
            try(Cursor c=db.rawQuery("SELECT 1 FROM catalog_cache_state"+("همه".equals(category)?"":" WHERE category=?")+" LIMIT 1","همه".equals(category)?null:new String[]{category})) { return c.moveToFirst(); }
        }
    }
    public void clearCatalog() {
        synchronized(LOCK) {
            db.beginTransaction();
            try { db.delete("catalog_cache",null,null);db.delete("catalog_cache_state",null,null);db.setTransactionSuccessful(); }
            finally { db.endTransaction(); }
        }
    }
    public void rememberCatalog(JSONArray rows) throws JSONException {
        synchronized(LOCK){db.beginTransaction();try{
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);ContentValues v=new ContentValues();v.put("id",row.getString("id"));v.put("body",row.getString("body"));v.put("category",row.getString("category"));db.insertWithOnConflict("catalog_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                app.getSharedPreferences("catalog_metadata",0).edit().putString(row.getString("body"),row.toString()).apply();
            }db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
    }
    public List<String> localCatalogPage(String category,String query,int offset){
        synchronized(LOCK){List<String> out=new ArrayList<>();List<String> args=new ArrayList<>();
            String where="1=1";
            if(!"همه".equals(category)){where+=" AND category=?";args.add(category);}
            String key=CatalogSearch.key(query);int skipped=0;
            try(Cursor c=db.rawQuery("SELECT body FROM catalog_cache WHERE "+where+" ORDER BY id",args.toArray(new String[0]))){
                while(c.moveToNext())if(CatalogSearch.key(c.getString(0)).contains(key)){
                    if(skipped++<Math.max(0,offset))continue;
                    out.add(c.getString(0));if(out.size()==21)break;
                }
            }return out;
        }
    }
    public String catalogCredit(String text) {
        try {
            JSONObject row=new JSONObject(app.getSharedPreferences("catalog_metadata",0).getString(text,"{}"));
            String source=row.isNull("source")?"بانک دل‌نوشته":row.optString("source","بانک دل‌نوشته");
            String author=row.isNull("author")?"":row.optString("author","");
            String url=row.isNull("source_url")?"":row.optString("source_url","");
            String rights=row.isNull("rights")?"":row.optString("rights","");
            return "منبع: "+source+(author.isEmpty()?"":"\nپدیدآورنده: "+author)+(url.isEmpty()?"":"\n"+url)+(rights.isEmpty()?"":"\n"+rights);
        }catch(Exception error){return "بانک دل‌نوشته";}
    }
    public List<String> cachedSuggestions(String category,String nickname) {
        String name=nickname==null?"":nickname.trim().replaceAll("\\s+"," ");if(name.isEmpty())name="عزیزم";
        synchronized(LOCK) {
            List<String[]> pool=new ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT id,body FROM catalog_cache WHERE NOT EXISTS(SELECT 1 FROM messages m WHERE m.body=catalog_cache.body)"+("همه".equals(category)?"":" AND category=?")+" ORDER BY RANDOM()","همه".equals(category)?null:new String[]{category})) {while(c.moveToNext())pool.add(new String[]{c.getString(0),c.getString(1)});}
            java.util.Set<String> approved=new java.util.HashSet<>();
            try(Cursor c=db.rawQuery("SELECT body FROM messages",null)){while(c.moveToNext())approved.add(c.getString(0));}
            final String actualName=name;pool.removeIf(item->approved.contains(item[1].replace("{name}",actualName)));
            android.content.SharedPreferences seenPrefs=app.getSharedPreferences("catalog_seen",0);
            java.util.Set<String> seen=new java.util.HashSet<>(seenPrefs.getStringSet("ids",java.util.Collections.emptySet()));
            List<String[]> selected=new ArrayList<>();
            for(String[] item:pool)if(!seen.contains(item[0])&&selected.size()<5)selected.add(item);
            if(selected.isEmpty()) {for(String[] item:pool)seen.remove(item[0]);for(String[] item:pool){if(selected.size()==5)break;selected.add(item);}}
            List<String> out=new ArrayList<>();
            for(String[] item:selected){seen.add(item[0]);String raw=item[1],rendered=raw.replace("{name}",name);String meta=app.getSharedPreferences("catalog_metadata",0).getString(raw,"{}");app.getSharedPreferences("catalog_metadata",0).edit().putString(rendered,meta).apply();out.add(rendered);}
            seenPrefs.edit().putStringSet("ids",seen).apply();return out;
        }
    }
}
