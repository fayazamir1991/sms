package ir.delneveshte.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
public class SearchPerformanceTest {
    private Context app;private AppStore store;
    @Before public void reset() throws Exception{app=RuntimeEnvironment.getApplication();Field f=AppStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)f.get(null);if(db!=null&&db.isOpen())db.close();f.set(null,null);app.deleteDatabase("messages.db");for(String name:new String[]{"settings","cloud","catalog_format"})app.getSharedPreferences(name,0).edit().clear().commit();store=new AppStore(app);}
    private JSONArray rows(int count) throws Exception{JSONArray rows=new JSONArray();for(int i=0;i<count;i++)rows.put(new JSONObject().put("id",String.format("%04d",i)).put("body","مهتابک عشق آزمایشی "+i+" 🤍").put("category","شعر"));return rows;}
    @Test public void localPagesAreTwentyAtATimeAndDoNotDuplicateCacheAndBundle() throws Exception{
        store.rememberCatalog(rows(65));LocalSearchPager pager=new LocalSearchPager(app,store,"مهتابک","colloquial");List<String> all=new ArrayList<>();
        for(int expected:new int[]{20,20,20,5}){LocalSearchPager.Page page=pager.next(()->false);assertEquals(expected,page.rows.size());all.addAll(page.rows);assertEquals(expected==20,page.more);}
        assertEquals(65,new java.util.HashSet<>(all).size());
    }
    @Test public void typingAndClearDoNotWaitForAnOccupiedDatabaseLock() throws Exception{
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(a.getWindow().getDecorView(),"جست‌وجو").performClick();
            Field lockField=AppStore.class.getDeclaredField("LOCK");lockField.setAccessible(true);Object databaseLock=lockField.get(null);
            java.util.concurrent.atomic.AtomicBoolean timedOut=new java.util.concurrent.atomic.AtomicBoolean();
            CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);Thread holder=new Thread(()->{synchronized(databaseLock){locked.countDown();try{if(!release.await(2,TimeUnit.SECONDS))timedOut.set(true);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}});holder.start();assertTrue(locked.await(1,TimeUnit.SECONDS));
            try{
                CatalogBrowsingTest.search(a.getWindow().getDecorView()).setText("عشق مهتاب");assertEquals("عشق مهتاب",CatalogBrowsingTest.search(a.getWindow().getDecorView()).getText().toString());
                assertFalse("Typing waited for the database lock",timedOut.get());assertEquals("UI typing must finish before the DB lock is released",1,release.getCount());
                CatalogBrowsingTest.described(a.getWindow().getDecorView(),"پاک‌کردن جست‌وجو").performClick();assertTrue(CatalogBrowsingTest.options(a).isEmpty());
            }finally{release.countDown();holder.join(2000);}
        }
    }
    @Test public void oneLetterAndWhitespaceNeverStartLocalOrRemoteSearch() throws Exception{
        AtomicInteger calls=new AtomicInteger();try(SearchHarness h=new SearchHarness(calls)){
            for(String q:new String[]{""," ","ع","ع‌","َع "}){h.controller.start(q,"colloquial");shadowOf(Looper.getMainLooper()).idle();assertTrue(h.rows.isEmpty());assertFalse(h.loading);}
            assertEquals(0,calls.get());assertFalse(CatalogSearch.canSearch("ع"));assertTrue(CatalogSearch.canSearch("عش"));
        }
    }
    @Test public void controllerDoesNotFetchAllRemotePagesUntilMoreIsRequested() throws Exception{
        AtomicInteger calls=new AtomicInteger();try(SearchHarness h=new SearchHarness(calls)){
            h.controller.start("مهتابک","colloquial");CatalogBrowsingTest.await(()->!h.loading&&h.rows.size()==20);assertEquals(1,calls.get());assertTrue(h.more);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,calls.get());assertEquals(20,h.rows.size());
            h.controller.more();CatalogBrowsingTest.await(()->h.rows.size()==40&&!h.loading);assertEquals(2,calls.get());
        }
    }
    private final class SearchHarness implements AutoCloseable {
        final SearchController controller;List<String> rows=new ArrayList<>();boolean more,loading;
        SearchHarness(AtomicInteger calls){CloudConfig cloud=new CloudConfig(app);cloud.registrationSucceeded(cloud.generation());controller=new SearchController(app,store,ignored->(path,body)->{
            assertNotEquals("HTTP must run off the UI thread",Looper.getMainLooper(),Looper.myLooper());int page=calls.getAndIncrement();JSONArray result=new JSONArray();for(int i=0;i<20;i++)result.put(new JSONObject().put("id",page+"-"+i).put("category","شعر").put("body","مهتابک پیام جست‌وجوی تازه "+page+" "+i+" 🤍"));return new JSONObject().put("messages",result).put("has_more",true).put("next_cursor","page-"+page);
        },(r,m,l,e)->{rows=r;more=m;loading=l;});}
        public void close(){controller.close();}
    }
}
