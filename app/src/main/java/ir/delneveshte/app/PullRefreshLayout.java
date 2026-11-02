package ir.delneveshte.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Refresh only on a deliberate downward release at the top; never steals horizontal or upward scrolls. */
public final class PullRefreshLayout extends FrameLayout {
    private final ScrollView scroll;
    private final LinearLayout indicator;
    private final TextView label;
    private final ProgressBar progress;
    private final int slop;
    private float startX,startY,distance;
    private boolean eligible,dragging,refreshing;
    private int pointer=-1;
    private Runnable listener;
    public PullRefreshLayout(Context context,ScrollView content) {
        super(context);scroll=content;addView(scroll,new LayoutParams(-1,-1));
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        indicator=new LinearLayout(context);indicator.setOrientation(LinearLayout.HORIZONTAL);indicator.setGravity(Gravity.CENTER);indicator.setPadding(dp(16),dp(8),dp(16),dp(8));
        GradientDrawable background=new GradientDrawable();background.setColor(Color.WHITE);background.setCornerRadius(dp(24));indicator.setBackground(background);indicator.setElevation(dp(2));
        progress=new ProgressBar(context);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(117,100,168)));indicator.addView(progress,new LinearLayout.LayoutParams(dp(20),dp(20)));
        label=new TextView(context);label.setTextColor(Color.rgb(98,82,142));label.setTextSize(13);label.setTypeface(context.getResources().getFont(R.font.vazirmatn_regular),Typeface.NORMAL);label.setPadding(dp(8),0,0,0);indicator.addView(label);
        LayoutParams params=new LayoutParams(-2,dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);params.topMargin=dp(8);addView(indicator,params);indicator.setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    public void setOnRefreshListener(Runnable action){listener=action;}
    public boolean isRefreshing(){return refreshing;}
    public void setRefreshing(boolean value){refreshing=value;dragging=false;distance=0;if(value){indicator.setVisibility(VISIBLE);progress.setVisibility(VISIBLE);label.setText("در حال تازه‌سازی…");}else indicator.setVisibility(GONE);}
    private void resetDrag(){dragging=false;eligible=false;pointer=-1;distance=0;if(!refreshing)indicator.setVisibility(GONE);}
    private void showPull(){progress.setVisibility(GONE);indicator.setVisibility(VISIBLE);label.setText(distance>=dp(72)?"برای تازه‌سازی رها کن":"برای تازه‌سازی پایین بکش");}
    private void refresh(){if(refreshing||listener==null)return;setRefreshing(true);listener.run();}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                resetDrag();startX=event.getX();startY=event.getY();pointer=event.getPointerId(0);eligible=!refreshing&&!scroll.canScrollVertically(-1);break;
            case MotionEvent.ACTION_MOVE:
                int index=event.findPointerIndex(pointer);if(index<0)return false;
                float dy=event.getY(index)-startY,dx=Math.abs(event.getX(index)-startX);
                if(eligible&&dy>slop&&dy>dx*1.5f){dragging=true;distance=dy;showPull();return true;}
                if(dx>slop||dy < -slop)eligible=false;break;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_UP:resetDrag();break;
            default:break;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!dragging)return super.onTouchEvent(event);
        int index=event.findPointerIndex(pointer);
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&index>=0){distance=Math.max(0,event.getY(index)-startY);showPull();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_UP){boolean ready=distance>=dp(72);resetDrag();if(ready)performClick();return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL||event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||index<0){resetDrag();return true;}
        return true;
    }
    @Override public boolean performClick(){super.performClick();refresh();return true;}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,"تازه‌سازی بانک پیام"));}
    @Override public boolean performAccessibilityAction(int action,Bundle arguments){if(action==AccessibilityNodeInfo.ACTION_CLICK)return performClick();return super.performAccessibilityAction(action,arguments);}
}
