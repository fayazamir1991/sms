package ir.delneveshte.app;

import android.app.Application;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class UiControlsTest {
    private Application app;
    @Before public void reset() throws Exception {
        app=RuntimeEnvironment.getApplication();Field field=AppStore.class.getDeclaredField("db");field.setAccessible(true);
        SQLiteDatabase old=(SQLiteDatabase)field.get(null);if(old!=null&&old.isOpen())old.close();field.set(null,null);app.deleteDatabase("messages.db");
        app.getSharedPreferences("settings",0).edit().clear().putString("nickname","نام قدیمی").commit();
        app.getSharedPreferences("cloud",0).edit().clear().putString("url","https://old.example.com").putBoolean("enabled",false).commit();
        app.getSharedPreferences("catalog_format",0).edit().putInt("version",3).commit();
    }
    private int inputs(View v){int n=v instanceof EditText?1:0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)n+=inputs(g.getChildAt(i));}return n;}
    private void checkIconButtons(View v){
        if(v instanceof ImageButton){ImageButton b=(ImageButton)v;assertEquals(ImageView.ScaleType.CENTER_INSIDE,b.getScaleType());assertEquals(b.getLayoutParams().width,b.getLayoutParams().height);assertTrue(b.getMinimumWidth()>=48*app.getResources().getDisplayMetrics().density);assertNotNull(b.getContentDescription());}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)checkIconButtons(g.getChildAt(i));}
    }
    @Test public void searchClearHasASquareTouchTargetAndClearsLiveResults(){
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){
            MainActivity a=c.get();View home=a.getWindow().getDecorView();checkIconButtons(home);CatalogBrowsingTest.text(home,"جست‌وجو").performClick();shadowOf(Looper.getMainLooper()).idle();
            View root=a.getWindow().getDecorView();checkIconButtons(root);EditText field=CatalogBrowsingTest.search(root);field.setText("zzzz");shadowOf(Looper.getMainLooper()).idle();
            View clear=CatalogBrowsingTest.described(root,"پاک‌کردن جست‌وجو");assertTrue(clear.isEnabled());int size=Math.round(48*app.getResources().getDisplayMetrics().density);clear.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));clear.layout(0,0,size,size);
            long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,2,2,0),up=android.view.MotionEvent.obtain(now,now+10,android.view.MotionEvent.ACTION_UP,2,2,0);
            assertTrue(clear.dispatchTouchEvent(down));assertTrue(clear.dispatchTouchEvent(up));down.recycle();up.recycle();shadowOf(Looper.getMainLooper()).idle();assertEquals("",field.getText().toString());assertFalse(clear.isEnabled());
        }
    }
    @Test public void settingsOnlyExposeDestinationCountAndGeneralAndroidHelp(){
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(a.getWindow().getDecorView(),"تنظیمات").performClick();shadowOf(Looper.getMainLooper()).idle();View root=a.getWindow().getDecorView();
            assertEquals(2,inputs(root));for(String label:new String[]{"نام یا لقب در پیشنهادها","بانک پیام‌ها و همگام‌سازی","خاموش‌کردن همگام‌سازی","تغییر سرور","آماده‌سازی شیائومی","تنظیم شروع خودکار شیائومی"})assertNull(CatalogBrowsingTest.text(root,label));
            assertNotNull(CatalogBrowsingTest.text(root,"اجرای پس‌زمینه و باتری"));assertTrue(new CloudConfig(app).enabled());assertEquals(CloudConfig.normalizeUrl(BuildConfig.API_BASE_URL),new CloudConfig(app).baseUrl());
            CatalogBrowsingTest.text(root,"تنظیم مصرف باتری").performClick();Intent intent=shadowOf(a).getNextStartedActivity();assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,intent.getAction());
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void xmarkRendersWithEqualWidthAndHeightInsteadOfStretching(){
        Drawable x=app.getDrawable(R.drawable.ic_close);Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);x.setBounds(0,0,96,96);x.draw(new Canvas(bitmap));
        Rect ink=new Rect(96,96,0,0);for(int y=0;y<96;y++)for(int col=0;col<96;col++)if((bitmap.getPixel(col,y)>>>24)>128){ink.left=Math.min(ink.left,col);ink.top=Math.min(ink.top,y);ink.right=Math.max(ink.right,col+1);ink.bottom=Math.max(ink.bottom,y+1);}
        assertTrue(ink.width()>40);assertTrue(Math.abs(ink.width()-ink.height())<=2);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void cardIconPaintsASmallSurfaceInsideItsLargerTouchTarget(){
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){
            MainActivity a=c.get();ImageButton clock=(ImageButton)CatalogBrowsingTest.described(a.getWindow().getDecorView(),"ارسال در زمان دلخواه");float density=app.getResources().getDisplayMetrics().density;int size=Math.round(48*density);
            assertTrue(clock.getMinimumWidth()>=size);assertTrue(clock.getDrawable().getIntrinsicWidth()<=Math.round(20*density));
            Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Drawable background=clock.getBackground();background.setBounds(0,0,size,size);background.draw(new Canvas(bitmap));Rect ink=new Rect(size,size,0,0);
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)if((bitmap.getPixel(x,y)>>>24)>128){ink.left=Math.min(ink.left,x);ink.top=Math.min(ink.top,y);ink.right=Math.max(ink.right,x+1);ink.bottom=Math.max(ink.bottom,y+1);}
            assertTrue(ink.width()>=Math.round(30*density));assertTrue(ink.width()<=Math.round(33*density));assertEquals(ink.width(),ink.height());assertTrue(ink.left>=Math.round(7*density));
        }
    }
    private void checkTextButtonsFit(View v){
        if(v instanceof android.widget.Button){android.widget.Button b=(android.widget.Button)v;assertNotNull(b.getLayout());assertTrue("Clipped button: "+b.getText(),b.getHeight()-b.getCompoundPaddingTop()-b.getCompoundPaddingBottom()>=b.getLayout().getHeight());}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)checkTextButtonsFit(g.getChildAt(i));}
    }
    @Test @Config(qualifiers="w320dp-h640dp") public void narrowScreenAndLargeSystemFontKeepLabelsVisibleAndNavigationBelowContent() throws Exception {
        android.content.res.Resources resources=app.getResources();android.content.res.Configuration config=new android.content.res.Configuration(resources.getConfiguration());config.fontScale=1.6f;resources.updateConfiguration(config,resources.getDisplayMetrics());
        try(ActivityController<MessageActionsTest.OfflineActivity> c=Robolectric.buildActivity(MessageActionsTest.OfflineActivity.class).setup()){
            MainActivity a=c.get();CatalogBrowsingTest.text(a.getWindow().getDecorView(),"تنظیمات").performClick();shadowOf(Looper.getMainLooper()).idle();View root=a.getWindow().getDecorView();float density=resources.getDisplayMetrics().density;int w=Math.round(320*density),h=Math.round(640*density);
            root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);checkTextButtonsFit(root);
            View nav=(View)CatalogBrowsingTest.text(root,"پیشنهادها").getParent().getParent().getParent();Field scroll=MainActivity.class.getDeclaredField("pageScroll");scroll.setAccessible(true);View content=(View)scroll.get(a);Rect contentRect=new Rect(),navRect=new Rect();assertTrue("Content is outside the test window",content.getGlobalVisibleRect(contentRect));assertTrue("Navigation is outside the test window",nav.getGlobalVisibleRect(navRect));assertTrue("Navigation overlaps content",contentRect.bottom<=navRect.top);
        }
    }
}
