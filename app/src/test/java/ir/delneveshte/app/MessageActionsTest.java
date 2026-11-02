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
    public static class OfflineActivity extends MainActivity {
        @Override CloudSync.Transport catalogTransport(CloudConfig cloud){return (path,body)->{throw new CloudApi.Failure(0,"offline");};}
    }
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
        app.getSharedPreferences("cloud", 0).edit().clear().putLong("last_catalog",System.currentTimeMillis()).commit();
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
        String body=CatalogBrowsingTest.options(a).get(0);CatalogBrowsingTest.text(root(a),body).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertTrue(org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(action,0));
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void queueAction(MainActivity a,String text,int action){
        CatalogBrowsingTest.text(root(a),text).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu().getMenu().performIdentifierAction(action,0));shadowOf(Looper.getMainLooper()).idle();
    }
    private int actionCount(View view,Class<?> kind){int n=kind.isInstance(view)?1:0;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)n+=actionCount(g.getChildAt(i),kind);}return n;}
    @Test public void compactCardAddsRandomWithOneTapAndPreservesTheApprovedSnapshot() throws Exception {
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();View action=CatalogBrowsingTest.described(root(a),"ارسال در زمان تصادفی");assertNotNull(action);action.performClick();shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,store.queue().size());assertNull(ShadowAlertDialog.getLatestAlertDialog());assertEquals("RANDOM",store.queue().get(0).sendMode);
            assertFalse(CatalogBrowsingTest.described(root(a),"ارسال در زمان تصادفی").isEnabled());
            Settings changed=settings.copy();changed.recipient="+989351234567";changed.subscriptionId=8;store.saveSettings(changed);assertEquals(settings.recipient,store.queue().get(0).recipient);assertEquals(7,store.queue().get(0).subscriptionId);
        }
    }
    @Test public void editingOffersAllThreeModesAndRandomQueuesTheExactEditedText() throws Exception {
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            menu(c.get(),4);AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();EditText input=CatalogBrowsingTest.search(editor.getWindow().getDecorView());input.setText("دلم یک چای دونفره می‌خواهد ☕💕");
            for(String label:new String[]{"ارسال همین حالا","ارسال در زمان تصادفی","ارسال در زمان دلخواه"})assertNotNull(CatalogBrowsingTest.text(editor.getWindow().getDecorView(),label));
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان تصادفی").performClick();shadowOf(Looper.getMainLooper()).idle();assertFalse(editor.isShowing());assertEquals(1,store.queue().size());assertEquals("دلم یک چای دونفره می‌خواهد ☕💕",store.queue().get(0).body);
        }
    }
    @Test public void editingCanChooseAFixedTimeAndCancelCreatesNoQueueEntry() throws Exception {
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            menu(c.get(),4);AlertDialog editor=ShadowAlertDialog.getLatestAlertDialog();CatalogBrowsingTest.search(editor.getWindow().getDecorView()).setText("قرار عاشقانهٔ ما 💕");
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان دلخواه").performClick();shadowOf(Looper.getMainLooper()).idle();AlertDialog schedule=ShadowAlertDialog.getLatestAlertDialog();assertTrue(store.queue().isEmpty());schedule.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(store.queue().isEmpty());assertTrue(editor.isShowing());
            CatalogBrowsingTest.text(editor.getWindow().getDecorView(),"ارسال در زمان دلخواه").performClick();shadowOf(Looper.getMainLooper()).idle();schedule=ShadowAlertDialog.getLatestAlertDialog();schedule.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
            Message m=store.queue().get(0);assertEquals("FIXED",m.sendMode);assertTrue(m.requestedAt>System.currentTimeMillis());assertEquals("قرار عاشقانهٔ ما 💕",m.body);assertEquals(settings.recipient,m.recipient);assertFalse(editor.isShowing());
        }
    }
    @Test public void immediateCancellationCreatesNothingAndConfirmationUsesThePreviewedRecipient() throws Exception {
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            menu(c.get(),1);AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();assertTrue(store.queue().isEmpty());confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(store.history().isEmpty());
            menu(c.get(),1);confirmation=ShadowAlertDialog.getLatestAlertDialog();Settings changed=settings.copy();changed.recipient="+989351234567";store.saveSettings(changed);confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
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


    @Test public void everyRandomApprovalHasATimeWhilePausedAndOverflowUsesFutureDays() {
        settings.dailyCount=2;settings.startMinute=0;settings.endMinute=1440;store.saveSettings(settings);
        long now=System.currentTimeMillis();java.util.Map<String,Integer> days=new java.util.HashMap<>();
        for(int n=0;n<7;n++){
            Message m=store.get(store.approve("عشق من "+n,settings,"SIM"));assertTrue(m.plannedAt>now);assertEquals("APPROVED",m.status);
            String day=java.time.Instant.ofEpochMilli(m.plannedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();days.put(day,days.getOrDefault(day,0)+1);
            assertEquals(day,m.planDay);
        }
        assertTrue(days.size()>=4);for(int count:days.values())assertTrue(count<=2);
        java.util.Map<Long,Long> before=new java.util.HashMap<>();for(Message m:store.queue())before.put(m.id,m.plannedAt);
        SmsRuntime.reconcile(RuntimeEnvironment.getApplication());SmsRuntime.pause(RuntimeEnvironment.getApplication());
        for(Message m:store.queue())assertEquals((long)before.get(m.id),m.plannedAt);
        store.approve("پیام تازه",settings,"SIM");for(Message m:store.queue())if(before.containsKey(m.id))assertEquals((long)before.get(m.id),m.plannedAt);
    }
    @Test public void attemptedRandomSlotsCountTowardFutureReservationsButFixedMessagesDoNot(){
        settings.dailyCount=1;settings.startMinute=0;settings.endMinute=1440;store.saveSettings(settings);
        long first=store.approve("پیام اول",settings,"SIM");Message m=store.get(first);store.schedule(first,m.plannedAt,m.planDay);assertTrue(store.claim(first,m.plannedAt,1));
        store.approveFixed("قرار دلخواه",settings,"SIM",m.plannedAt+1000);
        Message next=store.get(store.approve("پیام بعدی",settings,"SIM"));assertTrue(next.planDay.compareTo(m.planDay)>0);
    }
    @Test public void queueTimeEditIsAtomicAndPreservesSnapshotAndSyncIdentity() throws Exception {
        long id=store.approve("همان متن تأییدشده ❤️",settings,"SIM 1");Message old=store.get(id);long at=System.currentTimeMillis()+4*3600_000;
        Settings changed=settings.copy();changed.recipient="+989351234567";changed.subscriptionId=8;store.saveSettings(changed);
        assertTrue(SmsRuntime.rescheduleQueued(RuntimeEnvironment.getApplication(),id,old.revision,at));Message edited=store.get(id);
        assertEquals("FIXED",edited.sendMode);assertEquals(at,edited.plannedAt);assertEquals(at,edited.requestedAt);assertEquals(old.body,edited.body);assertEquals(old.recipient,edited.recipient);assertEquals(old.subscriptionId,edited.subscriptionId);assertEquals(old.syncId,edited.syncId);assertEquals(old.approvedAt,edited.approvedAt);assertEquals(old.revision+1,edited.revision);
        org.json.JSONObject payload=store.pendingSync(10).getJSONObject(0);assertEquals(at,payload.getLong("planned_at"));assertEquals(at,payload.getLong("requested_at"));
        assertFalse(store.rescheduleFixed(id,old.revision,at+1000));assertEquals(at,store.get(id).plannedAt);
        assertTrue(store.cancel(id));assertFalse(store.rescheduleFixed(id,store.get(id).revision,at+1000));
        long sent=store.approve("در حال ارسال",settings,"SIM");Message sending=store.get(sent);store.schedule(sent,sending.plannedAt,sending.planDay);store.claim(sent,System.currentTimeMillis(),1);assertFalse(store.rescheduleFixed(sent,store.get(sent).revision,at));
    }
    @Test public void legacyUnassignedAndExpiredRandomRowsGetTimesWithoutMovingValidFutureRows() throws Exception {
        settings.startMinute=0;settings.endMinute=1440;store.saveSettings(settings);
        long valid=store.approve("زمان معتبر",settings,"SIM"), legacy=store.approve("قدیمی",settings,"SIM"), expired=store.approve("عقب افتاده",settings,"SIM");long keep=store.get(valid).plannedAt;
        Field field=AppStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(null);
        db.execSQL("UPDATE messages SET plannedAt=0,planDay='' WHERE id=?",new Object[]{legacy});db.execSQL("UPDATE messages SET plannedAt=? WHERE id=?",new Object[]{System.currentTimeMillis()-1000,expired});
        store.ensureRandomSchedules(System.currentTimeMillis());assertEquals(keep,store.get(valid).plannedAt);assertTrue(store.get(legacy).plannedAt>System.currentTimeMillis());assertTrue(store.get(expired).plannedAt>System.currentTimeMillis());
        long revision=store.get(legacy).revision;store.ensureRandomSchedules(System.currentTimeMillis());assertEquals(revision,store.get(legacy).revision);
    }
    @Test public void queueEditorCancelChangesNothingAndSavingChangesTheSameMessage() throws Exception {
        settings.startMinute=0;settings.endMinute=1440;store.saveSettings(settings);long id=store.approve("پیام صف ❤️",settings,"SIM");
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();Message before=store.get(id);
            queueAction(a,"پیام صف ❤️",1);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(before.revision,store.get(id).revision);
            queueAction(a,"پیام صف ❤️",1);dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,store.queue().size());assertEquals(id,store.queue().get(0).id);assertEquals("FIXED",store.get(id).sendMode);assertEquals(before.plannedAt,store.get(id).requestedAt);
        }
    }

    @Test public void queueCardTapAndLongPressOpenActionsWithConfirmationForSendingAndRemoval() throws Exception {
        String text="پیام صف برای منو ❤️";long id=store.approve(text,settings,"SIM");
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();
            View body=CatalogBrowsingTest.text(root(a),text);assertTrue(body.performLongClick());shadowOf(Looper.getMainLooper()).idle();
            android.widget.PopupMenu menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();assertEquals(5,menu.getMenu().size());
            assertTrue(menu.getMenu().performIdentifierAction(4,0));assertEquals(text,((android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
            body.performClick();shadowOf(Looper.getMainLooper()).idle();menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();assertTrue(menu.getMenu().performIdentifierAction(2,0));shadowOf(Looper.getMainLooper()).idle();
            AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();assertTrue(confirm.isShowing());confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(store.history().isEmpty());assertNotNull(store.get(id));
            body.performClick();shadowOf(Looper.getMainLooper()).idle();menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();assertTrue(menu.getMenu().performIdentifierAction(3,0));shadowOf(Looper.getMainLooper()).idle();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(1,store.queue().size());
            body.performClick();shadowOf(Looper.getMainLooper()).idle();menu=org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();assertTrue(menu.getMenu().performIdentifierAction(1,0));shadowOf(Looper.getMainLooper()).idle();assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());assertEquals(1,store.queue().size());
        }
    }

    @Test public void compactQueueCardHasOnlyTrashAndRemovalStillRequiresConfirmation(){
        String text="کارت جمع‌وجور صف ❤️";long id=store.approve(text,settings,"SIM");
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();
            ViewGroup card=(ViewGroup)CatalogBrowsingTest.text(root(a),text).getParent();assertEquals(0,actionCount(card,android.widget.Button.class));assertEquals(1,actionCount(card,android.widget.ImageButton.class));assertNull(CatalogBrowsingTest.text(card,settings.recipient));
            queueAction(a,text,5);AlertDialog info=ShadowAlertDialog.getLatestAlertDialog();assertTrue(shadowOf(info).getMessage().toString().contains(settings.recipient));info.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            CatalogBrowsingTest.described(root(a),"حذف از صف").performClick();shadowOf(Looper.getMainLooper()).idle();AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();assertEquals(1,store.queue().size());confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(1,store.queue().size());
            CatalogBrowsingTest.described(root(a),"حذف از صف").performClick();shadowOf(Looper.getMainLooper()).idle();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(store.queue().isEmpty());assertEquals("CANCELLED",store.get(id).status);
        }
    }
    @Test public void tomorrowShortcutPreservesClockAndToastUsesRelativeDate(){
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();long initial=java.time.LocalDate.now(zone).plusDays(2).atTime(11,35).atZone(zone).toInstant().toEpochMilli();String text="فردا یاد تو ❤️";long id=store.approveFixed(text,settings,"SIM",initial);
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();queueAction(a,text,1);AlertDialog form=ShadowAlertDialog.getLatestAlertDialog();View view=form.getWindow().getDecorView();
            for(String label:new String[]{"امروز","فردا","تاریخ دیگر"})assertNotNull(CatalogBrowsingTest.text(view,label));CatalogBrowsingTest.text(view,"فردا").performClick();form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            long expected=java.time.LocalDate.now(zone).plusDays(1).atTime(11,35).atZone(zone).toInstant().toEpochMilli();assertEquals(expected,store.get(id).requestedAt);assertEquals("زمان ارسال: فردا ساعت ۱۱:۳۵",ShadowToast.getTextOfLatestToast().toString());
        }
    }
    @Test public void otherDateOpensSolarCalendarAndSelectsPersianNewYearWithoutChangingClock(){
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();java.time.LocalDate initial=java.time.LocalDate.now(zone).plusDays(2);String text="قرار نوروز ❤️";long id=store.approveFixed(text,settings,"SIM",initial.atTime(10,20).atZone(zone).toInstant().toEpochMilli());
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();queueAction(a,text,1);AlertDialog form=ShadowAlertDialog.getLatestAlertDialog();CatalogBrowsingTest.text(form.getWindow().getDecorView(),"تاریخ دیگر").performClick();shadowOf(Looper.getMainLooper()).idle();
            AlertDialog picker=ShadowAlertDialog.getLatestAlertDialog();assertEquals("انتخاب تاریخ خورشیدی",shadowOf(picker).getTitle().toString());assertEquals(0,actionCount(picker.getWindow().getDecorView(),android.widget.DatePicker.class));
            CatalogBrowsingTest.described(picker.getWindow().getDecorView(),"انتخاب ماه و سال خورشیدی").performClick();shadowOf(Looper.getMainLooper()).idle();AlertDialog chooser=ShadowAlertDialog.getLatestAlertDialog();
            int year=PersianDates.fields(java.time.LocalDate.now(zone),zone)[0]+1;((android.widget.NumberPicker)CatalogBrowsingTest.described(chooser.getWindow().getDecorView(),"سال خورشیدی")).setValue(year);((android.widget.NumberPicker)CatalogBrowsingTest.described(chooser.getWindow().getDecorView(),"ماه خورشیدی")).setValue(1);chooser.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
            java.time.LocalDate newYear=PersianDates.date(year,1,1,zone);CatalogBrowsingTest.described(picker.getWindow().getDecorView(),PersianDates.solar(newYear,zone)).performClick();picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertTrue(form.isShowing());assertEquals(1,store.queue().size());form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(newYear.atTime(10,20).atZone(zone).toInstant().toEpochMilli(),store.get(id).requestedAt);
        }
    }
    @Test public void cancellingSolarPickerOrChoosingPastTimeCannotMutateQueuedMessage(){
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();String text="زمان محفوظ ❤️";long at=java.time.LocalDate.now(zone).plusDays(1).atTime(0,0).atZone(zone).toInstant().toEpochMilli();long id=store.approveFixed(text,settings,"SIM",at);long revision=store.get(id).revision;
        try(ActivityController<OfflineActivity> c=Robolectric.buildActivity(OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(root(a),"صف ارسال").performClick();shadowOf(Looper.getMainLooper()).idle();queueAction(a,text,1);AlertDialog form=ShadowAlertDialog.getLatestAlertDialog();CatalogBrowsingTest.text(form.getWindow().getDecorView(),"تاریخ دیگر").performClick();shadowOf(Looper.getMainLooper()).idle();ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(form.isShowing());assertEquals(revision,store.get(id).revision);
            CatalogBrowsingTest.text(form.getWindow().getDecorView(),"امروز").performClick();form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(form.isShowing());assertEquals(at,store.get(id).requestedAt);assertEquals(revision,store.get(id).revision);
        }
    }
}
