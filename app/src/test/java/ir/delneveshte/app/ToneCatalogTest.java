package ir.delneveshte.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class ToneCatalogTest {
    private Context app;
    private void closeStore() throws Exception {Field field=AppStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(null);if(db!=null&&db.isOpen())db.close();field.set(null,null);}
    @Before public void reset() throws Exception {app=RuntimeEnvironment.getApplication();closeStore();app.deleteDatabase("messages.db");app.getSharedPreferences("settings",0).edit().clear().commit();}
    private JSONObject prose() throws Exception {return new JSONObject().put("id","paired").put("category","صمیمی").put("body","قدیمی").put("body_formal","دلم آغوش تو را می‌خواهد 🤍").put("body_colloquial","دلم بغل تو رو می‌خواد 🤍");}
    @Test public void defaultIsColloquialAndLocalSearchSeesOnlyChosenRegister() throws Exception {
        AppStore store=new AppStore(app);assertEquals("colloquial",store.settings().tone);
        store.rememberCatalog(new JSONArray().put(prose()));
        assertEquals(1,store.localCatalogPage("همه","بغل",0,"عزیزم","colloquial").size());
        assertTrue(store.localCatalogPage("همه","آغوش",0,"عزیزم","colloquial").isEmpty());
        assertEquals(1,store.localCatalogPage("همه","آغوش",0,"عزیزم","formal").size());
        assertTrue(store.localCatalogPage("همه","بغل",0,"عزیزم","formal").isEmpty());
        store.saveTone("formal");closeStore();assertEquals("formal",new AppStore(app).settings().tone);
    }
    @Test public void poetryIsIdenticalAndLegacyUntypedProseCannotLeakTheWrongTone() throws Exception {
        JSONObject poem=new JSONObject().put("id","poem").put("category","شعر").put("body","متن اصلی شاعر");
        JSONObject legacy=new JSONObject().put("id","old").put("category","صمیمی").put("body","متن قدیمی بدون دو لحن");
        JSONArray rows=new JSONArray().put(prose()).put(poem).put(legacy);
        for(String tone:new String[]{"formal","colloquial"}){JSONArray selected=ToneCatalog.select(rows,tone);assertEquals(2,selected.length());assertEquals("متن اصلی شاعر",selected.getJSONObject(1).getString("body"));}
        AppStore store=new AppStore(app);store.rememberCatalog(rows);
        assertTrue(store.localCatalogPage("همه","بدون دو لحن",0,"عزیزم","formal").isEmpty());
        assertEquals(store.localCatalogPage("شعر","",0,"عزیزم","formal"),store.localCatalogPage("شعر","",0,"عزیزم","colloquial"));
    }
    @Test public void versionThreeUpgradePreservesQueueScheduleSnapshotIdentityAndOutbox() throws Exception {
        AppStore store=new AppStore(app);Settings settings=store.settings();settings.recipient="+989121234567";settings.subscriptionId=7;
        long id=store.approve("متن تأییدشده تغییر نمی‌کند 💕",settings,"SIM");store.schedule(id,9999999999999L,"2286-11-20");Message before=store.get(id);int events=store.pendingSyncCount();closeStore();
        SQLiteDatabase old=app.openOrCreateDatabase("messages.db",0,null);old.execSQL("DROP TABLE catalog_cache");old.execSQL("CREATE TABLE catalog_cache(id TEXT PRIMARY KEY,body TEXT NOT NULL,category TEXT NOT NULL)");old.execSQL("INSERT INTO catalog_cache(id,body,category) VALUES('legacy','متن کش قدیمی','صمیمی')");old.setVersion(3);old.close();
        store=new AppStore(app);Message after=store.get(id);assertEquals(before.body,after.body);assertEquals(before.recipient,after.recipient);assertEquals(before.subscriptionId,after.subscriptionId);assertEquals(before.plannedAt,after.plannedAt);assertEquals(before.syncId,after.syncId);assertEquals(before.revision,after.revision);assertEquals(events,store.pendingSyncCount());
        store.saveTone("formal");assertEquals(before.body,store.get(id).body);assertTrue(store.localCatalogPage("همه","",0).isEmpty());
    }
    @Test public void bundledCatalogContainsBothOldAndTwoThousandNewPairs() {
        List<String> spoken=ToneCatalog.offline(app,"همه","عزیزم","colloquial"),formal=ToneCatalog.offline(app,"همه","عزیزم","formal");
        assertEquals(formal.size(),spoken.size());assertTrue(formal.size()>=3002);assertNotEquals(formal.get(0),spoken.get(0));
        assertEquals(ToneCatalog.offline(app,"شعر","عزیزم","formal"),ToneCatalog.offline(app,"شعر","عزیزم","colloquial"));
    }
}
