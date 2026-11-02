package ir.delneveshte.app;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class PullRefreshTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final int[] calls={0};
    private PullRefreshLayout layout;
    private ScrollView scroll;
    private void create(){scroll=new ScrollView(context);TextView child=new TextView(context);child.setText("بانک پیام");scroll.addView(child,new ScrollView.LayoutParams(400,3000));layout=new PullRefreshLayout(context,scroll);layout.setOnRefreshListener(()->calls[0]++);layout.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));layout.layout(0,0,400,600);}
    private float px(float dp){return dp*context.getResources().getDisplayMetrics().density;}
    private boolean event(int type,float x,float y){long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,type,px(x),px(y),0);boolean handled=layout.dispatchTouchEvent(event);event.recycle();return handled;}
    @Test public void onlyACompleteDownwardReleaseAtTopRefreshesAndRepeatedDragsDoNotDuplicate(){
        create();event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,40,110);event(MotionEvent.ACTION_UP,40,110);assertEquals(1,calls[0]);assertTrue(layout.isRefreshing());
        event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,40,140);event(MotionEvent.ACTION_UP,40,140);assertEquals(1,calls[0]);
        layout.setRefreshing(false);event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,40,110);event(MotionEvent.ACTION_CANCEL,40,110);assertEquals(1,calls[0]);assertFalse(layout.isRefreshing());
    }
    @Test public void shortHorizontalUpwardAndScrolledGesturesDoNotRefresh(){
        create();event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,40,50);event(MotionEvent.ACTION_UP,40,50);assertEquals(0,calls[0]);
        event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,160,45);event(MotionEvent.ACTION_UP,160,45);assertEquals(0,calls[0]);
        event(MotionEvent.ACTION_DOWN,40,160);event(MotionEvent.ACTION_MOVE,40,30);event(MotionEvent.ACTION_UP,40,30);assertEquals(0,calls[0]);
        scroll.scrollTo(0,200);assertTrue(scroll.canScrollVertically(-1));event(MotionEvent.ACTION_DOWN,40,20);event(MotionEvent.ACTION_MOVE,40,140);event(MotionEvent.ACTION_UP,40,140);assertEquals(0,calls[0]);
    }
    @Test public void accessibilityCanRefreshWithoutAGesture(){create();assertTrue(layout.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null));assertEquals(1,calls[0]);}
}
