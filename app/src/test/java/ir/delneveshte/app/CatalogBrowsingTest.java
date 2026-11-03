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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
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
    private Context app;private AppStore store;private static CloudSync.Transport transport;
    public static class TestActivity extends MainActivity {@Override CloudSync.Transport catalogTransport(CloudConfig cloud){return transport;}}
    private static class Pending {final String path;final CompletableFuture<JSONObject> result=new CompletableFuture<>();Pending(String value){path=value;}}
    private final LinkedBlockingQueue<Pending> requests=new LinkedBlockingQueue<>();
    private final List<Pending> pending=java.util.Collections.synchronizedList(new ArrayList<>());
    @Before public void reset() throws Exception {
        app=RuntimeEnvironment.getApplication();Field f=AppStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase old=(SQLiteDatabase)f.get(null);if(old!=null&&old.isOpen())old.close();f.set(null,null);app.deleteDatabase("messages.db");
        for(String name:new String[]{"settings","cloud","catalog_metadata","catalog_format"})app.getSharedPreferences(name,0).edit().clear().commit();
        app.getSharedPreferences("catalog_format",0).edit().putInt("version",3).commit();CloudConfig cloud=new CloudConfig(app);cloud.registrationSucceeded(cloud.generation());cloud.catalogUpdated();store=new AppStore(app);
        transport=(path,body)->{Pending req=new Pending(path);pending.add(req);requests.add(req);return req.result.get(10,TimeUnit.SECONDS);};
    }
    @After public void drain() throws Exception {synchronized(pending){for(Pending req:pending)req.result.complete(page());}for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.getName().equals("delneveshte-catalog-browse"))thread.join(2000);shadowOf(Looper.getMainLooper()).idle();}
    static JSONObject row(String body) throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("body",body).put("category","شعر");}
    static JSONObject page(String... bodies) throws Exception {JSONArray rows=new JSONArray();for(String body:bodies)rows.put(row(body));return new JSONObject().put("messages",rows).put("next_cursor",JSONObject.NULL).put("has_more",false);}
    private Pending next() throws Exception {Pending req=requests.poll(5,TimeUnit.SECONDS);assertNotNull("Catalog request did not start",req);return req;}
    interface Check {boolean done() throws Exception;}
    static void await(Check check) throws Exception {for(int i=0;i<250;i++){shadowOf(Looper.getMainLooper()).idle();if(check.done())return;Thread.sleep(10);}fail("Timed out waiting for UI result");}
    static TextView text(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){TextView v=text(g.getChildAt(i),label);if(v!=null)return v;}}return null;}
    static View described(View root,String label){if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View v=described(g.getChildAt(i),label);if(v!=null)return v;}}return null;}
    static EditText search(View root){if(root instanceof EditText)return (EditText)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){EditText v=search(g.getChildAt(i));if(v!=null)return v;}}return null;}
    @SuppressWarnings("unchecked") static List<String> options(MainActivity a) throws Exception {Field f=MainActivity.class.getDeclaredField("options");f.setAccessible(true);return new ArrayList<>((List<String>)f.get(a));}
    static boolean refreshing(MainActivity a) throws Exception {Field f=MainActivity.class.getDeclaredField("refreshingSuggestions");f.setAccessible(true);return f.getBoolean(a);}
    static void typed(View root,String q){search(root).setText(q);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));}

    @Test public void changingToneDiscardsLateResponsesAndSearchUsesOnlySelectedVariant() throws Exception {
        JSONObject pair=row("قدیمی").put("category","صمیمی").put("body_formal","نقره‌رو، آغوش تو را می‌خواهم 🤍").put("body_colloquial","نقره‌رو، بغل تو رو می‌خوام 🤍");
        store.rememberCatalog(new JSONArray().put(pair));
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();Pending old=next();assertTrue(options(a).contains(pair.getString("body_colloquial")));
            text(root,"تنظیمات").performClick();root=a.getWindow().getDecorView();text(root,"رسمی").performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("formal",store.settings().tone);root=a.getWindow().getDecorView();text(root,"بانک پیام‌ها").performClick();Pending current=next();assertTrue(current.path.contains("tone=formal"));
            current.result.complete(new JSONObject().put("messages",new JSONArray().put(pair)).put("has_more",false).put("next_cursor",JSONObject.NULL));await(()->!refreshing(a));
            old.result.complete(new JSONObject().put("messages",new JSONArray().put(pair)).put("has_more",false).put("next_cursor",JSONObject.NULL));Thread.sleep(30);shadowOf(Looper.getMainLooper()).idle();
            assertEquals(java.util.Collections.singletonList(pair.getString("body_formal")),options(a));
            root=a.getWindow().getDecorView();text(root,"جست‌وجو").performClick();typed(root,"نقره‌رو");Pending search=next();assertTrue(search.path.contains("tone=formal"));await(()->!options(a).isEmpty());assertTrue(options(a).contains(pair.getString("body_formal")));assertFalse(options(a).contains(pair.getString("body_colloquial")));
        }
    }
    @Test public void pullRefreshRequestsANewFirstPageAndEndsWhenTheResponseArrives() throws Exception {
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();Pending first=next();first.result.complete(page("پیام پیش از تازه‌سازی 🤍"));await(()->!refreshing(a));
            Field f=MainActivity.class.getDeclaredField("bankRefresh");f.setAccessible(true);PullRefreshLayout pull=(PullRefreshLayout)f.get(a);assertNotNull(pull);
            pull.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null);Pending refreshed=next();assertFalse(refreshed.path.contains("cursor="));assertTrue(pull.isRefreshing());
            refreshed.result.complete(page("پیام پس از تازه‌سازی 🤍"));await(()->!refreshing(a));assertFalse(pull.isRefreshing());assertEquals(java.util.Collections.singletonList("پیام پس از تازه‌سازی 🤍"),options(a));
        }
    }
    @Test public void homeIsCompactAndOfflineLiveSearchHasClearWithoutASubmitButton() throws Exception {
        transport=(path,body)->{throw new CloudApi.Failure(0,"offline");};
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();assertEquals(20,options(a).size());assertNull(search(root));assertNull(text(root,"انتخاب تو، ارسال در زمان‌های متفاوت"));
            text(root,"جست‌وجو").performClick();assertNotNull(search(root));assertTrue(search(root).hasFocus());assertTrue(options(a).isEmpty());typed(root,"کنار");await(()->!options(a).isEmpty());assertFalse(options(a).isEmpty());for(String body:options(a))assertTrue(CatalogSearch.matches(body,"کنار"));
            described(root,"پاک‌کردن جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals("",search(root).getText().toString());assertTrue(options(a).isEmpty());
        }
    }
    @Test public void globalSearchIgnoresHomeCategoryAndPreservesMatchesWhenRemoteReturnsEmpty() throws Exception {
        String cached="عشق تو، آرامش روزهای من است 💕";store.rememberCatalog(new JSONArray().put(row(cached).put("category","شعر")));
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();next();text(root,"قدردانی").performClick();next();text(root,"جست‌وجو").performClick();assertTrue(options(a).isEmpty());
            typed(root,"عشق");Pending q=next();assertTrue(q.path.contains("category="+URLEncoder.encode("همه","UTF-8")));assertTrue(q.path.contains("q="+URLEncoder.encode("عشق","UTF-8")));await(()->options(a).contains(cached));assertNotNull(text(root,cached));
            q.result.complete(page());await(()->!refreshing(a));assertNotNull(text(root,cached));
        }
    }
    @Test public void typingIsDebouncedAndOldRepliesCannotReplaceNewQuery() throws Exception {
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();Pending initial=next();text(root,"جست‌وجو").performClick();assertNull(requests.poll());EditText input=search(root);
            input.setText("با");input.setText("باران");assertNull(requests.poll());shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));Pending old=next();
            typed(root,"دلآرامک");Pending current=next();String found="دلآرامک، کنار تو آرام می‌گیرم 💕";current.result.complete(page(found));await(()->text(root,found)!=null);
            initial.result.complete(page("پیام قبلی 💕"));old.result.complete(page("باران قدیمی 💕"));Thread.sleep(30);shadowOf(Looper.getMainLooper()).idle();
            assertEquals("دلآرامک",input.getText().toString());assertSame(input,search(root));for(String body:options(a))assertTrue(CatalogSearch.matches(body,"دلآرامک"));
        }
    }
    @Test public void remoteResultsBeyondTheCachedPageAreAppendedWithCursor() throws Exception {
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();next();text(root,"جست‌وجو").performClick();typed(root,"ابرنقره");Pending first=next();
            first.result.complete(page("ابرنقره و تو، یک خیال شیرین 💕").put("next_cursor","test-cursor").put("has_more",true));await(()->!refreshing(a));assertEquals(1,options(a).size());
            text(root,"بارگذاری پیام‌های بیشتر").performClick();Pending second=next();assertTrue(second.path.contains("cursor=test-cursor"));assertTrue(second.path.contains("q="+URLEncoder.encode("ابرنقره","UTF-8")));
            second.result.complete(page("ابرنقره و مهربانی تو 💕"));await(()->options(a).size()==2);
        }
    }
    @Test public void unusableCacheAndAnEmptyRemotePageCannotHideInitialMessages() throws Exception {
        JSONArray dirty=new JSONArray();for(String category:TextBank.categories())if(!"همه".equals(category))for(int n=0;n<25;n++)dirty.put(row(n%2==0?"Old English cache":"دوستت دارم https://example.com/old").put("category",category));store.rememberCatalog(dirty);
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();next();assertEquals(20,options(a).size());text(root,"شعر").performClick();Pending req=next();assertEquals(20,options(a).size());req.result.complete(page());await(()->!refreshing(a));assertEquals(20,options(a).size());
        }
    }
    @Test public void validLocalRowsAreFilteredBeforePaginationAndPersianSpellingIsNormalized() throws Exception {
        JSONArray rows=new JSONArray();for(int n=0;n<30;n++)rows.put(row("Old English "+n).put("id",String.format("a%03d",n)));for(int n=0;n<45;n++)rows.put(row("جانم، دِل‌تنگ توام\nدوستت دارم و كنارت آرامم "+n+" 💕").put("id",String.format("b%03d",n)));store.rememberCatalog(rows);
        for(String q:new String[]{"دلتنگ","دل تنگ","دوستتدارم","توام دوستت","کنارت"})assertEquals(21,store.localCatalogPage("همه",q,0).size());
        List<String> first=store.localCatalogPage("همه","",0),second=store.localCatalogPage("همه","",20);assertTrue(java.util.Collections.disjoint(first.subList(0,20),second.subList(0,20)));assertEquals(5,store.localCatalogPage("همه","",40).size());assertTrue(store.localCatalogPage("همه","%",0).isEmpty());assertTrue(store.localCatalogPage("همه","_",0).isEmpty());
    }
    @Test public void knownRegistrationIsReusedAndServerResetRetriesOnce() throws Exception {
        CloudConfig config=new CloudConfig(app);List<String> calls=new ArrayList<>();CatalogClient client=new CatalogClient((path,payload)->{calls.add(path);if(calls.size()==1)throw new CloudApi.Failure(401,"reset");return new JSONObject().put("messages",new JSONArray());},config);client.browse("همه","عشق",null);assertEquals(3,calls.size());assertEquals("/api/v1/installations/register",calls.get(1));calls.clear();new CatalogClient((path,payload)->{calls.add(path);return new JSONObject();},config).browse("همه","",null);assertEquals(1,calls.size());
    }

    private boolean hasCounter(View view){
        if(view instanceof TextView&&((TextView)view).getText().toString().startsWith("پیام‌ها:"))return true;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(hasCounter(g.getChildAt(i)))return true;}return false;
    }
    @Test public void bothSearchEntryPointsFocusAnEmptyFieldWithoutSendingAnEmptyQuery() throws Exception {
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();next();assertFalse(hasCounter(root));
            described(root,"جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idle();root=a.getWindow().getDecorView();assertTrue(search(root).hasFocus());assertTrue(options(a).isEmpty());assertNull(requests.poll());assertFalse(hasCounter(root));assertNull(text(root,"پیامی پیدا نشد."));assertNull(text(root,"بارگذاری پیام‌های بیشتر"));
            typed(root,"   ");assertTrue(options(a).isEmpty());assertNull(requests.poll());described(root,"پاک‌کردن جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(options(a).isEmpty());
            described(root,"بازگشت به پیشنهادها").performClick();shadowOf(Looper.getMainLooper()).idle();root=a.getWindow().getDecorView();next();assertFalse(options(a).isEmpty());assertFalse(hasCounter(root));text(root,"جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idle();root=a.getWindow().getDecorView();assertTrue(search(root).hasFocus());assertEquals("",search(root).getText().toString());assertTrue(options(a).isEmpty());assertNull(requests.poll());assertFalse(hasCounter(root));
        }
    }
    @Test public void clearingWhileAReplyIsPendingCannotRestoreResultsOrPagination() throws Exception {
        store.rememberCatalog(new JSONArray().put(row("عشق تو، روشنی روزهای من است 💕")));
        try(ActivityController<TestActivity> c=Robolectric.buildActivity(TestActivity.class).setup()){
            MainActivity a=c.get();View root=a.getWindow().getDecorView();Pending home=next();text(root,"جست‌وجو").performClick();root=a.getWindow().getDecorView();typed(root,"عشق");Pending old=next();await(()->!options(a).isEmpty());assertFalse(options(a).isEmpty());assertFalse(hasCounter(root));
            described(root,"پاک‌کردن جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));assertEquals("",search(root).getText().toString());assertTrue(options(a).isEmpty());assertFalse(refreshing(a));assertNull(requests.poll());
            home.result.complete(page("پیام قدیمی خانه 💕"));old.result.complete(page("عشق تو ماندنی است 💕").put("next_cursor","old-cursor").put("has_more",true));
            for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.getName().equals("delneveshte-catalog-browse"))thread.join(2000);shadowOf(Looper.getMainLooper()).idle();
            assertTrue(options(a).isEmpty());assertFalse(refreshing(a));assertNull(text(root,"بارگذاری پیام‌های بیشتر"));assertNull(text(root,"پیامی پیدا نشد."));assertFalse(hasCounter(root));assertTrue(search(root).hasFocus());
        }
    }
}
