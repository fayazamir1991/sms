package ir.delneveshte.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Upload-only synchronization: a server can never enqueue or send an SMS on this phone. */
public final class CloudSync {
    private static final Object LOCK=new Object();
    public interface Transport { JSONObject request(String path,JSONObject body) throws Exception; }
    private CloudSync() { }
    public static boolean run(Context context,AtomicBoolean stopped) {
        synchronized(LOCK) {
            CloudConfig config=new CloudConfig(context);
            if(!config.enabled()||stopped.get())return false;
            int generation=config.generation();AppStore store=new AppStore(context);
            try {
                Transport api=new CloudApi(config);
                api.request("/api/v1/installations/register",new JSONObject().put("app_version",BuildConfig.VERSION_NAME).put("device_hash",config.deviceHash()));
                if(System.currentTimeMillis()-config.lastCatalog()>30*60*1000L) {
                    for(String category:TextBank.categories()) {
                        if("همه".equals(category))continue;
                        JSONObject response=api.request("/api/v1/messages/browse?category="+URLEncoder.encode(category,"UTF-8"),null);
                        JSONArray messages=response.getJSONArray("messages");
                        if(messages.length()>50)throw new IllegalStateException("پاسخ بانک پیام‌ها معتبر نیست.");
                        for(int i=0;i<messages.length();i++){
                            JSONObject row=messages.getJSONObject(i);
                            if(!category.equals(row.getString("category"))||row.getString("body").length()>2400)throw new IllegalStateException("متن پیشنهادی معتبر نیست.");
                        }
                        if(stopped.get()||!config.enabled()||config.generation()!=generation)return false;
                        store.replaceCatalog(category,messages);
                    }
                    config.catalogUpdated();
                }
                long started=System.currentTimeMillis();int batches=0;
                while(!stopped.get()&&config.enabled()&&config.generation()==generation&&batches++<20&&System.currentTimeMillis()-started<90000) {
                    if(!uploadBatch(store,api,config,generation))break;
                }
                if(stopped.get()||!config.enabled()||config.generation()!=generation)return false;
                config.success();return store.pendingSyncCount()>0;
            }catch(CloudApi.Failure error){config.error(error.getMessage());return error.retryable();}
            catch(Exception error){config.error("همگام‌سازی کامل نشد؛ صف گوشی محفوظ است و دوباره تلاش می‌شود.");return true;}
        }
    }
    /** Visible to package tests so acknowledgement races can be tested without network or real SMS. */
    static boolean uploadBatch(AppStore store,Transport api,CloudConfig config,int generation) throws Exception {
        JSONArray rows=store.pendingSync(5);if(rows.length()==0)return false;
        JSONObject response=api.request("/api/v1/sync",new JSONObject().put("messages",rows));
        JSONArray acks=response.getJSONArray("acks");
        if(acks.length()!=rows.length())throw new IllegalStateException("پاسخ همگام‌سازی کامل نیست.");
        Map<String,JSONObject> expected=new HashMap<>();for(int i=0;i<rows.length();i++)expected.put(rows.getJSONObject(i).getString("id"),rows.getJSONObject(i));
        Set<String> seen=new HashSet<>();
        for(int i=0;i<acks.length();i++) {
            JSONObject ack=acks.getJSONObject(i),row=expected.get(ack.getString("id"));
            if(row==null||!seen.add(ack.getString("id"))||ack.getLong("revision")!=row.getLong("revision"))throw new IllegalStateException("تأیید سرور با پیام ارسالی مطابقت ندارد.");
            Set<String> events=new HashSet<>();JSONArray sent=row.getJSONArray("events"),returned=ack.getJSONArray("event_ids");
            for(int n=0;n<sent.length();n++)events.add(sent.getJSONObject(n).getString("id"));
            if(returned.length()!=events.size())throw new IllegalStateException("تأیید رخدادهای پیام کامل نیست.");
            for(int n=0;n<returned.length();n++)if(!events.remove(returned.getString(n)))throw new IllegalStateException("رخداد ناشناس در پاسخ سرور.");
        }
        if(!config.enabled()||config.generation()!=generation)return false;
        for(int i=0;i<acks.length();i++){
            JSONObject ack=acks.getJSONObject(i);JSONArray events=ack.getJSONArray("event_ids");List<String> ids=new ArrayList<>();
            for(int n=0;n<events.length();n++)ids.add(events.getString(n));
            store.acknowledgeSync(ack.getString("id"),ack.getLong("revision"),ids);
        }
        return true;
    }
}
