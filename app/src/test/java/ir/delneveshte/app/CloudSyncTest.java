package ir.delneveshte.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class CloudSyncTest {
    private Context app;
    private Settings settings;
    @Before public void reset() throws Exception {
        app=RuntimeEnvironment.getApplication();closeStore();app.deleteDatabase("messages.db");
        app.getSharedPreferences("settings",0).edit().clear().commit();
        app.getSharedPreferences("cloud",0).edit().clear().commit();
        settings=new Settings();settings.recipient="+989121234567";settings.subscriptionId=7;
    }
    private void closeStore() throws Exception {
        Field field=AppStore.class.getDeclaredField("db");field.setAccessible(true);
        SQLiteDatabase old=(SQLiteDatabase)field.get(null);if(old!=null&&old.isOpen())old.close();field.set(null,null);
    }
    private CloudConfig enabled() {
        return new CloudConfig(app);
    }
    private JSONObject ack(JSONObject payload) throws Exception {
        JSONArray rows=payload.getJSONArray("messages"),acks=new JSONArray();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);JSONArray ids=new JSONArray(),events=row.getJSONArray("events");
            for(int n=0;n<events.length();n++)ids.put(events.getJSONObject(n).getString("id"));
            acks.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")).put("event_ids",ids));
        }
        return new JSONObject().put("acks",acks);
    }
    @Test public void versionOneUpgradePreservesScheduleHistoryAndSettings() throws Exception {
        SQLiteDatabase old=app.openOrCreateDatabase("messages.db",0,null);
        old.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,recipient TEXT NOT NULL,simLabel TEXT,subscriptionId INTEGER,approvedAt INTEGER,plannedAt INTEGER DEFAULT 0,submittedAt INTEGER DEFAULT 0,status TEXT,detail TEXT DEFAULT '',planDay TEXT DEFAULT '',partCount INTEGER DEFAULT 0)");
        old.execSQL("CREATE TABLE parts(messageId INTEGER,part INTEGER,success INTEGER,PRIMARY KEY(messageId,part))");
        old.execSQL("INSERT INTO messages(id,body,recipient,subscriptionId,approvedAt,plannedAt,status) VALUES(21,'دوستت دارم ❤️','+989121234567',7,1000,999999,'SCHEDULED')");
        old.execSQL("INSERT INTO messages(id,body,recipient,subscriptionId,approvedAt,submittedAt,status) VALUES(22,'کنارتم 🫂','+989121234567',7,1000,2000,'SENT')");
        old.setVersion(1);old.close();app.getSharedPreferences("settings",0).edit().putString("recipient",settings.recipient).putInt("sim",7).commit();
        AppStore store=new AppStore(app);Message planned=store.get(21);
        assertEquals("SCHEDULED",planned.status);assertEquals(999999,planned.plannedAt);
        assertEquals("دوستت دارم ❤️",planned.body);assertEquals(7,planned.subscriptionId);assertEquals(settings.recipient,planned.recipient);
        assertEquals("SENT",store.get(22).status);assertEquals(2000,store.get(22).submittedAt);
        assertEquals(settings.recipient,store.settings().recipient);assertEquals(2,store.pendingSyncCount());
        UUID.fromString(planned.syncId);String id=planned.syncId;closeStore();assertEquals(id,new AppStore(app).get(21).syncId);
    }
    @Test public void acknowledgementDoesNotLoseNewerOfflineState() throws Exception {
        AppStore store=new AppStore(app);long id=store.approve("دلم پیش توست 💌",settings,"SIM");CloudConfig config=enabled();
        CloudSync.uploadBatch(store,(path,payload)->{store.cancel(id);return ack(payload);},config,config.generation());
        assertEquals("CANCELLED",store.get(id).status);assertEquals(1,store.pendingSyncCount());
        JSONObject pending=store.pendingSync(5).getJSONObject(0);assertEquals(2,pending.getLong("revision"));
        assertEquals("CANCELLED",pending.getJSONArray("events").getJSONObject(0).getString("status"));
        CloudSync.uploadBatch(store,(path,payload)->ack(payload),config,config.generation());assertEquals(0,store.pendingSyncCount());
    }
    @Test public void longEventHistoryDrainsAcrossBatchesWithoutLoss() throws Exception {
        AppStore store=new AppStore(app);long id=store.approve("به یادت هستم 💭❤️",settings,"SIM");
        for(int n=0;n<12;n++)store.schedule(id,10000+n,"2026-11-01");
        CloudConfig config=enabled();int calls=0;
        while(store.pendingSyncCount()>0&&calls++<5)CloudSync.uploadBatch(store,(path,payload)->ack(payload),config,config.generation());
        assertEquals(3,calls);assertEquals(0,store.pendingSyncCount());assertEquals(13,store.get(id).revision);
    }
    @Test public void malformedAcknowledgementCannotClearOutbox() throws Exception {
        AppStore store=new AppStore(app);store.approve("عزیز دلمی ❤️",settings,"SIM");CloudConfig config=enabled();
        try {
            CloudSync.uploadBatch(store,(path,payload)->{JSONObject response=ack(payload);response.getJSONArray("acks").getJSONObject(0).put("revision",999);return response;},config,config.generation());
            fail("Invalid response must fail");
        }catch(IllegalStateException expected){assertEquals(1,store.pendingSyncCount());}
    }
    @Test public void changingPolicyGenerationDuringUploadLeavesLocalDataPending() throws Exception {
        AppStore store=new AppStore(app);store.approve("لبخندت را دوست دارم 😊❤️",settings,"SIM");CloudConfig config=enabled();int generation=config.generation();
        assertFalse(CloudSync.uploadBatch(store,(path,payload)->{app.getSharedPreferences("cloud",0).edit().putInt("generation",config.generation()+1).commit();return ack(payload);},config,generation));
        assertEquals(1,store.pendingSyncCount());
    }
    @Test public void catalogUpdatesCannotRewriteApprovedMessage() throws Exception {
        AppStore store=new AppStore(app);String id=UUID.randomUUID().toString();
        store.replaceCatalog("کوتاه",new JSONArray().put(new JSONObject().put("id",id).put("body","{name}، کنار تو آرامم 🫂")));
        String selected=store.cachedSuggestions("کوتاه","جانم").get(0);long message=store.approve(selected,settings,"SIM");
        store.replaceCatalog("کوتاه",new JSONArray());assertTrue(store.hasCachedCatalog("کوتاه"));assertTrue(store.cachedSuggestions("کوتاه","جانم").isEmpty());
        assertEquals("جانم، کنار تو آرامم 🫂",store.get(message).body);
    }
    @Test public void managedServerIgnoresLegacyDisableAndCustomUrlWithoutReplacingIdentity(){
        String id=UUID.randomUUID().toString(),credential="0123456789abcdef";
        app.getSharedPreferences("cloud",0).edit().putString("url","https://old.example.com").putBoolean("enabled",false)
                .putString("installation_id",id).putString("credential",credential).putInt("generation",7).commit();
        CloudConfig cloud=new CloudConfig(app);assertTrue(cloud.enabled());assertEquals(CloudConfig.normalizeUrl(BuildConfig.API_BASE_URL),cloud.baseUrl());
        assertEquals(id,cloud.installationId());assertEquals(credential,cloud.credential());assertEquals(8,cloud.generation());assertFalse(cloud.registered());
        CloudConfig reopened=new CloudConfig(app);assertEquals(8,reopened.generation());assertTrue(reopened.enabled());
        app.getSharedPreferences("cloud",0).edit().putBoolean("enabled",false).putString("url","https://ignored.example.com").commit();assertTrue(reopened.enabled());assertEquals(cloud.baseUrl(),new CloudConfig(app).baseUrl());
    }
    @Test public void sourceMetadataNeverBecomesApprovedSmsBody() throws Exception {
        AppStore store=new AppStore(app);String body="دوستت دارم و کنار تو آرامم 💕";
        store.replaceCatalog("کوتاه",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("body",body).put("source","namnak").put("author","نویسنده").put("source_url","https://namnak.com/example")));
        String selected=store.cachedSuggestions("کوتاه","جانم").get(0);
        assertTrue(store.catalogCredit(selected).contains("https://namnak.com/example"));
        long id=store.approve(selected,settings,"SIM");assertEquals(body,store.get(id).body);
        assertFalse(store.get(id).body.contains("namnak"));
    }
    @Test public void unseenSuggestionsRotateAndApprovedTextsStayExcluded() throws Exception {
        AppStore store=new AppStore(app);JSONArray rows=new JSONArray();
        for(int i=0;i<12;i++)rows.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("body","پیام عاشقانهٔ "+i+" 💕"));
        store.replaceCatalog("کوتاه",rows);
        java.util.Set<String> first=new java.util.HashSet<>(store.cachedSuggestions("کوتاه","جانم"));
        java.util.Set<String> second=new java.util.HashSet<>(new AppStore(app).cachedSuggestions("کوتاه","جانم"));
        assertEquals(5,first.size());assertEquals(5,second.size());assertTrue(java.util.Collections.disjoint(first,second));
        java.util.List<String> third=store.cachedSuggestions("کوتاه","جانم");assertEquals(2,third.size());
        String approved=first.iterator().next();store.approve(approved,settings,"SIM");
        for(int i=0;i<6;i++)assertFalse(store.cachedSuggestions("کوتاه","جانم").contains(approved));
    }
    @Test public void credentialsPersistAcrossConfigInstances() {
        CloudConfig first=new CloudConfig(app),second=new CloudConfig(app);
        assertEquals(first.installationId(),second.installationId());assertEquals(first.credential(),second.credential());
        assertEquals(64,first.credential().length());assertTrue(first.enabled());
    }
    @Test public void versionTwoUpgradePreservesAnExistingRandomScheduleAndItsIdentity() throws Exception {
        closeStore();app.deleteDatabase("messages.db");SQLiteDatabase old=app.openOrCreateDatabase("messages.db",0,null);
        old.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT NOT NULL,recipient TEXT NOT NULL,simLabel TEXT,subscriptionId INTEGER,approvedAt INTEGER,plannedAt INTEGER DEFAULT 0,submittedAt INTEGER DEFAULT 0,status TEXT,detail TEXT DEFAULT '',planDay TEXT DEFAULT '',partCount INTEGER DEFAULT 0,sync_id TEXT,revision INTEGER NOT NULL DEFAULT 1,synced_revision INTEGER NOT NULL DEFAULT 0)");
        old.execSQL("CREATE TABLE parts(messageId INTEGER,part INTEGER,success INTEGER,PRIMARY KEY(messageId,part))");
        old.execSQL("CREATE TABLE sync_events(id TEXT PRIMARY KEY,message_id INTEGER NOT NULL REFERENCES messages(id),status TEXT NOT NULL,revision INTEGER NOT NULL,occurred_at INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE catalog_cache(id TEXT PRIMARY KEY,body TEXT NOT NULL,category TEXT NOT NULL)");old.execSQL("CREATE TABLE catalog_cache_state(category TEXT PRIMARY KEY,updated_at INTEGER NOT NULL)");
        String identity=UUID.randomUUID().toString();old.execSQL("INSERT INTO messages(id,body,recipient,simLabel,subscriptionId,approvedAt,plannedAt,status,planDay,sync_id,revision,synced_revision) VALUES(21,'کنارت آرامم 💕','+989121234567','SIM',7,1000,999999,'SCHEDULED','2026-11-05',?,4,3)",new Object[]{identity});old.setVersion(2);old.close();
        AppStore store=new AppStore(app);Message m=store.get(21);assertEquals("SCHEDULED",m.status);assertEquals(999999,m.plannedAt);assertEquals(identity,m.syncId);assertEquals(4,m.revision);assertEquals("RANDOM",m.sendMode);assertEquals(0,m.requestedAt);assertEquals("کنارت آرامم 💕",m.body);assertEquals("+989121234567",m.recipient);assertEquals(7,m.subscriptionId);
    }

}
