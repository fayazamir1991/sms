package ir.delneveshte.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class StartupDefaultsTest {
    private Context app;
    private void close() throws Exception{Field f=AppStore.class.getDeclaredField("db");f.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)f.get(null);if(db!=null&&db.isOpen())db.close();f.set(null,null);}
    @Before public void reset() throws Exception{app=RuntimeEnvironment.getApplication();close();app.deleteDatabase("messages.db");app.getSharedPreferences("settings",0).edit().clear().commit();}
    @Test public void freshInstallStaysEnabledAcrossLaunchWithoutPermissionsButCannotSend() throws Exception{
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.SEND_SMS,android.Manifest.permission.READ_PHONE_STATE);
        AppStore store=new AppStore(app);assertTrue(store.settings().enabled);assertFalse(store.settings().valid());
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){assertTrue(store.settings().enabled);assertEquals(0,store.approvedCount());assertTrue(store.scheduled().isEmpty());assertFalse(SmsRuntime.readiness(app).isEmpty());}
        close();assertTrue(new AppStore(app).settings().enabled);
    }
    @Test public void anExplicitPauseAndAnOldInstallWithoutPreferenceRemainPaused() throws Exception{
        AppStore store=new AppStore(app);SmsRuntime.pause(app);assertFalse(store.settings().enabled);close();assertFalse(new AppStore(app).settings().enabled);
        close();app.getSharedPreferences("settings",0).edit().remove("enabled").commit();assertFalse(new AppStore(app).settings().enabled);
    }
    @Test public void setupPreferencesDoNotRequireAnExtraEnableStep() throws Exception{
        AppStore store=new AppStore(app);Settings s=store.settings();s.recipient="+989121234567";s.subscriptionId=7;store.saveSettings(s);assertTrue(store.settings().enabled);assertTrue(store.settings().valid());
        SmsRuntime.reconcile(app);assertTrue(store.settings().enabled);assertEquals(0,store.approvedCount());
    }
}
