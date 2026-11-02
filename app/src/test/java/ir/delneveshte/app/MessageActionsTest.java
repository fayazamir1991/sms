package ir.delneveshte.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder;
import org.robolectric.shadows.ShadowToast;
import java.lang.reflect.Field;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MessageActionsTest {
    private AppStore store;
    private Settings settings;

    @Before public void setUp() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Field field = AppStore.class.getDeclaredField("db");
        field.setAccessible(true);
        SQLiteDatabase old = (SQLiteDatabase) field.get(null);
        if (old != null && old.isOpen()) old.close();
        field.set(null, null);
        app.deleteDatabase("messages.db");
        app.getSharedPreferences("settings", 0).edit().clear().commit();
        app.getSharedPreferences("catalog_format",0).edit().putInt("version",3).commit();
        app.getSharedPreferences("cloud", 0).edit().clear().putBoolean("enabled",false).putLong("last_catalog",System.currentTimeMillis()).commit();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS);
        shadowOf(app.getPackageManager()).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true);
        SubscriptionManager manager = app.getSystemService(SubscriptionManager.class);
        shadowOf(manager).setActiveSubscriptionInfoList(Collections.singletonList(
                SubscriptionInfoBuilder.newBuilder().setId(7).setSimSlotIndex(0)
                        .setDisplayName("SIM 1").buildSubscriptionInfo()));
        store = new AppStore(app);
        settings = new Settings();
        settings.recipient = "+989121234567";
        settings.subscriptionId = 7;
        store.saveSettings(settings);
    }

    private View root(MainActivity a){return a.getWindow().getDecorView();}
    private void menu(MainActivity a,int action) throws Exception {
        String body=CatalogBrowsingTest.options(a).get(0);CatalogBrowsingTest.text(root(a),body).performClick();
        assertTrue(org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(action,0));
        shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void compactCardAddsRandomWithOneTapAndPreservesTheApprovedSnapshot() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity a=c.get();View action=CatalogBrowsingTest.described(root(a),"ارسال در زمان تصادفی");assertNotNull(action);action.performClick();
            assertEquals(1,store.queue().size());assertNull(ShadowAlertDialog.getLatestAlertDialog());assertEquals("RANDOM",store.queue().get(0).sendMode);
            assertFalse(CatalogBrowsingTest.described(root(a),"ارسال در زمان تصادفی").isEnabled());
            Settings changed=settings.copy();changed.recipient="+989351234567";changed.subscriptionId=8;store.saveSettings(changed);assertEquals(settings.recipient,store.queue().get(0).recipient);assertEquals(7,store.queue().get(0).subscriptionId);
        }
    }
    @Test public void editingOffersAllThreeModesAndRandomQueuesTheExactEditedText() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            menu(c.get(),4);AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();EditText input=CatalogBrowsingTest.search(editor.getWindow().getDecorView());input.setText("دلم یک چای دونفره می‌خواهد ☕💕");
            for(String label:new String[]{"ارسال همین حالا","ارسال در زمان تصادفی","ارسال در زمان دلخواه"})assertNotNull(CatalogBrowsingTest.text(editor.getWindow().getDecorView(),label));
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان تصادفی").performClick();assertFalse(editor.isShowing());assertEquals(1,store.queue().size());assertEquals("دلم یک چای دونفره می‌خواهد ☕💕",store.queue().get(0).body);
        }
    }
    @Test public void editingCanChooseAFixedTimeAndCancelCreatesNoQueueEntry() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            menu(c.get(),4);AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();CatalogBrowsingTest.search(editor.getWindow().getDecorView()).setText("قرار عاشقانهٔ ما 💕");
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان دلخواه").performClick();AlertDialog schedule=ShadowAlertDialog.getLatestAlertDialog();assertTrue(store.queue().isEmpty());schedule.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(store.queue().isEmpty());assertTrue(editor.isShowing());
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان دلخواه").performClick();schedule=ShadowAlertDialog.getLatestAlertDialog();schedule.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Message m=store.queue().get(0);assertEquals("FIXED",m.sendMode);assertTrue(m.requestedAt>System.currentTimeMillis());assertEquals("قرار عاشقانهٔ ما 💕",m.body);assertEquals(settings.recipient,m.recipient);assertFalse(editor.isShowing());
        }
    }
    @Test public void immediateCancellationCreatesNothingAndConfirmationUsesThePreviewedRecipient() throws Exception {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()){
            menu(c.get(),1);AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();assertTrue(store.queue().isEmpty());confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(store.history().isEmpty());
            menu(c.get(),1);confirmation=ShadowAlertDialog.getLatestAlertDialog();Settings changed=settings.copy();changed.recipient="+989351234567";store.saveSettings(changed);confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(store.queue().isEmpty());assertEquals(1,store.history().size());Message m=store.history().get(0);assertEquals(settings.recipient,m.recipient);assertEquals("SENDING",m.status);assertEquals("IMMEDIATE",m.sendMode);
        }
    }
    @Test public void immediateIntentCannotBecomeAnAutomaticQueuedSend(){
        long id=store.approveImmediate("دوستت دارم ❤️",settings,"SIM 1");assertTrue(store.queue().isEmpty());assertTrue(store.unscheduled().isEmpty());assertFalse(store.schedule(id,System.currentTimeMillis(),"2026-11-01"));store.requeueFuture();assertTrue(store.queue().isEmpty());store.recoverUncertain(System.currentTimeMillis()+16*60_000);assertEquals("FAILED",store.get(id).status);assertFalse(store.claim(id,System.currentTimeMillis(),1));
    }
    @Test public void multipartSendIsClaimedOnceAndCallbacksAreDeduplicated(){
        long id=store.approveImmediate("دلتنگتم 🫂❤️",settings,"SIM 1");assertTrue(store.claim(id,System.currentTimeMillis(),2));assertFalse(store.claim(id,System.currentTimeMillis(),2));store.recordPart(id,0,true,"");store.recordPart(id,0,true,"");assertEquals("SENDING",store.get(id).status);store.recordPart(id,1,true,"");assertEquals("SENT",store.get(id).status);
    }
    @Test public void fixedTimeSurvivesPauseAndCannotBeRandomlyRescheduled() throws Exception {
        long at=System.currentTimeMillis()+3*3600_000;long id=store.approveFixed("دیدار ما 💕",settings,"SIM",at);String day=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();assertTrue(store.schedule(id,at,day));assertFalse(store.schedule(id,at+1000,day));
        store.requeueRandom();assertEquals("SCHEDULED",store.get(id).status);SmsRuntime.pause(RuntimeEnvironment.getApplication());Message m=store.get(id);assertEquals("APPROVED",m.status);assertEquals(at,m.requestedAt);assertEquals(at,m.plannedAt);assertTrue(store.unscheduled().isEmpty());
        settings.enabled=true;store.saveSettings(settings);SmsRuntime.reconcile(RuntimeEnvironment.getApplication());assertEquals(at,store.get(id).plannedAt);assertEquals("SCHEDULED",store.get(id).status);
        long random=store.approve("عشق ما 💕",settings,"SIM");SmsRuntime.reconcile(RuntimeEnvironment.getApplication());assertEquals("RANDOM",store.get(random).sendMode);assertEquals(at,store.get(id).plannedAt);
    }
    @Test public void overduePausedFixedMessageFailsInsteadOfEnteringRandomQueue() throws Exception {
        long id=store.approveFixed("همیشه در یادم 💕",settings,"SIM",System.currentTimeMillis()+60_000);Field field=AppStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(null);db.execSQL("UPDATE messages SET requestedAt=?,plannedAt=? WHERE id=?",new Object[]{System.currentTimeMillis()-3600_000,System.currentTimeMillis()-3600_000,id});
        settings.enabled=true;store.saveSettings(settings);SmsRuntime.reconcile(RuntimeEnvironment.getApplication());assertEquals("FAILED",store.get(id).status);assertTrue(store.unscheduled().isEmpty());
    }
    @Test public void fixedTimeIsIndependentOfRandomWindowAndDailyQuota() throws Exception {
        settings.enabled=true;settings.startMinute=1320;settings.endMinute=1380;store.saveSettings(settings);
        long at=java.time.LocalDate.now().plusDays(2).atTime(7,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();long id=store.approveFixed("قرار صبحگاهی ما 💕",settings,"SIM",at);String day=java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();store.schedule(id,at,day);
        java.lang.reflect.Method eligible=SmsRuntime.class.getDeclaredMethod("eligibleScheduled",AppStore.class,Message.class,long.class);eligible.setAccessible(true);assertEquals(true,eligible.invoke(null,store,store.get(id),at+1000));assertEquals(false,eligible.invoke(null,store,store.get(id),at-1));assertEquals(false,eligible.invoke(null,store,store.get(id),at+SmsRuntime.FIXED_GRACE+1));
        store.claim(id,at+1000,1);assertEquals(0,store.attemptedOn(day));
    }

}
