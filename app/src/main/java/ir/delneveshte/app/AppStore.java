package ir.delneveshte.app;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

public final class AppStore {
 private static final Object LOCK=new Object();
 private static SQLiteDatabase db;
 private final android.content.SharedPreferences prefs;
 public AppStore(Context c){synchronized(LOCK){if(db==null)db=new Helper(c.getApplicationContext()).getWritableDatabase();}prefs=c.getApplicationContext().getSharedPreferences("settings",0);}
 private static class Helper extends SQLiteOpenHelper {
  Helper(Context c){super(c,"messages.db",null,1);}
  public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,recipient TEXT NOT NULL,simLabel TEXT,subscriptionId INTEGER,approvedAt INTEGER,plannedAt INTEGER DEFAULT 0,submittedAt INTEGER DEFAULT 0,status TEXT,detail TEXT DEFAULT '',planDay TEXT DEFAULT '',partCount INTEGER DEFAULT 0)");d.execSQL("CREATE TABLE parts(messageId INTEGER,part INTEGER,success INTEGER,PRIMARY KEY(messageId,part))");}
  public void onUpgrade(SQLiteDatabase d,int a,int b){throw new IllegalStateException("Unsupported schema");}
 }
 public Settings settings(){Settings s=new Settings();s.recipient=prefs.getString("recipient","");s.nickname=prefs.getString("nickname","عزیزم");s.dailyCount=prefs.getInt("count",2);s.startMinute=prefs.getInt("start",540);s.endMinute=prefs.getInt("end",1260);s.subscriptionId=prefs.getInt("sim",-1);s.enabled=prefs.getBoolean("enabled",false);return s;}
 public void saveSettings(Settings s){if(!prefs.edit().putString("recipient",s.recipient).putString("nickname",s.nickname).putInt("count",s.dailyCount).putInt("start",s.startMinute).putInt("end",s.endMinute).putInt("sim",s.subscriptionId).putBoolean("enabled",s.enabled).commit())throw new IllegalStateException("ذخیره تنظیمات ناموفق بود");}
 private ContentValues values(String status,String detail){ContentValues v=new ContentValues();v.put("status",status);v.put("detail",detail);return v;}
 public long approve(String body,Settings s,String label){if(!s.valid()||body==null||body.trim().isEmpty())throw new IllegalArgumentException("متن و تنظیمات را بررسی کنید");synchronized(LOCK){ContentValues v=values("APPROVED","");v.put("body",body);v.put("recipient",s.recipient);v.put("subscriptionId",s.subscriptionId);v.put("simLabel",label);v.put("approvedAt",System.currentTimeMillis());return db.insertOrThrow("messages",null,v);}}
 private List<Message> list(String where,String order){synchronized(LOCK){List<Message> out=new ArrayList<>();try(Cursor c=db.query("messages",null,where,null,null,null,order)){while(c.moveToNext()){Message m=new Message();m.id=c.getLong(c.getColumnIndexOrThrow("id"));m.body=c.getString(c.getColumnIndexOrThrow("body"));m.recipient=c.getString(c.getColumnIndexOrThrow("recipient"));m.simLabel=c.getString(c.getColumnIndexOrThrow("simLabel"));m.status=c.getString(c.getColumnIndexOrThrow("status"));m.detail=c.getString(c.getColumnIndexOrThrow("detail"));m.planDay=c.getString(c.getColumnIndexOrThrow("planDay"));m.subscriptionId=c.getInt(c.getColumnIndexOrThrow("subscriptionId"));m.partCount=c.getInt(c.getColumnIndexOrThrow("partCount"));m.approvedAt=c.getLong(c.getColumnIndexOrThrow("approvedAt"));m.plannedAt=c.getLong(c.getColumnIndexOrThrow("plannedAt"));m.submittedAt=c.getLong(c.getColumnIndexOrThrow("submittedAt"));out.add(m);}}return out;}}
 public Message get(long id){List<Message> l=list("id="+id,"id");return l.isEmpty()?null:l.get(0);}
 public List<Message> queue(){return list("status IN ('APPROVED','SCHEDULED')","approvedAt,id");}
 public List<Message> history(){return list("status NOT IN ('APPROVED','SCHEDULED')","id DESC LIMIT 100");}
 public List<Message> unscheduled(){return list("status='APPROVED'","approvedAt,id");}
 public List<Message> scheduled(){return list("status='SCHEDULED'","plannedAt,id");}
 public int approvedCount(){return queue().size();}
 public boolean cancel(long id){synchronized(LOCK){return db.update("messages",values("CANCELLED","لغو توسط شما"),"id=? AND status IN ('APPROVED','SCHEDULED')",new String[]{""+id})==1;}}
 public void requeueFuture(){synchronized(LOCK){ContentValues v=values("APPROVED","");v.put("plannedAt",0);v.put("planDay","");db.update("messages",v,"status='SCHEDULED'",null);}}
 public void recoverUncertain(long now){synchronized(LOCK){db.update("messages",values("UNCERTAIN","پاسخ قطعی ارسال دریافت نشد؛ ارسال مجدد خودکار انجام نمی‌شود"),"status='SENDING' AND submittedAt<?",new String[]{""+(now-900000)});}}
 public void expireSchedules(long now){synchronized(LOCK){db.update("messages",values("APPROVED",""),"status='SCHEDULED' AND plannedAt<?",new String[]{""+now});}}
 public int attemptedOn(String day){synchronized(LOCK){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM messages WHERE planDay=? AND submittedAt>0",new String[]{day})){c.moveToFirst();return c.getInt(0);}}}
 public boolean schedule(long id,long at,String day){synchronized(LOCK){ContentValues v=values("SCHEDULED","");v.put("plannedAt",at);v.put("planDay",day);return db.update("messages",v,"id=? AND status IN ('APPROVED','SCHEDULED')",new String[]{""+id})==1;}}
 public boolean claim(long id,long now,int parts){synchronized(LOCK){ContentValues v=values("SENDING","");v.put("submittedAt",now);v.put("partCount",parts);return parts>0&&db.update("messages",v,"id=? AND status='SCHEDULED'",new String[]{""+id})==1;}}
 public void failBeforeSend(long id,String detail){synchronized(LOCK){db.update("messages",values("FAILED",detail),"id=? AND status IN ('APPROVED','SCHEDULED')",new String[]{""+id});}}
 public void failAttempt(long id,String detail){synchronized(LOCK){db.update("messages",values("UNCERTAIN",detail),"id=? AND status='SENDING'",new String[]{""+id});}}
 public void recordPart(long id,int index,boolean success,String detail){synchronized(LOCK){db.beginTransaction();try{Message m=get(id);if(m==null||index<0||index>=m.partCount||!(m.status.equals("SENDING")||m.status.equals("UNCERTAIN")||m.status.equals("FAILED")))return;ContentValues p=new ContentValues();p.put("messageId",id);p.put("part",index);p.put("success",success?1:0);if(db.insertWithOnConflict("parts",null,p,SQLiteDatabase.CONFLICT_IGNORE)==-1)return;try(Cursor c=db.rawQuery("SELECT COUNT(*),MIN(success) FROM parts WHERE messageId=?",new String[]{""+id})){c.moveToFirst();if(c.getInt(1)==0)db.update("messages",values("FAILED",success?m.detail:detail),"id=?",new String[]{""+id});else if(c.getInt(0)==m.partCount)db.update("messages",values("SENT","ارسال به شبکه انجام شد؛ رسید تحویل نیست"),"id=?",new String[]{""+id});}db.setTransactionSuccessful();}finally{db.endTransaction();}}}
}
