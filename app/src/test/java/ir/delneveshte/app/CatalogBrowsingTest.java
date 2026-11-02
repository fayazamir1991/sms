package ir.delneveshte.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class CatalogBrowsingTest {
    private Context app;
    private AppStore store;
    private static CloudSync.Transport transport;
    public static class TestActivity extends MainActivity {
        @Override CloudSync.Transport catalogTransport(CloudConfig config) { return transport; }
    }
    private static class Pending {
        final String path;
        final CompletableFuture<JSONObject> result=new CompletableFuture<>();
        Pending(String value) { path=value; }
    }
    private final LinkedBlockingQueue<Pending> requests=new LinkedBlockingQueue<>();
    @Before public void reset() throws Exception {
        app=RuntimeEnvironment.getApplication();
        Field f=AppStore.class.getDeclaredField("db");f.setAccessible(true);
        SQLiteDatabase old=(SQLiteDatabase)f.get(null);if(old!=null&&old.isOpen())old.close();f.set(null,null);
        app.deleteDatabase("messages.db");
        for(String name:new String[]{"settings","cloud","catalog_metadata","catalog_format"})app.getSharedPreferences(name,0).edit().clear().commit();
        app.getSharedPreferences("catalog_format",0).edit().putInt("version",3).commit();
        CloudConfig cloud=new CloudConfig(app);cloud.configure("https://example.test",true);cloud.registrationSucceeded(cloud.generation());cloud.catalogUpdated();
        store=new AppStore(app);
        transport=(path,body)->{Pending request=new Pending(path);requests.add(request);return request.result.get(5,TimeUnit.SECONDS);};
    }
    private static JSONObject row(String body) throws Exception { return new JSONObject().put("id",UUID.randomUUID().toString()).put("body",body).put("category","شعر"); }
    private static JSONObject page(String body) throws Exception { return new JSONObject().put("messages",new JSONArray().put(row(body))).put("next_cursor",JSONObject.NULL).put("has_more",false); }
    private Pending next() throws Exception { Pending p=requests.poll(5,TimeUnit.SECONDS);assertNotNull("Catalog request did not start",p);return p; }
    private interface Check { boolean done(); }
    private static void await(Check check) throws Exception {
        for(int i=0;i<250;i++){shadowOf(Looper.getMainLooper()).idle();if(check.done())return;Thread.sleep(10);}
        fail("Timed out waiting for UI result");
    }
    private static TextView text(View root,String label) {
        if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private static EditText search(View root) {
        if(root instanceof EditText)return (EditText)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){EditText found=search(group.getChildAt(i));if(found!=null)return found;}}return null;
    }
    @SuppressWarnings("unchecked") private static List<String> options(MainActivity activity) throws Exception { Field f=MainActivity.class.getDeclaredField("options");f.setAccessible(true);return new ArrayList<>((List<String>)f.get(activity)); }

    @Test public void cachedMessagesStayVisibleWhileLoadingAndTypedSearchSurvivesResponse() throws Exception {
        String cached="دلم با دیدن تو آرام می‌گیرد 💕",remote="دوستت دارم، همین حالا و همیشه 💕";
        store.rememberCatalog(new JSONArray().put(row(cached)));
        try(ActivityController<TestActivity> controller=Robolectric.buildActivity(TestActivity.class).setup()){
            TestActivity activity=controller.get();View root=activity.getWindow().getDecorView();Pending first=next();
            assertNotNull(text(root,cached));
            EditText input=search(root);input.setText("دلتنگ");input.requestFocus();
            first.result.complete(page(remote));await(()->text(root,remote)!=null);
            assertSame(input,search(root));assertEquals("دلتنگ",input.getText().toString());assertTrue(input.hasFocus());
            text(root,"جست‌وجو").performClick();Pending query=next();assertTrue(query.path.contains("q="+URLEncoder.encode("دلتنگ","UTF-8")));
            String found="دلتنگ توام، به اندازهٔ تمام لحظه‌ها 💕";query.result.complete(page(found));await(()->text(root,found)!=null);
            assertEquals(1,options(activity).size());
        }
    }
    @Test public void initialBundledMessagesRemainVisibleEvenWithAnEmptyCachedSnapshot() throws Exception {
        store.replaceCatalog("شعر",new JSONArray());new CloudConfig(app).configure("https://example.test",false);
        try(ActivityController<TestActivity> controller=Robolectric.buildActivity(TestActivity.class).setup()){
            TestActivity activity=controller.get();assertFalse(options(activity).isEmpty());
            View root=activity.getWindow().getDecorView();search(root).setText("كنار");text(root,"جست‌وجو").performClick();
            assertFalse(options(activity).isEmpty());for(String body:options(activity))assertTrue(CatalogSearch.matches(body,"کنار"));
        }
    }
    @Test public void lateResponseCannotReplaceTheNewerSearch() throws Exception {
        try(ActivityController<TestActivity> controller=Robolectric.buildActivity(TestActivity.class).setup()){
            TestActivity activity=controller.get();View root=activity.getWindow().getDecorView();Pending old=next();
            search(root).setText("آرام");text(root,"جست‌وجو").performClick();Pending current=next();
            String found="کنار تو آرامم، عزیزترینم 💕";current.result.complete(page(found));await(()->text(root,found)!=null);
            old.result.complete(page("پاسخ قدیمی نباید نمایش داده شود 💕"));
            Thread.sleep(30);shadowOf(Looper.getMainLooper()).idle();assertEquals(java.util.Collections.singletonList(found),options(activity));
        }
    }
    @Test public void localSubstringSearchNormalizesPersianAndDoesNotInterpretWildcards() throws Exception {
        String body="جانم، دِل‌تنگ توام\nدوستت دارم و كنارت آرامم 💕";
        store.rememberCatalog(new JSONArray().put(row(body)));
        for(String q:new String[]{"دلتنگ","دل تنگ","دوستتدارم","توام دوستت","کنارت"})assertEquals(q,java.util.Collections.singletonList(body),store.localCatalogPage("همه",q,0));
        assertTrue(store.localCatalogPage("همه","%",0).isEmpty());assertTrue(store.localCatalogPage("همه","_",0).isEmpty());
        assertTrue(store.localCatalogPage("قدردانی","دلتنگ",0).isEmpty());
    }
    @Test public void unusableCachedRowsCannotHideBundledStartupOrCategoryMessages() throws Exception {
        JSONArray dirty=new JSONArray();
        for(String category:TextBank.categories())if(!"همه".equals(category)){
            for(int n=0;n<25;n++)dirty.put(row(n%2==0?"An old English cached message":"دوستت دارم https://example.com/old").put("category",category));
        }
        store.rememberCatalog(dirty);
        try(ActivityController<TestActivity> controller=Robolectric.buildActivity(TestActivity.class).setup()){
            TestActivity activity=controller.get();View root=activity.getWindow().getDecorView();Pending initial=next();
            assertEquals(20,options(activity).size());assertNotNull(text(root,"پیام‌ها: ۲۰"));
            text(root,"شعر").performClick();Pending poetry=next();
            assertEquals(20,options(activity).size());assertNotNull(text(root,"پیام‌ها: ۲۰"));
            for(String body:options(activity))assertNotNull(CatalogText.render(body,"عزیزم"));
            poetry.result.complete(new JSONObject().put("messages",new JSONArray()).put("next_cursor",JSONObject.NULL).put("has_more",false));
            await(()->text(root,"پیام‌های روی گوشی · شامل بانک آماده و پیام‌های ذخیره‌شده")!=null);
            assertEquals(20,options(activity).size());
            initial.result.complete(page("پاسخ قبلی 💕"));
        }
    }
    @Test public void invalidRowsAreSkippedBeforeLocalPageOffsetAndGoodRowsStillFillThePage() throws Exception {
        JSONArray rows=new JSONArray();
        for(int n=0;n<30;n++)rows.put(row("Old English cache "+n).put("id",String.format("a%03d",n)));
        for(int n=0;n<45;n++)rows.put(row("دلم کنار تو آرام است "+n+" 💕").put("id",String.format("b%03d",n)));
        store.rememberCatalog(rows);
        List<String> first=store.localCatalogPage("همه","",0),second=store.localCatalogPage("همه","",20);
        assertEquals(21,first.size());assertEquals(21,second.size());
        assertTrue(java.util.Collections.disjoint(first.subList(0,20),second.subList(0,20)));
        assertEquals(5,store.localCatalogPage("همه","",40).size());
    }
    @Test public void anEmptyServerPageDoesNotClearInitialBundledMessages() throws Exception {
        try(ActivityController<TestActivity> controller=Robolectric.buildActivity(TestActivity.class).setup()){
            TestActivity activity=controller.get();View root=activity.getWindow().getDecorView();Pending request=next();
            assertEquals(20,options(activity).size());
            request.result.complete(new JSONObject().put("messages",new JSONArray()).put("next_cursor",JSONObject.NULL).put("has_more",false));
            await(()->text(root,"پیام‌های روی گوشی · شامل بانک آماده و پیام‌های ذخیره‌شده")!=null);
            assertEquals(20,options(activity).size());assertNotNull(text(root,"پیام‌ها: ۲۰"));
        }
    }
    @Test public void knownRegistrationIsReusedAndServerResetRetriesOnce() throws Exception {
        CloudConfig config=new CloudConfig(app);List<String> calls=new ArrayList<>();
        CatalogClient client=new CatalogClient((path,payload)->{calls.add(path);if(calls.size()==1)throw new CloudApi.Failure(401,"reset");return new JSONObject().put("messages",new JSONArray());},config);
        client.browse("همه","آرام",null);assertEquals(3,calls.size());assertEquals("/api/v1/installations/register",calls.get(1));
        calls.clear();new CatalogClient((path,payload)->{calls.add(path);return new JSONObject();},config).browse("همه","",null);
        assertEquals(1,calls.size());assertTrue(calls.get(0).startsWith("/api/v1/messages/browse"));
    }
}
