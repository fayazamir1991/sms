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

    @Test public void addingSuggestionNeedsOneTapAndStaysOnSuggestions() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            View action = findText(activity.getWindow().getDecorView(), "افزودن به صف");
            assertNotNull(action);
            click(action);
            assertEquals(1, store.queue().size());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertNotNull(findText(activity.getWindow().getDecorView(), "بارگذاری پیام‌های بیشتر"));
            assertTrue(ShadowToast.getTextOfLatestToast().contains("ارسال خودکار را روشن کن"));
            assertFalse(action.isEnabled());
            Settings changed = settings.copy();
            changed.recipient = "+989351234567";
            changed.subscriptionId = 8;
            store.saveSettings(changed);
            assertEquals(settings.recipient, store.queue().get(0).recipient);
            assertEquals(7, store.queue().get(0).subscriptionId);
        }
    }

    @Test public void editingAddsExactEditedTextWithoutAnotherDialog() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(findText(controller.get().getWindow().getDecorView(), "ویرایش و زمان‌بندی"));
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(editor);
            EditText input = findType(editor.getWindow().getDecorView(), EditText.class);
            input.setText("دلم یک چای دونفره می‌خواهد ☕💕");
            click(editor.getButton(AlertDialog.BUTTON_POSITIVE));
            assertFalse(editor.isShowing());
            assertSame(editor, ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(1, store.queue().size());
            assertEquals("دلم یک چای دونفره می‌خواهد ☕💕", store.queue().get(0).body);
        }
    }

    @Test public void cancellingImmediateSendDoesNotCreateOrQueueMessage() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(findText(controller.get().getWindow().getDecorView(), "ارسال فوری"));
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(confirmation);
            assertTrue(store.queue().isEmpty());
            assertTrue(store.history().isEmpty());
            click(confirmation.getButton(AlertDialog.BUTTON_NEGATIVE));
            assertTrue(store.queue().isEmpty());
            assertTrue(store.history().isEmpty());
        }
    }

    @Test public void immediateIntentCannotBecomeAnAutomaticQueuedSend() {
        long id = store.approveImmediate("دوستت دارم ❤️", settings, "SIM 1");
        assertTrue(store.queue().isEmpty());
        assertTrue(store.unscheduled().isEmpty());
        assertFalse(store.schedule(id, System.currentTimeMillis(), "2026-11-01"));
        store.requeueFuture();
        assertTrue(store.queue().isEmpty());
        store.recoverUncertain(System.currentTimeMillis() + 16 * 60_000);
        assertEquals("FAILED", store.get(id).status);
        assertFalse(store.claim(id, System.currentTimeMillis(), 1));
    }

    @Test public void confirmingImmediateSendUsesThePreviewedRecipientAndNeverQueues() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(findText(controller.get().getWindow().getDecorView(), "ارسال فوری"));
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(store.history().isEmpty());
            Settings changed = settings.copy();
            changed.recipient = "+989351234567";
            store.saveSettings(changed);
            click(confirmation.getButton(AlertDialog.BUTTON_POSITIVE));
            assertTrue(store.queue().isEmpty());
            assertEquals(1, store.history().size());
            Message sent = store.history().get(0);
            assertEquals(settings.recipient, sent.recipient);
            assertEquals(7, sent.subscriptionId);
            assertEquals("SENDING", sent.status);
        }
    }

    @Test public void multipartImmediateSendIsClaimedOnceAndCallbacksAreDeduplicated() {
        long id = store.approveImmediate("دلتنگتم 🫂❤️", settings, "SIM 1");
        assertTrue(store.claim(id, System.currentTimeMillis(), 2));
        assertFalse(store.claim(id, System.currentTimeMillis(), 2));
        store.recordPart(id, 0, true, "");
        store.recordPart(id, 0, true, "");
        assertEquals("SENDING", store.get(id).status);
        store.recordPart(id, 1, true, "");
        assertEquals("SENT", store.get(id).status);
        assertTrue(store.queue().isEmpty());
    }

    @Test public void browseLoadsTwentyThenAppendsAndSearchesWithoutLosingEarlierCards() throws Exception {
        org.json.JSONArray rows=new org.json.JSONArray();
        for(int i=0;i<45;i++)rows.put(new org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("body","یاد تو "+(i%2==0?"باران":"لبخند")+" پیام "+i+" 💕").put("category","کوتاه").put("source","taw-bio").put("source_url","https://taw-bio.ir/source"));
        store.replaceCatalog("کوتاه",rows);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();assertNotNull(findText(activity.getWindow().getDecorView(),"پیام‌ها: ۲۰"));
            TextView initial=findPrefix(activity.getWindow().getDecorView(),"یاد تو ");assertNotNull(initial);String first=initial.getText().toString();
            assertNull(findPrefix(activity.getWindow().getDecorView(),"منبع:"));
            click(findText(activity.getWindow().getDecorView(),"بارگذاری پیام‌های بیشتر"));
            assertNotNull(findText(activity.getWindow().getDecorView(),first));assertNotNull(findText(activity.getWindow().getDecorView(),"پیام‌ها: ۴۰"));
            click(findText(activity.getWindow().getDecorView(),"بارگذاری پیام‌های بیشتر"));assertNotNull(findText(activity.getWindow().getDecorView(),"پیام‌ها: ۴۵"));
            EditText query=findType(activity.getWindow().getDecorView(),EditText.class);query.setText("باران");click(findText(activity.getWindow().getDecorView(),"جست‌وجو"));assertNotNull(findText(activity.getWindow().getDecorView(),"پیام‌ها: ۲۰"));
            assertNull(findPrefix(activity.getWindow().getDecorView(),"یاد تو لبخند"));
        }
    }
    private static TextView findPrefix(View root,String prefix){
        if(root instanceof TextView&&((TextView)root).getText().toString().startsWith(prefix))return (TextView)root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){TextView found=findPrefix(group.getChildAt(i),prefix);if(found!=null)return found;}}return null;
    }

    private static void click(View view) {
        assertNotNull(view);
        view.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T findType(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
