package ir.delneveshte.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Local approvals and automatic cloud synchronization. No SMS is sent on launch. */
public class MainActivity extends Activity {
    private static final int BG=AppThemeTokens.BACKGROUND;
    private static final int INK=AppThemeTokens.TEXT;
    private static final int ACCENT=AppThemeTokens.ACCENT;
    private static final int PALE=AppThemeTokens.SOFT_ACCENT;
    private static final int MUTED=AppThemeTokens.SECONDARY;
    private static final int LINE=AppThemeTokens.DIVIDER;
    private static final int GOOD=AppThemeTokens.SUCCESS;
    private static final int REQUEST_SIM = 102;
    private static final int REQUEST_SMS = 103;
    private static final int REQUEST_NOTIFICATIONS = 104;
    private final Handler handler = new Handler();
    private final Random random = new Random();
    private Typeface regularFont,mediumFont;
    private AppStore store;
    private ir.delneveshte.app.Settings draft;
    private LinearLayout page;
    private ScrollView pageScroll;
    private PullRefreshLayout bankRefresh;
    private EditText searchInput;
    private boolean searchKeyboardRequested;
    private int searchKeyboardAttempts;
    private boolean updateCheckStarted;
    private int updatePolls;
    private final Runnable updatePoll=new Runnable(){@Override public void run(){if(!isFinishing()&&!isDestroyed()&&updatePolls++<180){if(!AppUpdater.installCompleted(MainActivity.this))handler.postDelayed(this,2000);}}};
    private EditText activeEditor;
    private AlertDialog activeEditorDialog;
    private int currentTab;
    private String category;
    private List<String> options = new ArrayList<>();
    private boolean refreshingSuggestions,catalogNetworkFailed;
    private String catalogQuery="",catalogSearchDraft="",catalogCursor;
    private LinearLayout catalogResults;
    private SearchController messageSearch;
    private boolean emptySearch(){return currentTab==4&&!CatalogSearch.canSearch(catalogQuery);}
    private boolean liveQuery(){return currentTab==4&&CatalogSearch.canSearch(catalogQuery);}
    private final Runnable liveSearch=()->{if(currentTab==4&&!isFinishing()&&!isDestroyed())freshSuggestions();};
    private String activeCategory(){return currentTab==4?"همه":category;}
    private String activeQuery(){return currentTab==4?catalogQuery:"";}
    private boolean catalogRequested;
    private boolean usingOfflineCatalog=true;
    private boolean catalogHasMore=true;
    private int localCatalogOffset;
    private volatile int browseGeneration;
    private final java.util.Set<String> sessionEnqueued=new java.util.HashSet<>();
    private final java.util.Map<String,String> credits=new java.util.HashMap<>();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && (currentTab == 1 || currentTab == 2)) renderKeepingScroll();
            handler.postDelayed(this, 8000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        regularFont=getResources().getFont(R.font.vazirmatn_regular);mediumFont=getResources().getFont(R.font.vazirmatn_medium);
        store = new AppStore(this);
        messageSearch=new SearchController(this,store,this::catalogTransport,(rows,more,loading,error)->{
            if(!liveQuery()||isFinishing()||isDestroyed())return;
            List<String> rendered=new ArrayList<>();for(String raw:rows){String text=CatalogText.render(raw,catalogNickname());if(text!=null)rendered.add(text);}
            boolean changed=!rendered.equals(options)||catalogHasMore!=more||refreshingSuggestions!=loading||catalogNetworkFailed!=(error!=null);
            if(!changed)return;
            options.clear();credits.clear();for(String raw:rows)appendOption(raw);
            catalogHasMore=more;refreshingSuggestions=loading;catalogNetworkFailed=error!=null;renderCatalogResults();
        });
        draft = store.settings().copy();
        android.content.SharedPreferences format=getSharedPreferences("catalog_format",0);
        if(format.getInt("version",0)<3){store.clearCatalog();new CloudConfig(this).catalogUpdatedReset();format.edit().putInt("version",3).apply();}
        String[] categories = TextBank.categories();
        category = categories.length == 0 ? "همه" : categories[0];
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("tab", 0);
            catalogQuery=savedInstanceState.getString("catalog_query","");
            catalogSearchDraft=savedInstanceState.getString("catalog_search_draft",catalogQuery);
            category = savedInstanceState.getString("category", category);
            draft.recipient = savedInstanceState.getString("draft_recipient", draft.recipient);
            draft.tone = savedInstanceState.getString("draft_tone", draft.tone);
            draft.dailyCount = savedInstanceState.getInt("draft_count", draft.dailyCount);
            draft.startMinute = savedInstanceState.getInt("draft_start", draft.startMinute);
            draft.endMinute = savedInstanceState.getInt("draft_end", draft.endMinute);
            draft.subscriptionId = savedInstanceState.getInt("draft_sim", draft.subscriptionId);
        }
        searchKeyboardRequested=currentTab==4;
        Window window = getWindow();
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setStatusBarColor(AppThemeTokens.STATUS_BAR);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        renewOptions();
        render();
        if (savedInstanceState != null && savedInstanceState.containsKey("editor_text"))
            editText(savedInstanceState.getString("editor_text", ""));
    }

    @Override protected void onResume() {
        super.onResume();
        store.recoverUncertain(System.currentTimeMillis());
        // Android removes alarms on force-stop; reopening restores the approved schedule.
        SmsRuntime.reconcile(this);
        CloudScheduler.ensure(this);
        AppUpdater.installCompleted(this);
        if(!updateCheckStarted){updateCheckStarted=true;checkForUpdates(false);}
        if(options.isEmpty()&&(!catalogRequested||usingOfflineCatalog))renewOptions();
        render();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 8000);
        CloudConfig cloud=new CloudConfig(this);
        if((currentTab==0||liveQuery())&&cloud.enabled()&&(!catalogRequested||currentTab==4)&&activeEditorDialog==null)freshSuggestions();
    }

    @Override protected void onPause() {
        searchKeyboardRequested=false;
        handler.removeCallbacks(refresh);handler.removeCallbacks(updatePoll);
        handler.removeCallbacks(liveSearch);if(messageSearch!=null)messageSearch.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);handler.removeCallbacks(liveSearch);
        if(messageSearch!=null)messageSearch.close();
        if (activeEditorDialog != null) activeEditorDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("catalog_query",catalogQuery);
        state.putString("catalog_search_draft",catalogSearchDraft);
        state.putInt("tab", currentTab);
        state.putString("category", category);
        state.putString("draft_recipient", draft.recipient);
        state.putString("draft_tone", draft.tone);
        state.putInt("draft_count", draft.dailyCount);
        state.putInt("draft_start", draft.startMinute);
        state.putInt("draft_end", draft.endMinute);
        state.putInt("draft_sim", draft.subscriptionId);
        if (activeEditor != null && activeEditorDialog != null && activeEditorDialog.isShowing())
            state.putString("editor_text", activeEditor.getText().toString());
        super.onSaveInstanceState(state);
    }

    private void renderKeepingScroll() {
        int scrollY = pageScroll == null ? 0 : pageScroll.getScrollY();
        render();
        pageScroll.post(() -> pageScroll.scrollTo(0, scrollY));
    }

    private void render() {
        catalogResults=null;searchInput=null;bankRefresh=null;
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(0,0,0,0);
        root.addView(appHeader());
        if(currentTab==0)root.addView(categoryTabs());
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page = vertical();
        page.setPadding(0,dp(4),0,dp(currentTab==0?72:16));
        scroll.addView(page);
        FrameLayout content=new FrameLayout(this);content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if(currentTab==0){
            bankRefresh=new PullRefreshLayout(this,scroll);bankRefresh.setOnRefreshListener(this::freshSuggestions);
            content.addView(bankRefresh,new FrameLayout.LayoutParams(-1,-1));
        }else content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if (currentTab == 0||currentTab==4) suggestionsPage();
        else if (currentTab == 1) queuePage();
        else if (currentTab == 2) historyPage();
        else settingsPage();
        if(currentTab==0){
            ImageButton compose=compactAction("نوشتن پیام",R.drawable.ic_compose,()->editText(""));
            compose.setBackground(iconBackground(ACCENT,UiMetrics.COMPOSE_SURFACE));compose.setColorFilter(Color.WHITE);compose.setElevation(dp(2));compose.setPadding(dp(14),dp(14),dp(14),dp(14));
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH),Gravity.BOTTOM|Gravity.RIGHT);fp.setMargins(dp(12),dp(12),dp(12),dp(12));content.addView(compose,fp);
        }
        root.addView(navigation(),new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        if(currentTab==4&&searchKeyboardRequested)focusSearchInput();
    }

    private View appHeader(){
        if(currentTab==4)return searchToolbar();
        LinearLayout bar=horizontal();bar.setBackgroundColor(BG);bar.setPadding(dp(16),0,dp(8),0);bar.setMinimumHeight(dp(56));
        ImageView brand=new ImageView(this);brand.setScaleType(ImageView.ScaleType.CENTER_INSIDE);brand.setImageResource(R.drawable.ic_home);brand.setColorFilter(AppThemeTokens.COMPLEMENT_INK);brand.setBackground(shape(AppThemeTokens.SOFT_COMPLEMENT,14,0));brand.setPadding(dp(6),dp(6),dp(6),dp(6));brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(28),dp(28));bp.setMarginEnd(dp(10));bar.addView(brand,bp);
        LinearLayout title=vertical();String name=currentTab==0?"دل‌نوشته":currentTab==1?"صف ارسال":currentTab==2?"سابقه":"تنظیمات";
        title.addView(label(name,20,INK,true));
        boolean active=store.settings().enabled;String status=active?"ارسال فعال":"ارسال متوقف";
        title.addView(label(status,12,MUTED,false));bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        title.setContentDescription(active?"ارسال خودکار فعال است":"ارسال خودکار متوقف است");title.setOnClickListener(v->selectTab(3));
        if(currentTab!=3){ImageButton search=toolbarAction("جست‌وجو",R.drawable.ic_search,()->selectTab(4));bar.addView(search,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));}
        return bar;
    }
    private ImageButton toolbarAction(String description,int icon,Runnable action){
        ImageButton button=compactAction(description,icon,action);button.setColorFilter(ACCENT);button.setBackground(iconBackground(Color.TRANSPARENT,UiMetrics.ICON_SURFACE));return button;
    }
    private View searchToolbar(){
        LinearLayout bar=horizontal();bar.setMinimumHeight(dp(56));bar.setPadding(dp(8),dp(2),dp(12),dp(2));bar.setBackgroundColor(BG);
        bar.addView(toolbarAction("بازگشت به پیشنهادها",R.drawable.ic_back,()->selectTab(0)),new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));
        LinearLayout field=horizontal();field.setMinimumHeight(dp(UiMetrics.TOUCH));field.setBackground(controlBackground(Color.WHITE,12,UiMetrics.BUTTON_INSET,LINE));
        EditText search=input(catalogSearchDraft,"جست‌وجو؛ حداقل ۲ حرف…",InputType.TYPE_CLASS_TEXT);search.setSingleLine(true);search.setTextSize(16);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setBackgroundColor(Color.TRANSPARENT);search.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);search.setPadding(dp(10),dp(4),dp(4),dp(4));search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        ImageButton clear=toolbarAction("پاک‌کردن جست‌وجو",R.drawable.ic_close,()->search.setText(""));clear.setPadding(dp(15),dp(15),dp(15),dp(15));clear.setEnabled(!catalogSearchDraft.isEmpty());clear.setAlpha(catalogSearchDraft.isEmpty()?0.35f:1f);
        searchInput=search;
        watch(search,value->{
            catalogSearchDraft=value;catalogQuery=value.trim();catalogNetworkFailed=false;clear.setEnabled(!value.isEmpty());clear.setAlpha(value.isEmpty()?0.35f:1f);
            handler.removeCallbacks(liveSearch);browseGeneration++;messageSearch.cancel();catalogCursor=null;usingOfflineCatalog=true;
            options.clear();credits.clear();catalogHasMore=false;refreshingSuggestions=liveQuery();renderCatalogResults();
            if(liveQuery())handler.postDelayed(liveSearch,350);
        });
        search.setOnFocusChangeListener((v,focused)->field.setBackground(controlBackground(Color.WHITE,12,UiMetrics.BUTTON_INSET,focused?ACCENT:LINE)));
        search.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){handler.removeCallbacks(liveSearch);freshSuggestions();return true;}return false;});
        field.addView(search,new LinearLayout.LayoutParams(0,-2,1));field.addView(clear,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-2,1);fp.setMarginStart(dp(4));bar.addView(field,fp);return bar;
    }
    private View categoryTabs(){
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setBackgroundColor(BG);scroll.setHorizontalScrollBarEnabled(false);scroll.setPadding(dp(12),0,dp(12),0);scroll.setClipToPadding(false);LinearLayout tabs=horizontal();
        for(String option:TextBank.categories()){
            boolean selected=option.equals(category);TextView text=label(option,13,selected?Color.WHITE:MUTED,true);text.setGravity(Gravity.CENTER);text.setMinHeight(dp(UiMetrics.TOUCH));text.setPadding(dp(12),dp(8),dp(12),dp(8));text.setBackground(controlBackground(selected?ACCENT:Color.TRANSPARENT,16,UiMetrics.CHIP_INSET,selected?0:LINE));text.setSelected(selected);
            text.setOnClickListener(v->{category=option;freshSuggestions();render();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMarginEnd(dp(6));tabs.addView(text,cp);
        }
        scroll.addView(tabs);return scroll;
    }

    private View navigation() {
        LinearLayout container=vertical();container.setBackgroundColor(Color.WHITE);
        View line=new View(this);line.setBackgroundColor(LINE);container.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=horizontal();nav.setPadding(dp(6),dp(2),dp(6),dp(2));nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout right=horizontal();right.addView(navigationItem("صف",R.drawable.ic_queue,1,false),new LinearLayout.LayoutParams(0,-2,1));right.addView(navigationItem("سابقه",R.drawable.ic_history,2,false),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout left=horizontal();left.addView(navigationItem("جست‌وجو",R.drawable.ic_search,4,false),new LinearLayout.LayoutParams(0,-2,1));left.addView(navigationItem("تنظیمات",R.drawable.ic_settings,3,false),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(navigationItem("بانک پیام‌ها",R.drawable.ic_home,0,true),new LinearLayout.LayoutParams(dp(76),-2));
        nav.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        container.addView(nav);return container;
    }

    private View navigationItem(String title,int iconResource,int tab,boolean primary){
        boolean selected=currentTab==tab;LinearLayout item=vertical();item.setGravity(Gravity.CENTER);item.setMinimumHeight(dp(60));item.setPadding(0,dp(3),0,dp(3));item.setSelected(selected);
        FrameLayout badge=new FrameLayout(this);badge.setBackgroundColor(Color.TRANSPARENT);
        ImageView icon=new ImageView(this);icon.setTag(iconResource);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setImageResource(iconResource);icon.setColorFilter(selected?Color.WHITE:INK);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);int canvas=primary?30:UiMetrics.ICON;badge.addView(icon,new FrameLayout.LayoutParams(dp(canvas),dp(canvas),Gravity.CENTER));item.addView(badge,new LinearLayout.LayoutParams(dp(40),dp(32)));
        TextView label=label(title,11,selected?Color.WHITE:INK,selected);label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setIncludeFontPadding(false);label.setOnClickListener(v->selectTab(tab));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(2);item.addView(label,lp);
        item.setBackground(ripple(selected?ACCENT:Color.TRANSPARENT,12));item.setContentDescription(title);item.setOnClickListener(v->selectTab(tab));return item;
    }

    private void selectTab(int tab){
        if(tab==4&&currentTab==4){searchKeyboardRequested=true;searchKeyboardAttempts=0;focusSearchInput();return;}
        if(currentTab==4&&tab!=4&&searchInput!=null){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(searchInput.getWindowToken(),0);}
        handler.removeCallbacks(liveSearch);messageSearch.cancel();browseGeneration++;currentTab=tab;
        searchKeyboardRequested=tab==4;searchKeyboardAttempts=0;
        if(tab==4){catalogQuery="";catalogSearchDraft="";}
        if(tab==0||tab==4){usingOfflineCatalog=true;renewOptions();render();if(tab==0||liveQuery())freshSuggestions();}
        else render();
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);if(focused&&currentTab==4&&searchKeyboardRequested)focusSearchInput();
    }
    private void focusSearchInput(){
        final EditText field=searchInput;if(currentTab!=4||field==null||!searchKeyboardRequested)return;
        if(!field.hasFocus()){field.requestFocus();field.setSelection(field.length());}
        field.post(()->{
            if(currentTab!=4||searchInput!=field||!searchKeyboardRequested||!field.isAttachedToWindow()||!hasWindowFocus())return;
            android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(ime!=null&&ime.showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)){searchKeyboardRequested=false;}
            else if(++searchKeyboardAttempts<4)field.postDelayed(this::focusSearchInput,100);
        });
    }

    private ImageButton compactAction(String description,int icon,Runnable action){
        ImageButton button=new ImageButton(this);button.setImageResource(icon);button.setColorFilter(ACCENT);
        button.setContentDescription(description);button.setBackground(iconBackground(PALE,UiMetrics.ICON_SURFACE));button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);button.setAdjustViewBounds(false);button.setMinimumWidth(dp(UiMetrics.TOUCH));button.setMinimumHeight(dp(UiMetrics.TOUCH));button.setPadding(dp(15),dp(15),dp(15),dp(15));
        button.setOnClickListener(v->action.run());return button;
    }
    private void suggestionsPage(){
        catalogResults=vertical();page.addView(catalogResults,match());renderCatalogResults();
    }
    private LinearLayout messageCard(){
        LinearLayout view=vertical();view.setPadding(dp(14),dp(10),dp(14),dp(4));view.setBackground(shape(Color.WHITE,UiMetrics.CARD_RADIUS,LINE));view.setElevation(dp(0));
        LinearLayout.LayoutParams lp=match();lp.setMargins(dp(12),0,dp(12),dp(8));view.setLayoutParams(lp);return view;
    }

    private void renderCatalogResults(){
        if(bankRefresh!=null&&!refreshingSuggestions)bankRefresh.setRefreshing(false);
        if((currentTab!=0&&currentTab!=4)||catalogResults==null)return;
        LinearLayout target=catalogResults;target.removeAllViews();
        if(emptySearch())return;
        if(options.isEmpty())target.addView(body(refreshingSuggestions?"در حال جست‌وجو…":"پیامی پیدا نشد.",14));
        if(options.isEmpty()&&!refreshingSuggestions&&catalogNetworkFailed&&currentTab==4)target.addView(body("برای جست‌وجو در کل بانک، اتصال به سرور را بررسی کن.",13));
        for(String text:options){
            LinearLayout c=messageCard();TextView message=body(text,16);message.setPadding(0,0,0,dp(2));
            message.setOnClickListener(v->messageMenu(c,text));message.setOnLongClickListener(v->{messageMenu(c,text);return true;});
            c.setOnClickListener(v->messageMenu(c,text));c.setOnLongClickListener(v->{messageMenu(c,text);return true;});c.addView(message);
            LinearLayout actions=horizontal();actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            ImageButton random=compactAction("ارسال در زمان تصادفی",sessionEnqueued.contains(text)?R.drawable.ic_check:R.drawable.ic_queue,()->{
                if(sessionEnqueued.contains(text)){toast("این پیام در صف است.");return;}
                if(enqueueMessage(text)){sessionEnqueued.add(text);renderCatalogResults();}
            });
            random.setEnabled(!sessionEnqueued.contains(text));actions.addView(random,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));
            ImageButton fixed=compactAction("ارسال در زمان دلخواه",R.drawable.ic_clock,()->scheduleMessage(text,null));fixed.setColorFilter(AppThemeTokens.COMPLEMENT_INK);fixed.setBackground(iconBackground(AppThemeTokens.SOFT_COMPLEMENT,UiMetrics.ICON_SURFACE));LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH));fp.setMarginStart(dp(2));actions.addView(fixed,fp);c.addView(actions);target.addView(c);
        }
        if(catalogHasMore){Button more=button(refreshingSuggestions?"در حال دریافت…":"بارگذاری پیام‌های بیشتر",false,()->loadCatalogPage(false));more.setEnabled(!refreshingSuggestions);target.addView(more);}
    }
    private void messageMenu(View anchor,String text){
        android.widget.PopupMenu menu=new android.widget.PopupMenu(this,anchor);
        menu.getMenu().add(0,1,0,"ارسال همین حالا").setIcon(R.drawable.ic_send);
        menu.getMenu().add(0,2,1,"ارسال در زمان تصادفی").setIcon(R.drawable.ic_queue);
        menu.getMenu().add(0,3,2,"ارسال در زمان دلخواه").setIcon(R.drawable.ic_clock);
        menu.getMenu().add(0,4,3,"ویرایش متن").setIcon(R.drawable.ic_edit_schedule);
        menu.getMenu().add(0,5,4,"اطلاعات منبع").setIcon(R.drawable.ic_info);
        menu.getMenu().add(0,6,5,"کپی متن").setIcon(R.drawable.ic_copy);
        for(int i=0;i<menu.getMenu().size();i++){android.graphics.drawable.Drawable icon=menu.getMenu().getItem(i).getIcon();if(icon!=null)icon.mutate().setTint(i==2?AppThemeTokens.COMPLEMENT_INK:i==3?AppThemeTokens.ACCENT:ACCENT);}
        if(Build.VERSION.SDK_INT>=29)menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 1:confirmNewSend(text);break;
                case 2:if(enqueueMessage(text)){sessionEnqueued.add(text);renderCatalogResults();}break;
                case 3:scheduleMessage(text,null);break;
                case 4:editText(text);break;
                case 5:TextView info=body(credits.getOrDefault(text,"بانک دل‌نوشته"),14);info.setTextIsSelectable(true);new AlertDialog.Builder(this).setTitle("اطلاعات منبع").setView(info).setPositiveButton("بستن",null).show();break;
                case 6:((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("پیام",text));toast("متن کپی شد.");break;
                default:return false;
            }return true;
        });menu.show();
    }

    private void freshSuggestions(){loadCatalogPage(true);}
    private void loadCatalogPage(boolean reset){
        catalogRequested=true;
        if(currentTab==4){
            if(emptySearch()){handler.removeCallbacks(liveSearch);messageSearch.cancel();renewOptions();renderCatalogResults();return;}
            if(reset)messageSearch.start(catalogQuery,store.settings().tone);else messageSearch.more();return;
        }
        if(emptySearch()){handler.removeCallbacks(liveSearch);browseGeneration++;renewOptions();renderCatalogResults();return;}
        if(!reset&&refreshingSuggestions)return;
        if(reset){catalogNetworkFailed=false;browseGeneration++;catalogCursor=null;catalogHasMore=true;localCatalogOffset=0;usingOfflineCatalog=true;renewOptions();}
        CloudConfig cloud=new CloudConfig(this);
        if(!cloud.enabled()||(!reset&&usingOfflineCatalog)){
            if(!reset)appendLocalPage();
            refreshingSuggestions=false;usingOfflineCatalog=true;renderCatalogResults();return;
        }
        final int generation=browseGeneration,cloudGeneration=cloud.generation();final String query=activeQuery(),chosenCategory=activeCategory(),cursor=catalogCursor,tone=store.settings().tone;
        refreshingSuggestions=true;renderCatalogResults();
        new Thread(()->{
            org.json.JSONObject response=null;String failure=null;
            try{
                response=new CatalogClient(catalogTransport(cloud),cloud).browse(chosenCategory,query,cursor,tone);
                response.put("messages",ToneCatalog.select(response.getJSONArray("messages"),tone));
                org.json.JSONArray rows=response.getJSONArray("messages");if(rows.length()>20)throw new IllegalStateException();
                for(int i=0;i<rows.length();i++){org.json.JSONObject row=rows.getJSONObject(i);if(row.getString("body").length()>1200||(!"همه".equals(chosenCategory)&&!chosenCategory.equals(row.getString("category"))))throw new IllegalStateException();}
                if(generation==browseGeneration&&cloudGeneration==cloud.generation()&&cloud.enabled())store.rememberCatalog(rows);
            }catch(Exception error){failure=error instanceof CloudApi.Failure?error.getMessage():"دریافت پیام‌ها کامل نشد؛ اتصال به سرور را بررسی کن.";}
            final org.json.JSONObject data=response;final String error=failure;
            handler.post(()->{
                if(generation!=browseGeneration||!tone.equals(store.settings().tone)||cloudGeneration!=cloud.generation()||!cloud.enabled()||isFinishing()||isDestroyed())return;
                refreshingSuggestions=false;
                if(error==null&&data!=null){
                    usingOfflineCatalog=false;
                    try{
                        org.json.JSONArray rows=data.getJSONArray("messages");
                        boolean usable=false;for(int i=0;i<rows.length();i++)if(CatalogText.render(rows.getJSONObject(i).getString("body"),catalogNickname())!=null){usable=true;break;}
                        if(usable||!query.isEmpty()){
                            if(reset){options.clear();credits.clear();}
                            for(int i=0;i<rows.length();i++)appendOption(rows.getJSONObject(i).getString("body"));
                            catalogHasMore=data.optBoolean("has_more",false);
                            catalogCursor=data.isNull("next_cursor")?null:data.optString("next_cursor",null);
                        }else{
                            usingOfflineCatalog=true;
                            if(options.isEmpty())renewOptions();
                            cloud.error("سرور فعلاً پیام قابل‌نمایشی ندارد؛ پیام‌های آماده روی گوشی در دسترس‌اند.");
                        }
                        cloud.catalogUpdated();
                    }catch(Exception ignored){cloud.error("پاسخ جست‌وجو معتبر نیست.");}
                }else{catalogNetworkFailed=true;cloud.error(error);usingOfflineCatalog=true;if(reset&&options.isEmpty())renewOptions();}
                renderCatalogResults();
            });
        },"delneveshte-catalog-browse").start();
    }
    CloudSync.Transport catalogTransport(CloudConfig cloud){return new CloudApi(cloud);}
    private String catalogNickname(){return "عزیزم";}
    private void appendOption(String raw){
        String text=CatalogText.render(raw,catalogNickname());if(text==null||options.contains(text))return;
        String[] parts=raw.split("\\n—",2);
        credits.put(text,parts.length>1?"شاعر: "+parts[1]:store.catalogCredit(raw));options.add(text);
    }
    private java.util.List<String> offlinePool(){
        java.util.List<String> result=new ArrayList<>();
        for(String raw:ToneCatalog.offline(this,activeCategory(),catalogNickname(),store.settings().tone)){
            String rendered=CatalogText.render(raw,catalogNickname());
            if(rendered!=null&&CatalogSearch.matches(rendered,activeQuery()))result.add(raw);
        }
        return result;
    }
    private void appendLocalPage(){
        java.util.List<String> rows=store.localCatalogPage(activeCategory(),activeQuery(),localCatalogOffset,catalogNickname(),store.settings().tone);
        if(store.localCatalogPage(activeCategory(),activeQuery(),0,catalogNickname(),store.settings().tone).isEmpty()){java.util.List<String> pool=offlinePool();int end=Math.min(pool.size(),localCatalogOffset+21);if(localCatalogOffset<end)rows=new ArrayList<>(pool.subList(localCatalogOffset,end));}
        catalogHasMore=rows.size()>20;
        for(int i=0;i<Math.min(20,rows.size());i++)appendOption(rows.get(i));localCatalogOffset+=20;
    }
    private void renewOptions(){
        options.clear();credits.clear();localCatalogOffset=0;
        if(emptySearch()){catalogHasMore=false;catalogCursor=null;refreshingSuggestions=false;return;}
        if(currentTab==4){catalogHasMore=false;refreshingSuggestions=false;return;
        }else appendLocalPage();
    }

    private void editText(String initial){
        LinearLayout content=dialogContent();EditText editor=input(initial,"متن پیام",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setMinLines(4);editor.setMaxLines(8);editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1200)});content.addView(editor,match());
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("ویرایش متن").setView(scrollDialog(content)).setNegativeButton("بستن",null).create();
        Runnable validate=()->{if(editor.getText().toString().trim().isEmpty())editor.setError("متن پیام را وارد کن");};
        content.addView(actionButton("ارسال همین حالا",R.drawable.ic_send,false,()->{validate.run();if(!editor.getText().toString().trim().isEmpty())confirmNewSend(editor.getText().toString().trim());}));
        content.addView(actionButton("ارسال در زمان تصادفی",R.drawable.ic_queue,true,()->{validate.run();String text=editor.getText().toString().trim();if(!text.isEmpty()&&enqueueMessage(text))dialog.dismiss();}));
        content.addView(actionButton("ارسال در زمان دلخواه",R.drawable.ic_clock,false,()->{validate.run();String text=editor.getText().toString().trim();if(!text.isEmpty())scheduleMessage(text,dialog::dismiss);}));
        activeEditor=editor;activeEditorDialog=dialog;
        dialog.setOnDismissListener(v->{if(activeEditorDialog==dialog){activeEditor=null;activeEditorDialog=null;}});dialog.show();
    }
    private void scheduleMessage(String text,Runnable saved){scheduleMessage(text,saved,null);}
    private void scheduleMessage(String text,Runnable saved,Message queued){
        ir.delneveshte.app.Settings snapshot=queued==null?snapshotForAction():null;if(queued==null&&snapshot==null)return;
        String sim=queued==null?simLabel(snapshot.subscriptionId):queued.simLabel;
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();long now=System.currentTimeMillis();
        long[] at={queued!=null&&queued.plannedAt>now?queued.plannedAt:java.time.Instant.ofEpochMilli(now).atZone(zone).plusHours(1).withSecond(0).withNano(0).toInstant().toEpochMilli()};
        LinearLayout content=dialogContent();content.addView(body(text,16));
        LinearLayout choices=horizontal();Button today=button("امروز",false,()->{}),tomorrow=button("فردا",false,()->{}),other=button("تاریخ دیگر",false,()->{});
        Button[] dates={today,tomorrow,other};for(Button choice:dates){choice.setTextSize(13);choice.setPadding(dp(4),dp(8),dp(4),dp(8));LinearLayout.LayoutParams lp=weighted();lp.setMarginEnd(dp(4));choices.addView(choice,lp);}content.addView(choices);
        TextView chosenDate=body("",13),summary=label("",14,ACCENT,true);content.addView(chosenDate);
        Button clock=button("",false,()->{});content.addView(clock);add(content,summary,8,0);
        Runnable update=()->{
            java.time.LocalDate day=java.time.Instant.ofEpochMilli(at[0]).atZone(zone).toLocalDate(),current=java.time.LocalDate.now(zone);
            int active=day.equals(current)?0:day.equals(current.plusDays(1))?1:2;
            for(int i=0;i<dates.length;i++){dates[i].setSelected(i==active);dates[i].setBackground(controlBackground(i==active?ACCENT:Color.WHITE,10,UiMetrics.BUTTON_INSET,i==active?0:LINE));dates[i].setTextColor(i==active?Color.WHITE:AppThemeTokens.ACCENT_INK);}
            chosenDate.setText(PersianDates.solar(day,zone));clock.setText("ساعت: "+PersianDates.clock(at[0],zone));summary.setText("زمان ارسال: "+formatDate(at[0]));
        };
        java.util.function.Consumer<java.time.LocalDate> choose=day->{java.time.LocalTime hour=java.time.Instant.ofEpochMilli(at[0]).atZone(zone).toLocalTime();at[0]=day.atTime(hour).atZone(zone).toInstant().toEpochMilli();update.run();};
        today.setOnClickListener(v->choose.accept(java.time.LocalDate.now(zone)));tomorrow.setOnClickListener(v->choose.accept(java.time.LocalDate.now(zone).plusDays(1)));
        other.setOnClickListener(v->showPersianDatePicker(java.time.Instant.ofEpochMilli(at[0]).atZone(zone).toLocalDate(),choose));
        clock.setOnClickListener(v->{java.time.ZonedDateTime time=java.time.Instant.ofEpochMilli(at[0]).atZone(zone);new TimePickerDialog(this,(view,h,m)->{at[0]=java.time.Instant.ofEpochMilli(at[0]).atZone(zone).toLocalDate().atTime(h,m).atZone(zone).toInstant().toEpochMilli();update.run();},time.getHour(),time.getMinute(),true).show();});update.run();
        content.addView(body("این زمان مستقل از بازه و تعداد ارسال تصادفی است. محدودیت باتری گوشی ممکن است باعث تأخیر شود.",12));
        if(!store.settings().enabled)content.addView(body("ارسال زمان‌دار متوقف است؛ برای اجرا، ارسال را در تنظیمات روشن کن.",12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(queued==null?"زمان ارسال":"ویرایش زمان ارسال").setView(scrollDialog(content)).setPositiveButton(queued==null?"زمان‌بندی پیام":"ذخیرهٔ زمان",null).setNegativeButton("انصراف",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            if(at[0]<=System.currentTimeMillis()){toast("زمانی در آینده انتخاب کن.");return;}
            try{
                if(queued==null){store.approveFixed(text,snapshot,sim,at[0]);SmsRuntime.reconcile(this);}
                else if(!SmsRuntime.rescheduleQueued(this,queued.id,queued.revision,at[0])){toast("وضعیت پیام تغییر کرده؛ صف را دوباره بررسی کن.");dialog.dismiss();render();return;}
                toast("زمان ارسال: "+formatDate(at[0]));dialog.dismiss();if(saved!=null)saved.run();
            }catch(RuntimeException error){toast("زمان‌بندی ذخیره نشد؛ تنظیمات را بررسی کن.");}
        }));dialog.show();
    }

    private void showPersianDatePicker(java.time.LocalDate initial,java.util.function.Consumer<java.time.LocalDate> onSelected){
        java.time.ZoneId zone=java.time.ZoneId.systemDefault();java.time.LocalDate minimum=java.time.LocalDate.now(zone);
        java.time.LocalDate[] selected={initial.isBefore(minimum)?minimum:initial};int[] month=PersianDates.fields(selected[0],zone);
        LinearLayout content=dialogContent(),header=horizontal(),grid=vertical();
        ImageButton previous=toolbarAction("ماه قبل",R.drawable.ic_back,()->{}),next=toolbarAction("ماه بعد",R.drawable.ic_back,()->{});next.setRotation(180);
        Button title=button("",false,()->{});header.addView(previous,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));LinearLayout.LayoutParams tp=weighted();tp.setMargins(dp(6),0,dp(6),0);header.addView(title,tp);header.addView(next,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));content.addView(header);
        TextView selection=body("",13);content.addView(selection);content.addView(grid);
        AlertDialog picker=new AlertDialog.Builder(this).setTitle("انتخاب تاریخ خورشیدی").setView(scrollDialog(content)).setPositiveButton("انتخاب تاریخ",null).setNegativeButton("انصراف",null).create();
        Runnable[] draw=new Runnable[1];draw[0]=()->{
            title.setText(PersianDates.MONTHS[month[1]-1]+" "+PersianDates.digits(month[0]));selection.setText("انتخاب‌شده: "+PersianDates.solar(selected[0],zone));grid.removeAllViews();
            java.time.LocalDate first=PersianDates.date(month[0],month[1],1,zone);int length=PersianDates.monthLength(month[0],month[1],zone),offset=(first.getDayOfWeek().getValue()+1)%7;
            int[] current=PersianDates.fields(java.time.LocalDate.now(zone),zone);previous.setEnabled(first.isAfter(PersianDates.date(current[0],current[1],1,zone)));previous.setAlpha(previous.isEnabled()?1f:.35f);
            LinearLayout week=horizontal();String[] shortNames={"ش","ی","د","س","چ","پ","ج"},names={"شنبه","یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه"};
            for(int i=0;i<7;i++){TextView day=label(shortNames[i],12,MUTED,true);day.setContentDescription(names[i]);day.setGravity(Gravity.CENTER);week.addView(day,new LinearLayout.LayoutParams(0,dp(32),1));}grid.addView(week);
            for(int start=0;start<offset+length;start+=7){LinearLayout row=horizontal();
                for(int col=0;col<7;col++){int number=start+col-offset+1;
                    if(number<1||number>length){row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(UiMetrics.TOUCH),1));continue;}
                    java.time.LocalDate date=PersianDates.date(month[0],month[1],number,zone);boolean active=date.equals(selected[0]);
                    Button cell=button(PersianDates.digits(number),active,()->{selected[0]=date;draw[0].run();});cell.setPadding(0,dp(6),0,dp(6));cell.setBackground(iconBackground(active?ACCENT:Color.TRANSPARENT,UiMetrics.ICON_SURFACE));cell.setMinWidth(0);cell.setMinimumWidth(0);cell.setSelected(active);cell.setContentDescription(PersianDates.solar(date,zone));cell.setEnabled(!date.isBefore(java.time.LocalDate.now(zone)));cell.setAlpha(cell.isEnabled()?1f:.30f);row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
                }grid.addView(row);
            }
        };
        previous.setOnClickListener(v->{month[1]--;if(month[1]<1){month[1]=12;month[0]--;}draw[0].run();});next.setOnClickListener(v->{month[1]++;if(month[1]>12){month[1]=1;month[0]++;}draw[0].run();});
        title.setContentDescription("انتخاب ماه و سال خورشیدی");title.setOnClickListener(v->{
            LinearLayout fields=horizontal();android.widget.NumberPicker years=new android.widget.NumberPicker(this),months=new android.widget.NumberPicker(this);int year=PersianDates.fields(java.time.LocalDate.now(zone),zone)[0];
            years.setMinValue(year);years.setMaxValue(Math.max(year+100,month[0]));years.setValue(month[0]);years.setWrapSelectorWheel(false);years.setFormatter(value->PersianDates.digits(value));years.setContentDescription("سال خورشیدی");
            months.setMinValue(1);months.setMaxValue(12);months.setDisplayedValues(PersianDates.MONTHS);months.setValue(month[1]);months.setWrapSelectorWheel(false);months.setContentDescription("ماه خورشیدی");fields.addView(months,new LinearLayout.LayoutParams(0,-2,1));fields.addView(years,new LinearLayout.LayoutParams(0,-2,1));
            AlertDialog chooser=new AlertDialog.Builder(this).setTitle("ماه و سال خورشیدی").setView(fields).setPositiveButton("نمایش ماه",null).setNegativeButton("انصراف",null).create();
            chooser.setOnShowListener(w->chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{fields.clearFocus();int[] today=PersianDates.fields(java.time.LocalDate.now(zone),zone);if(PersianDates.date(years.getValue(),months.getValue(),1,zone).isBefore(PersianDates.date(today[0],today[1],1,zone))){toast("ماه جاری یا آینده را انتخاب کن.");return;}month[0]=years.getValue();month[1]=months.getValue();draw[0].run();chooser.dismiss();}));chooser.show();
        });
        picker.setOnShowListener(v->picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(selected[0].isBefore(java.time.LocalDate.now(zone))){toast("امروز یا روزی در آینده را انتخاب کن.");return;}onSelected.accept(selected[0]);picker.dismiss();}));draw[0].run();picker.show();
    }

    private ir.delneveshte.app.Settings snapshotForAction() {
        ir.delneveshte.app.Settings snapshot = store.settings().copy();
        if (!snapshot.valid()) {
            toast("ابتدا شمارهٔ همسرت و سیم‌کارت را در تنظیمات ذخیره کن.");
            return null;
        }
        if (!SmsRuntime.hasSimPermission(this)) { explainSimPermission(); return null; }
        if (!isActiveSim(snapshot.subscriptionId)) {
            toast("سیم‌کارت انتخابی در دسترس نیست؛ تنظیمات را بررسی کن.");
            return null;
        }
        return snapshot;
    }

    private boolean enqueueMessage(String text) {
        ir.delneveshte.app.Settings snapshot = snapshotForAction();
        if (snapshot == null) return false;
        final long queuedId;
        try {
            queuedId=store.approve(text, snapshot, simLabel(snapshot.subscriptionId));
        } catch (RuntimeException error) {
            toast("پیام ذخیره نشد؛ دوباره تلاش کن.");
            return false;
        }
        if (store.settings().enabled) {
            try {
                SmsRuntime.reconcile(this);
                toast("به صف اضافه شد؛ زمان ارسال: "+formatDate(store.get(queuedId).plannedAt));
            } catch (RuntimeException error) {
                toast("به صف اضافه شد؛ زمان‌بندی نیاز به بررسی دارد.");
            }
        } else {
            toast("زمان ارسال: "+formatDate(store.get(queuedId).plannedAt)+"؛ ارسال خودکار متوقف است.");
        }
        return true;
    }

    private void confirmNewSend(String text) {
        ir.delneveshte.app.Settings snapshot = snapshotForAction();
        if (snapshot == null) return;
        if (!SmsRuntime.hasSendPermission(this)) { explainSmsPermission(); return; }
        showSendConfirmation(text, snapshot.recipient, snapshot.subscriptionId,
                simLabel(snapshot.subscriptionId), () -> {SmsRuntime.sendNewNow(this,text,snapshot,simLabel(snapshot.subscriptionId));if(activeEditorDialog!=null)activeEditorDialog.dismiss();});
    }

    private void queuePage() {
        store.ensureRandomSchedules(System.currentTimeMillis());
        List<Message> queue = store.queue();
        heading("صف پیام‌های تو", fa(queue.size()) + " پیام تأییدشده");
        boolean enabled = store.settings().enabled;
        LinearLayout info = card(PALE);
        info.addView(label(enabled ? "ارسال خودکار روشن است" : "ارسال خودکار متوقف است",
                16, ACCENT, true));
        info.addView(body(enabled?"برای تغییر زمان یا ارسال فوری، روی پیام بزن.":"زمان‌ها محفوظ‌اند. برای فعال‌کردن ارسال، به تنظیمات برو.",13));
        info.setOnClickListener(v->selectTab(3));
        page.addView(info);
        if (queue.isEmpty()) {
            empty("صف، منتظر حرف‌های توست", "چند پیشنهاد را انتخاب و تأیید کن. بدون متن تأییدشده، چیزی ارسال نمی‌شود.",
                    "دیدن پیشنهادها", () -> selectTab(0));
            return;
        }
        for (Message message : queue) {
            LinearLayout c = messageCard();
            c.addView(label("FIXED".equals(message.sendMode)?"زمان دلخواه: "+formatDate(message.requestedAt):message.plannedAt>0?"زمان تصادفی: "+formatDate(message.plannedAt):"تنظیمات زمان‌بندی را کامل کن",12,ACCENT,true));
            TextView messageText=body(message.body,16);
            c.setOnClickListener(v->queueMessageMenu(c,message));c.setOnLongClickListener(v->{queueMessageMenu(c,message);return true;});c.setOnContextClickListener(v->{queueMessageMenu(c,message);return true;});
            messageText.setOnClickListener(v->queueMessageMenu(c,message));messageText.setOnLongClickListener(v->{queueMessageMenu(c,message);return true;});messageText.setOnContextClickListener(v->{queueMessageMenu(c,message);return true;});
            c.addView(messageText);
            LinearLayout actions=horizontal();actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            ImageButton remove=compactAction("حذف از صف",R.drawable.ic_delete,()->cancelMessage(message));actions.addView(remove,new LinearLayout.LayoutParams(dp(UiMetrics.TOUCH),dp(UiMetrics.TOUCH)));c.addView(actions);
            page.addView(c);
        }
    }

    private void queueMessageMenu(View anchor,Message message){
        android.widget.PopupMenu menu=new android.widget.PopupMenu(this,anchor);
        menu.getMenu().add(0,1,0,"ویرایش زمان ارسال").setIcon(R.drawable.ic_clock);
        menu.getMenu().add(0,2,1,"ارسال همین حالا").setIcon(R.drawable.ic_send);
        menu.getMenu().add(0,3,2,"حذف از صف").setIcon(R.drawable.ic_delete);
        menu.getMenu().add(0,4,3,"کپی متن").setIcon(R.drawable.ic_copy);
        menu.getMenu().add(0,5,4,"اطلاعات ارسال").setIcon(R.drawable.ic_info);
        for(int i=0;i<menu.getMenu().size();i++){android.graphics.drawable.Drawable icon=menu.getMenu().getItem(i).getIcon();if(icon!=null)icon.mutate().setTint(i==0?AppThemeTokens.COMPLEMENT_INK:ACCENT);}
        if(Build.VERSION.SDK_INT>=29)menu.setForceShowIcon(true);
        menu.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 1:scheduleMessage(message.body,this::render,message);break;
                case 2:confirmSendNow(message);break;
                case 3:cancelMessage(message);break;
                case 5:new AlertDialog.Builder(this).setTitle("اطلاعات ارسال").setMessage("زمان ارسال: "+formatDate(message.plannedAt)+"\nشماره: "+message.recipient+"\n"+message.simLabel+"\nاین پیام با شماره و سیم‌کارت زمان تأیید ارسال می‌شود.").setPositiveButton("بستن",null).show();break;
                case 4:((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("پیام",message.body));toast("متن کپی شد.");break;
                default:return false;
            }return true;
        });menu.show();
    }

    private void cancelMessage(Message message) {
        new AlertDialog.Builder(this).setTitle("حذف از صف؟")
                .setMessage("این پیام دیگر برای ارسال خودکار استفاده نمی‌شود.")
                .setPositiveButton("حذف", (d, which) -> {
                    if (!store.cancel(message.id)) toast("این پیام قبلاً وارد مرحلهٔ ارسال شده است.");
                    if (store.settings().enabled) SmsRuntime.reconcile(this);
                    render();
                }).setNegativeButton("نگه‌دار", null).show();
    }

    private void confirmSendNow(Message message) {
        if (!SmsRuntime.hasSendPermission(this)) {
            explainSmsPermission();
            return;
        }
        if (!SmsRuntime.hasSimPermission(this)) {
            explainSimPermission();
            return;
        }
        if (!isActiveSim(message.subscriptionId)) {
            toast("سیم‌کارت تأییدشدهٔ این پیام در دسترس نیست.");
            return;
        }
        showSendConfirmation(message.body, message.recipient, message.subscriptionId, message.simLabel,
                () -> SmsRuntime.sendApprovedNow(this, message.id));
    }

    private void showSendConfirmation(String text, String recipient, int subscriptionId,
                                      String sim, Runnable send) {
        LinearLayout content = dialogContent();
        content.addView(body("این پیام همین حالا از سیم‌کارت انتخابی ارسال می‌شود، حتی اگر ارسال خودکار خاموش باشد.", 14));
        content.addView(phoneLabel(recipient));
        content.addView(body(sim, 14));
        content.addView(divider());
        content.addView(body(text, 17));
        content.addView(body(partsHint(text, subscriptionId), 12));
        new AlertDialog.Builder(this).setTitle("تأیید ارسال فوری")
                .setView(scrollDialog(content))
                .setPositiveButton("ارسال پیامک", (d, which) -> {
                    try {
                        send.run();
                        toast("نتیجهٔ ارسال را در سابقه ببین.");
                        selectTab(2);
                    } catch (RuntimeException error) {
                        toast("ارسال انجام نشد؛ مجوزها و سیم‌کارت را بررسی کن.");
                        render();
                    }
                }).setNegativeButton("انصراف", null).show();
    }

    private void historyPage() {
        heading("سابقهٔ پیام‌ها", "وضعیت واقعی هر درخواست ارسال");
        addPage(body("«ارسال به شبکه تأیید شد» به معنی تحویل یا خوانده‌شدن پیام نیست. پیام با نتیجهٔ نامشخص خودکار تکرار نمی‌شود.", 13), 0, 16);
        List<Message> history = store.history();
        if (history.isEmpty()) {
            empty("هنوز پیامی ارسال نشده", "بعد از نخستین ارسال، نتیجهٔ آن را اینجا می‌بینی.",
                    "دیدن صف ارسال", () -> selectTab(1));
            return;
        }
        for (Message message : history) {
            LinearLayout c = messageCard();
            c.addView(label(statusTitle(message.status), 14,
                    "SENT".equals(message.status) ? GOOD : ACCENT, true));
            c.addView(body(message.body, 16));
            c.addView(divider());
            c.addView(phoneLabel(message.recipient));
            c.addView(label(message.simLabel, 12, MUTED, false));
            long time = message.submittedAt > 0 ? message.submittedAt : message.approvedAt;
            c.addView(label(formatDate(time), 12, MUTED, false));
            if (message.detail != null && !message.detail.trim().isEmpty())
                c.addView(body(message.detail, 13));
            if ("FAILED".equals(message.status) && message.partCount > 1 && message.submittedAt > 0)
                c.addView(body("این متن چندبخشی بوده است؛ ممکن است بخشی از آن ارسال شده باشد. تکرار خودکار انجام نمی‌شود.", 12));
            page.addView(c);
        }
        addPage(button("تازه‌سازی وضعیت", false, this::render), 0, 0);
    }

    private void settingsPage() {
        heading("تنظیمات ارسال", "تعداد و بازهٔ زمانی، به انتخاب تو");
        LinearLayout toneCard=card(Color.WHITE);
        toneCard.addView(label("لحن پیام‌ها",15,ACCENT,true));
        boolean formal="formal".equals(store.settings().tone);android.widget.RadioGroup toneChoices=new android.widget.RadioGroup(this);toneChoices.setOrientation(android.widget.RadioGroup.HORIZONTAL);toneChoices.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);toneChoices.setContentDescription("لحن پیام‌ها");
        android.widget.RadioButton colloquial=new android.widget.RadioButton(this),formalButton=new android.widget.RadioButton(this);
        colloquial.setText("محاوره‌ای");colloquial.setTag("colloquial");formalButton.setText("رسمی");formalButton.setTag("formal");
        android.content.res.ColorStateList radioTint=new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,MUTED});
        for(android.widget.RadioButton choice:new android.widget.RadioButton[]{colloquial,formalButton}){choice.setId(View.generateViewId());choice.setTextSize(14);choice.setTypeface(mediumFont);choice.setTextColor(INK);choice.setButtonTintList(radioTint);choice.setMinHeight(dp(UiMetrics.TOUCH));choice.setPadding(dp(6),0,dp(6),0);choice.setGravity(Gravity.CENTER_VERTICAL);toneChoices.addView(choice,new android.widget.RadioGroup.LayoutParams(0,-2,1));}
        toneChoices.check(formal?formalButton.getId():colloquial.getId());
        toneChoices.setOnCheckedChangeListener((group,checkedId)->{
            android.widget.RadioButton selected=group.findViewById(checkedId);if(selected==null)return;String value=(String)selected.getTag();
            store.saveTone(value);draft.tone=value;
            handler.removeCallbacks(liveSearch);messageSearch.cancel();browseGeneration++;catalogCursor=null;catalogRequested=false;refreshingSuggestions=false;renewOptions();
        });
        toneCard.addView(toneChoices,match());
        toneCard.addView(body("شعرها در هر دو لحن با متن اصلی شاعر نمایش داده می‌شوند.",12));page.addView(toneCard);
        addPage(settingsLink("بررسی به‌روزرسانی برنامه",R.drawable.ic_info,()->checkForUpdates(true)),8,0);
        LinearLayout destination = card(Color.WHITE);
        destination.addView(label("مقصد پیامک",15,ACCENT,true));
        destination.addView(fieldTitle("شمارهٔ همسرت"));
        EditText phone = input(draft.recipient, "مثلاً ‎+98912…", InputType.TYPE_CLASS_PHONE);
        phone.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        phone.setTextDirection(View.TEXT_DIRECTION_LTR);
        phone.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        phone.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        watch(phone, value -> draft.recipient = asciiDigits(value).replace(" ", ""));
        destination.addView(phone, match());
        destination.addView(fieldTitle("سیم‌کارت ارسال‌کننده"));
        destination.addView(actionButton(simLabel(draft.subscriptionId),R.drawable.ic_settings,false,this::showSimPicker));
        destination.addView(body("شماره و سیم‌کارت هر پیام هنگام تأیید آن ذخیره می‌شوند. تغییر تنظیمات، مقصد پیام‌های قبلی را عوض نمی‌کند.", 12));
        page.addView(destination);

        LinearLayout timing = card(Color.WHITE);
        timing.addView(label("زمان‌بندی تصادفی",15,ACCENT,true));
        timing.addView(fieldTitle("تعداد پیام در روز (۱ تا ۲۰)"));
        EditText count = input(fa(draft.dailyCount), "تعداد پیام", InputType.TYPE_CLASS_NUMBER);
        count.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2)});
        watch(count, value -> {
            try { draft.dailyCount = Integer.parseInt(asciiDigits(value)); }
            catch (NumberFormatException ignored) { draft.dailyCount = 0; }
        });
        timing.addView(count, match());
        LinearLayout times = horizontal();
        times.addView(timeButton(true), weighted());
        LinearLayout.LayoutParams endLp = weighted();
        endLp.setMarginStart(dp(10));
        times.addView(timeButton(false), endLp);
        add(timing, times, 14, 0);
        timing.addView(body("بازه باید در یک روز باشد و ساعت پایان بعد از شروع قرار بگیرد. زمان دقیق هر روز تغییر می‌کند؛ تعداد ارسال از سقف انتخابی تو بیشتر نمی‌شود.", 12));
        page.addView(timing);
        addPage(button("ذخیرهٔ تنظیمات",true,this::saveDraft),0,16);

        LinearLayout control = card(PALE);
        boolean active = store.settings().enabled;
        control.addView(label(active ? "ارسال خودکار فعال است" : "شروع، هر وقت تو بخواهی", 17, ACCENT, true));
        control.addView(body("فقط پیام‌های تأییدشده ارسال می‌شوند. اگر صف خالی باشد، برنامه پیام تازه‌ای را خودسرانه ارسال نمی‌کند.", 14));
        if (active) {
            String readiness = SmsRuntime.readiness(this);
            if (readiness != null && !readiness.isEmpty()) control.addView(body(readiness, 14));
        }
        control.addView(settingsLink(active ? "توقف ارسال خودکار" : "فعال‌کردن ارسال خودکار",R.drawable.ic_send, () -> {
            if (store.settings().enabled) {
                SmsRuntime.pause(this);
                store.requeueFuture();
                draft.enabled = false;
                toast("ارسال خودکار متوقف شد");
                render();
            } else enableSchedule();
        }));
        addPage(control, 16, 0);

        LinearLayout permissions = card(Color.WHITE);
        permissions.addView(label("مجوزها و آماده‌بودن گوشی", 17, INK, true));
        permissions.addView(body((SmsRuntime.hasSendPermission(this) ? "✓" : "○") + "  اجازهٔ ارسال پیامک\n"
                + (SmsRuntime.hasSimPermission(this) ? "✓" : "○") + "  دسترسی برای انتخاب سیم‌کارت", 14));
        if (!SmsRuntime.hasSendPermission(this))
            permissions.addView(settingsLink("اجازهٔ ارسال پیامک",R.drawable.ic_send,this::explainSmsPermission));
        if (!SmsRuntime.hasSimPermission(this))
            add(permissions, settingsLink("اجازهٔ انتخاب سیم‌کارت",R.drawable.ic_settings,this::explainSimPermission), 8, 0);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(permissions, settingsLink("فعال‌کردن اعلان‌ها (اختیاری)",R.drawable.ic_info,
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS)), 8, 0);
        }
        permissions.addView(body("این اپ برای کار خودش نیازی به خواندن پیامک‌های شخصی تو ندارد.", 12));
        page.addView(permissions);

        LinearLayout phoneHelp = card(Color.WHITE);
        phoneHelp.addView(label("اجرای پس‌زمینه و باتری",17,INK,true));
        phoneHelp.addView(body("برای اجرای زمان‌بندی، اجازهٔ فعالیت پس‌زمینهٔ دل‌نوشته را بررسی کن و محدودیت مصرف باتری برنامه را بردار. بعضی گوشی‌ها گزینهٔ «شروع خودکار» هم دارند. نام و مسیر این گزینه‌ها بسته به سازنده و نسخهٔ اندروید متفاوت است.",14));
        phoneHelp.addView(settingsLink("تنظیمات برنامه",R.drawable.ic_settings,this::openAppSettings));
        add(phoneHelp,settingsLink("تنظیم مصرف باتری",R.drawable.ic_clock,this::openBatterySettings),8,0);
        phoneHelp.addView(body("پیامک‌ها از سیم‌کارت انتخابی ارسال می‌شوند. برای دیدن آن‌ها، پیام‌رسان پیش‌فرض گوشی را بررسی کن؛ می‌توانی از Google Messages یا پیام‌رسان گوشی استفاده کنی.",13));
        add(phoneHelp,settingsLink("پیام‌رسان پیش‌فرض",R.drawable.ic_home,()->openSystem(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))),8,0);
        page.addView(phoneHelp);
        addPage(settingsLink("درباره و مجوزها",R.drawable.ic_info,this::showLicenses),0,8);
        addPage(body("دل‌نوشته · " + BuildConfig.VERSION_NAME + "", 12), 0, 0);
    }

    private void checkForUpdates(boolean manual){
        AppUpdater.check(this,(release,error)->{
            if(isFinishing()||isDestroyed())return;
            if(error!=null){if(manual)toast("بررسی به‌روزرسانی ممکن نشد؛ اتصال اینترنت را بررسی کن.");return;}
            if(release==null){if(manual)toast("برنامه به‌روز است.");return;}
            new AlertDialog.Builder(this).setTitle("نسخهٔ تازه آماده است")
                    .setMessage("نسخهٔ "+fa(release.version)+" منتشر شده. فایل نصب از گیت‌هاب دریافت و امضای آن بررسی می‌شود؛ نصب نهایی را خود اندروید از تو تأیید می‌گیرد.")
                    .setNegativeButton("بعداً",(d,w)->{}).setPositiveButton("دریافت به‌روزرسانی",(d,w)->{try{AppUpdater.download(this,release);updatePolls=0;handler.removeCallbacks(updatePoll);handler.postDelayed(updatePoll,2000);toast("دریافت به‌روزرسانی آغاز شد.");}catch(Exception e){toast("شروع دریافت به‌روزرسانی ممکن نشد.");}}).show();
        });
    }

    private void showLicenses(){
        LinearLayout content=dialogContent();
        content.addView(body("آیکن‌ها: Font Awesome Free 7.3.1، اثر Fonticons, Inc.\nآیکن‌های SVG با حفظ مسیرها به VectorDrawable اندروید تبدیل شده‌اند؛ رنگ آن‌ها با تم برنامه هماهنگ می‌شود.\nفونت فارسی: وزیرمتن.",14));
        for(int resource:new int[]{R.raw.fontawesome_license,R.raw.vazirmatn_license}){
            try(java.io.InputStream input=getResources().openRawResource(resource);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int read;while((read=input.read(buffer))!=-1)bytes.write(buffer,0,read);
                TextView license=body(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8),12);license.setTextIsSelectable(true);content.addView(license);
            }catch(java.io.IOException error){content.addView(body("اطلاعات منابع در مستندات محصول ثبت شده است.",12));}
        }
        new AlertDialog.Builder(this).setTitle("درباره و مجوزها").setView(scrollDialog(content)).setPositiveButton("بستن",null).show();
    }

    private Button timeButton(boolean start) {
        int value = start ? draft.startMinute : draft.endMinute;
        return button((start ? "از ساعت " : "تا ساعت ") + time(value), false, () -> {
            new TimePickerDialog(this, (picker, hour, minute) -> {
                if (start) draft.startMinute = hour * 60 + minute;
                else draft.endMinute = hour * 60 + minute;
                render();
            }, value / 60, value % 60, true).show();
        });
    }

    private void saveDraft() {
        draft.recipient = asciiDigits(draft.recipient).trim().replace(" ", "");
        String issue = validate(draft);
        if (!issue.isEmpty()) { toast(issue); return; }
        ir.delneveshte.app.Settings previous = store.settings();
        draft.enabled = previous.enabled;
        store.saveSettings(draft.copy());
        store.ensureRandomSchedules(System.currentTimeMillis());
        store.requeueFuture();
        if (draft.enabled) SmsRuntime.reconcile(this);
        else SmsRuntime.pause(this);
        boolean changedDestination = !previous.recipient.equals(draft.recipient)
                || previous.subscriptionId != draft.subscriptionId;
        renewOptions();
        toast("تنظیمات ذخیره شد");
        render();
        if (changedDestination && store.approvedCount() > 0) {
            new AlertDialog.Builder(this).setTitle("پیام‌های قبلی مقصد خودشان را دارند")
                    .setMessage("پیام‌های تأییدشدهٔ قبلی با همان شماره و سیم‌کارت زمان تأیید باقی می‌مانند. اگر می‌خواهی مقصدشان عوض شود، آن‌ها را از صف حذف کن و دوباره تأیید کن.")
                    .setPositiveButton("دیدن صف", (d, which) -> selectTab(1))
                    .setNegativeButton("متوجه شدم", null).show();
        }
    }

    private void enableSchedule() {
        ir.delneveshte.app.Settings saved = store.settings();
        if (!sameSettings(draft, saved)) {
            toast("ابتدا تغییرات را با «ذخیرهٔ تنظیمات» ثبت کن.");
            return;
        }
        String problem = validate(saved);
        if (!problem.isEmpty()) { toast(problem); return; }
        if (!SmsRuntime.hasSendPermission(this)) { explainSmsPermission(); return; }
        if (!SmsRuntime.hasSimPermission(this)) { explainSimPermission(); return; }
        String readiness = SmsRuntime.readiness(this);
        if (readiness != null && !readiness.isEmpty()) { toast(readiness); return; }
        saved.enabled = true;
        store.saveSettings(saved);
        draft.enabled = true;
        SmsRuntime.reconcile(this);
        toast(store.approvedCount() == 0 ? "فعال شد؛ فعلاً صف خالی است و پیامی ارسال نمی‌شود."
                : "ارسال تصادفی پیام‌های تأییدشده فعال شد");
        render();
    }

    private String validate(ir.delneveshte.app.Settings value) {
        if (value.recipient == null || value.recipient.trim().isEmpty()) return "شمارهٔ همسرت را وارد کن.";
        if (!value.recipient.matches("\\+?[0-9]{7,15}")) return "شماره را فقط با رقم و در صورت نیاز + وارد کن.";
        if (value.dailyCount < 1 || value.dailyCount > 20) return "تعداد روزانه باید بین ۱ و ۲۰ باشد.";
        if (value.startMinute < 0 || value.endMinute > 1439 || value.startMinute >= value.endMinute)
            return "ساعت پایان باید بعد از شروع و در همان روز باشد.";
        if (value.subscriptionId < 0) return "سیم‌کارت ارسال‌کننده را انتخاب کن.";
        if (!value.valid()) return "شماره، سیم‌کارت و بازهٔ زمانی را بررسی کن.";
        return "";
    }

    private boolean sameSettings(ir.delneveshte.app.Settings a, ir.delneveshte.app.Settings b) {
        return a.recipient.equals(b.recipient) && a.nickname.equals(b.nickname) && a.tone.equals(b.tone)
                && a.dailyCount == b.dailyCount && a.startMinute == b.startMinute
                && a.endMinute == b.endMinute && a.subscriptionId == b.subscriptionId;
    }

    private void showSimPicker() {
        if (!SmsRuntime.hasSimPermission(this)) { explainSimPermission(); return; }
        List<SmsRuntime.Sim> sims = availableSims();
        if (sims.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("سیم‌کارتی در دسترس نیست")
                    .setMessage("سیم‌کارت‌ها باید فعال و دسترسی تلفن مجاز باشد. وضعیت سیم‌کارت را در تنظیمات گوشی بررسی کن.")
                    .setPositiveButton("تنظیمات برنامه", (d, which) -> openAppSettings())
                    .setNegativeButton("باشه", null).show();
            return;
        }
        String[] names = new String[sims.size()];
        int checked = -1;
        for (int i = 0; i < sims.size(); i++) {
            names[i] = sims.get(i).label;
            if (sims.get(i).id == draft.subscriptionId) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("ارسال از کدام سیم‌کارت؟")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    draft.subscriptionId = sims.get(which).id;
                    dialog.dismiss();
                    render();
                }).setNegativeButton("انصراف", null).show();
    }

    private List<SmsRuntime.Sim> availableSims() {
        try { return SmsRuntime.sims(this); }
        catch (RuntimeException error) { return new ArrayList<>(); }
    }

    private boolean isActiveSim(int subscriptionId) {
        for (SmsRuntime.Sim sim : availableSims()) if (sim.id == subscriptionId) return true;
        return false;
    }

    private String simLabel(int subscriptionId) {
        for (SmsRuntime.Sim sim : availableSims()) if (sim.id == subscriptionId) return sim.label;
        return subscriptionId < 0 ? "انتخاب سیم‌کارت" : "سیم‌کارت ذخیره‌شده؛ برای بررسی انتخاب کن";
    }

    private void explainSimPermission() {
        new AlertDialog.Builder(this).setTitle("انتخاب سیم‌کارت")
                .setMessage("اجازهٔ دسترسی تلفن فقط برای دیدن سیم‌کارت‌های فعال و انتخاب سیم‌کارت ارسال‌کننده لازم است. پیامک‌ها و تماس‌هایت خوانده نمی‌شوند.")
                .setPositiveButton("ادامه", (d, which) -> requestPermissionOrSettings(
                        Manifest.permission.READ_PHONE_STATE, REQUEST_SIM))
                .setNegativeButton("فعلاً نه", null).show();
    }

    private void explainSmsPermission() {
        new AlertDialog.Builder(this).setTitle("اجازهٔ ارسال پیامک")
                .setMessage("برای ارسال از سیم‌کارت خودت، اجازهٔ ارسال پیامک لازم است. فقط متن، شماره و سیم‌کارتی که خودت تأیید کرده‌ای استفاده می‌شود. دادن این مجوز به‌تنهایی پیامی ارسال نمی‌کند.")
                .setPositiveButton("ادامه", (d, which) -> requestPermissionOrSettings(
                        Manifest.permission.SEND_SMS, REQUEST_SMS))
                .setNegativeButton("فعلاً نه", null).show();
    }

    private void requestPermissionOrSettings(String permission, int request) {
        String marker = "asked_" + permission;
        boolean asked = getPreferences(MODE_PRIVATE).getBoolean(marker, false);
        if (asked && !shouldShowRequestPermissionRationale(permission)
                && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this).setTitle("مجوز را در تنظیمات بررسی کن")
                    .setMessage("اگر اندروید درخواست مجوز را دوباره نشان نمی‌دهد، از بخش مجوزهای همین برنامه بررسی کن. مجوز پیامک ممکن است بسته به روش نصب و محدودیت‌های دستگاه در دسترس نباشد.")
                    .setPositiveButton("تنظیمات برنامه", (d, which) -> openAppSettings())
                    .setNegativeButton("باشه", null).show();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean(marker, true).apply();
        requestPermissions(new String[]{permission}, request);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean allowed = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        SmsRuntime.reconcile(this);
        render();
        if (requestCode == REQUEST_SIM && allowed) showSimPicker();
        else if (requestCode == REQUEST_SMS && allowed)
            toast(store.settings().enabled?"مجوز ثبت شد؛ پیام‌های تأییدشده پس از تکمیل تنظیمات، طبق زمان‌بندی ارسال می‌شوند.":"مجوز ثبت شد؛ ارسال خودکار همچنان متوقف است.");
        else if (!allowed && requestCode != REQUEST_NOTIFICATIONS)
            toast("مجوز داده نشد؛ تا دریافت مجوز، پیامکی ارسال نمی‌شود.");
    }

    private void openAppSettings() {
        openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openBatterySettings(){
        Intent intent=new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        try{startActivity(intent);}catch(ActivityNotFoundException|SecurityException error){openAppSettings();}
    }

    private void openSystem(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) { toast("این بخش را از تنظیمات گوشی باز کن."); }
        catch (SecurityException error) { toast("این بخش را از تنظیمات گوشی باز کن."); }
    }

    private String partsHint(String text, int subscriptionId) {
        try {
            int parts = SmsManager.getSmsManagerForSubscriptionId(subscriptionId).divideMessage(text).size();
            return "طول متن: حدود " + fa(parts) + " بخش پیامک؛ هزینه طبق تعرفهٔ سیم‌کارت محاسبه می‌شود.";
        } catch (RuntimeException error) { return "هزینهٔ ارسال طبق تعرفهٔ پیامک سیم‌کارت توست."; }
    }

    private String statusTitle(String status) {
        if ("SENT".equals(status)) return "✓  ارسال به شبکه تأیید شد";
        if ("SENDING".equals(status)) return "◷  در انتظار نتیجهٔ شبکه";
        if ("FAILED".equals(status)) return "ارسال ناموفق";
        if ("IMMEDIATE".equals(status)) return "در انتظار ارسال فوری";
        if ("UNCERTAIN".equals(status)) return "نتیجه نامشخص؛ بدون تکرار خودکار";
        if ("CANCELLED".equals(status)) return "حذف‌شده از صف";
        return status;
    }

    private void heading(String title, String subtitle) {
        TextView caption=label(subtitle,13,MUTED,false);caption.setPadding(dp(18),dp(2),dp(18),dp(12));page.addView(caption);
    }

    private void empty(String title, String text, String action, Runnable task) {
        LinearLayout c = card(Color.WHITE);
        TextView icon = label("♡", 32, ACCENT, false);
        icon.setGravity(Gravity.CENTER);
        c.addView(icon);
        TextView t = label(title, 17, INK, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        TextView b = body(text, 14);
        b.setGravity(Gravity.CENTER);
        add(c, b, 8, 16);
        c.addView(button(action, true, task));
        page.addView(c);
    }

    private TextView fieldTitle(String title) {
        TextView view = label(title, 13, MUTED, true);
        view.setTextColor(AppThemeTokens.ACCENT_INK);view.setPadding(0,dp(10),0,dp(4));
        return view;
    }

    private TextView phoneLabel(String number) {
        TextView view = label(number == null ? "" : number, 15, INK, true);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        view.setGravity(Gravity.RIGHT);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return view;
    }

    private LinearLayout card(int color) {
        LinearLayout view = vertical();
        view.setPadding(dp(14),dp(12),dp(14),dp(12));
        view.setBackground(shape(color,UiMetrics.CARD_RADIUS,0));
        LinearLayout.LayoutParams lp = match();
        lp.bottomMargin = dp(12);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(size);
        view.setTextColor(color==ACCENT?AppThemeTokens.ACCENT_INK:color);
        view.setTypeface(bold?mediumFont:regularFont);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setGravity(Gravity.RIGHT);
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView body(String text, int size) {
        TextView view = label(text,size,size>=16?INK:MUTED,false);
        view.setLineSpacing(dp(1),1.12f);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setSingleLine(false);view.setMaxLines(Integer.MAX_VALUE);view.setEllipsize(null);
        view.setTypeface(mediumFont);
        view.setTextColor(primary ? Color.WHITE : AppThemeTokens.ACCENT_INK);
        view.setMinHeight(dp(UiMetrics.TOUCH));
        view.setMinimumHeight(dp(UiMetrics.TOUCH));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(controlBackground(primary?ACCENT:Color.TRANSPARENT,10,UiMetrics.BUTTON_INSET,primary?0:LINE));
        view.setStateListAnimator(null);
        view.setContentDescription(title);
        view.setLayoutParams(match());
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private Button actionButton(String title, int icon, boolean primary, Runnable action) {
        Button view = button(title, primary, action);
        setButtonIcon(view, icon, primary ? Color.WHITE : ACCENT);
        return view;
    }

    private Button settingsLink(String title,int icon,Runnable action){
        Button row=actionButton(title,icon,false,action);row.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.setBackground(controlBackground(Color.TRANSPARENT,8,UiMetrics.BUTTON_INSET,0));row.setPadding(dp(4),dp(8),dp(4),dp(8));row.setCompoundDrawablePadding(dp(10));return row;
    }

    private void setButtonIcon(Button view, int icon, int color) {
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(UiMetrics.CARD_ICON), dp(UiMetrics.CARD_ICON));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(6));
    }

    private EditText input(String text, String hint, int type) {
        EditText view = new EditText(this);
        view.setTextSize(16);
        view.setTypeface(regularFont);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setInputType(type);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setGravity(Gravity.RIGHT | Gravity.TOP);
        android.graphics.drawable.StateListDrawable fieldBackground=new android.graphics.drawable.StateListDrawable();fieldBackground.addState(new int[]{android.R.attr.state_focused},shape(Color.WHITE,12,ACCENT));fieldBackground.addState(new int[]{},shape(BG,12,LINE));view.setBackground(fieldBackground);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMinHeight(dp(UiMetrics.TOUCH));
        view.setHint(hint);
        view.setText(text == null ? "" : text);
        view.setContentDescription(hint);
        return view;
    }

    private interface OnText { void change(String value); }

    private void watch(EditText view, OnText change) {
        view.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { change.change(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(10), 0, dp(10));
        view.setLayoutParams(lp);
        return view;
    }

    private LinearLayout dialogContent() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        return layout;
    }

    private ScrollView scrollDialog(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(child);
        return scroll;
    }

    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    /** The visible surface is inset; the full View remains the non-overlapping touch target. */
    private RippleDrawable controlBackground(int color,int radius,int verticalInset,int border){
        android.graphics.drawable.InsetDrawable content=new android.graphics.drawable.InsetDrawable(shape(color==ACCENT?AppThemeTokens.ACTION_FILL:color,radius,border),0,dp(verticalInset),0,dp(verticalInset));
        android.graphics.drawable.InsetDrawable mask=new android.graphics.drawable.InsetDrawable(shape(Color.WHITE,radius,0),0,dp(verticalInset),0,dp(verticalInset));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28,129,114,181)),content,mask){
            @Override public boolean getPadding(android.graphics.Rect rect){rect.set(0,0,0,0);return false;}
        };
    }
    private RippleDrawable iconBackground(int color,int visualSize){
        android.graphics.drawable.LayerDrawable content=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shape(color==ACCENT?AppThemeTokens.ACTION_FILL:color,visualSize/2,0)});
        content.setLayerSize(0,dp(visualSize),dp(visualSize));content.setLayerGravity(0,Gravity.CENTER);
        android.graphics.drawable.LayerDrawable mask=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shape(Color.WHITE,visualSize/2,0)});
        mask.setLayerSize(0,dp(visualSize),dp(visualSize));mask.setLayerGravity(0,Gravity.CENTER);
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28,129,114,181)),content,mask){
            @Override public boolean getPadding(android.graphics.Rect rect){rect.set(0,0,0,0);return false;}
        };
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(24,129,114,181)),
                shape(color==ACCENT?AppThemeTokens.ACTION_FILL:color,radius,0),shape(Color.WHITE,radius,0));
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private void addPage(View view, int top, int bottom) { add(page, view, top, bottom); }
    private void add(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams lp = match();
        lp.setMargins(parent==page?dp(12):0,dp(top),parent==page?dp(12):0,dp(bottom));
        parent.addView(view, lp);
    }
    private int dp(int number) { return Math.round(number * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private String formatDate(long millis) {
        return PersianDates.relative(millis,System.currentTimeMillis(),java.time.ZoneId.systemDefault());
    }

    private String time(int minute) {
        return fa(String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60));
    }

    private String fa(int number) { return fa(Integer.toString(number)); }
    private String fa(String input) {
        StringBuilder output = new StringBuilder();
        for (char c : input.toCharArray()) output.append(c >= '0' && c <= '9' ? (char) ('۰' + c - '0') : c);
        return output.toString();
    }

    private String asciiDigits(String input) {
        if (input == null) return "";
        StringBuilder output = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (c >= '۰' && c <= '۹') output.append((char) ('0' + c - '۰'));
            else if (c >= '٠' && c <= '٩') output.append((char) ('0' + c - '٠'));
            else if (c != '\u200e' && c != '\u200f') output.append(c);
        }
        return output.toString();
    }
}
