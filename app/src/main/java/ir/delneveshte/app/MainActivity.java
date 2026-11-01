package ir.delneveshte.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
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
import android.provider.Telephony;
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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Local approvals and optional cloud synchronization. No SMS is sent on launch. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(255, 249, 245);
    private static final int INK = Color.rgb(56, 37, 45);
    private static final int ROSE = Color.rgb(148, 56, 82);
    private static final int PALE = Color.rgb(249, 231, 235);
    private static final int MUTED = Color.rgb(112, 93, 101);
    private static final int LINE = Color.rgb(235, 220, 224);
    private static final int GOOD = Color.rgb(42, 107, 82);
    private static final int REQUEST_SIM = 102;
    private static final int REQUEST_SMS = 103;
    private static final int REQUEST_NOTIFICATIONS = 104;
    private final Handler handler = new Handler();
    private final Random random = new Random();
    private AppStore store;
    private ir.delneveshte.app.Settings draft;
    private LinearLayout page;
    private ScrollView pageScroll;
    private EditText activeEditor;
    private AlertDialog activeEditorDialog;
    private int currentTab;
    private String category;
    private List<String> options = new ArrayList<>();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!isFinishing() && (currentTab == 1 || currentTab == 2)) renderKeepingScroll();
            handler.postDelayed(this, 8000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AppStore(this);
        draft = store.settings().copy();
        String[] categories = TextBank.categories();
        category = categories.length == 0 ? "همه" : categories[0];
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt("tab", 0);
            category = savedInstanceState.getString("category", category);
            draft.recipient = savedInstanceState.getString("draft_recipient", draft.recipient);
            draft.nickname = savedInstanceState.getString("draft_nickname", draft.nickname);
            draft.dailyCount = savedInstanceState.getInt("draft_count", draft.dailyCount);
            draft.startMinute = savedInstanceState.getInt("draft_start", draft.startMinute);
            draft.endMinute = savedInstanceState.getInt("draft_end", draft.endMinute);
            draft.subscriptionId = savedInstanceState.getInt("draft_sim", draft.subscriptionId);
        }
        Window window = getWindow();
        window.setStatusBarColor(BG);
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
        if (store.settings().enabled
                && (!SmsRuntime.hasSendPermission(this) || !SmsRuntime.hasSimPermission(this))) {
            SmsRuntime.pause(this);
            store.requeueFuture();
            draft.enabled = false;
        }
        // Android removes alarms on force-stop; reopening restores the approved schedule.
        if (store.settings().enabled) SmsRuntime.reconcile(this);
        CloudScheduler.ensure(this);
        maybeOfferCloudSync();
        render();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 8000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (activeEditorDialog != null) activeEditorDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", currentTab);
        state.putString("category", category);
        state.putString("draft_recipient", draft.recipient);
        state.putString("draft_nickname", draft.nickname);
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
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(0, dp(8), 0, 0);
        root.addView(appHeader());
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page = vertical();
        page.setPadding(dp(20), dp(8), dp(20), dp(20));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (currentTab == 0) suggestionsPage();
        else if (currentTab == 1) queuePage();
        else if (currentTab == 2) historyPage();
        else settingsPage();
        root.addView(navigation());
        setContentView(root);
    }

    private View appHeader() {
        LinearLayout row = horizontal();
        row.setPadding(dp(22), dp(10), dp(22), dp(16));
        LinearLayout names = vertical();
        names.addView(label("دل‌نوشته", 26, INK, true));
        names.addView(label("چند کلمه، از دل تو", 12, MUTED, false));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        boolean active = store.settings().enabled;
        String readiness = active ? SmsRuntime.readiness(this) : "";
        boolean ready = readiness == null || readiness.isEmpty();
        TextView state = label(active ? (ready ? "●  ارسال روشن" : "●  نیاز به بررسی") : "○  متوقف", 12,
                active && ready ? GOOD : MUTED, true);
        state.setPadding(dp(11), dp(9), dp(11), dp(9));
        state.setBackground(shape(active ? Color.rgb(230, 242, 233) : PALE, 18, 0));
        state.setContentDescription(active ? "ارسال خودکار فعال است" : "ارسال خودکار متوقف است");
        state.setOnClickListener(v -> selectTab(3));
        row.addView(state);
        return row;
    }

    private View navigation() {
        LinearLayout nav = horizontal();
        nav.setBackgroundColor(Color.WHITE);
        nav.setPadding(dp(6), dp(8), dp(6), dp(10));
        nav.setElevation(dp(5));
        String[] titles = {"پیشنهادها", "صف ارسال", "سابقه", "تنظیمات"};
        String[] icons = {"♡", "≋", "◷", "⚙"};
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TextView item = label(icons[i] + "\n" + titles[i], 12,
                    currentTab == i ? ROSE : MUTED, currentTab == i);
            item.setGravity(Gravity.CENTER);
            item.setMinHeight(dp(54));
            item.setPadding(dp(3), dp(4), dp(3), dp(4));
            item.setBackground(ripple(currentTab == i ? PALE : Color.WHITE, 16));
            item.setContentDescription(titles[i]);
            item.setOnClickListener(v -> selectTab(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(item, lp);
        }
        return nav;
    }

    private void selectTab(int tab) {
        currentTab = tab;
        render();
    }

    private void suggestionsPage() {
        heading("برای امروز، از دل انتخاب کن", "پیام دلخواهت را به صف اضافه کن یا همین حالا بفرست.");
        LinearLayout intro = card(PALE);
        intro.addView(label("انتخاب تو، ارسال در زمان‌های متفاوت", 17, ROSE, true));
        intro.addView(body("با «افزودن به صف»، همین متن برای ارسال در بازهٔ انتخابی تو ذخیره می‌شود.", 14));
        ir.delneveshte.app.Settings saved = store.settings();
        if (saved.valid()) {
            intro.addView(phoneLabel(saved.recipient));
            intro.addView(label(simLabel(saved.subscriptionId), 12, MUTED, false));
        } else {
            intro.addView(body("برای شروع، شمارهٔ همسرت و سیم‌کارت را در تنظیمات ذخیره کن.", 13));
        }
        if (!saved.enabled) intro.addView(body("ارسال خودکار خاموش است؛ پیام‌ها تا روشن‌کردن آن در صف می‌مانند.", 13));
        page.addView(intro);
        HorizontalScrollView categories = new HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = horizontal();
        for (String option : TextBank.categories()) {
            TextView chip = label(option, 13, option.equals(category) ? Color.WHITE : ROSE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(44));
            chip.setPadding(dp(17), dp(8), dp(17), dp(8));
            chip.setBackground(ripple(option.equals(category) ? ROSE : Color.WHITE, 22));
            chip.setOnClickListener(v -> { category = option; renewOptions(); render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMarginEnd(dp(8));
            chips.addView(chip, cp);
        }
        categories.addView(chips);
        addPage(categories, 0, 14);
        page.addView(body(store.hasCachedCatalog(category)
                ? "پیشنهادها از بانک پیام‌ها دریافت و روی گوشی ذخیره شده‌اند."
                : "فعلاً پیشنهادهای آمادهٔ گوشی را می‌بینی؛ بانک پیام‌ها پس از اتصال به سرور به‌روز می‌شود.", 12));
        if (options.isEmpty()) renewOptions();
        if (options.isEmpty()) page.addView(body("در این دسته فعلاً پیامی موجود نیست؛ دستهٔ دیگری را انتخاب کن یا متن خودت را بنویس.",14));
        int number = 0;
        for (String text : options) {
            LinearLayout c = card(Color.WHITE);
            c.addView(label("پیشنهاد " + fa(++number), 12, MUTED, true));
            TextView message = body(text, 18);
            message.setPadding(0, dp(12), 0, dp(12));
            message.setTextIsSelectable(true);
            c.addView(message);
            Button enqueue = actionButton("افزودن به صف", R.drawable.ic_queue, true, () -> { });
            enqueue.setOnClickListener(v -> {
                if (enqueueMessage(text)) {
                    enqueue.setText("به صف اضافه شد");
                    enqueue.setContentDescription("این پیام به صف اضافه شد");
                    setButtonIcon(enqueue, R.drawable.ic_check, Color.WHITE);
                    enqueue.setEnabled(false);
                    enqueue.setAlpha(0.7f);
                }
            });
            c.addView(enqueue);
            LinearLayout actions = horizontal();
            Button edit = actionButton("ویرایش و زمان‌بندی", R.drawable.ic_edit_schedule, false, () -> editText(text));
            Button send = actionButton("ارسال فوری", R.drawable.ic_send, false, () -> confirmNewSend(text));
            edit.setTextSize(13);
            send.setTextSize(13);
            edit.setMinHeight(dp(64));
            send.setMinHeight(dp(64));
            LinearLayout.LayoutParams editParams = weighted();
            editParams.setMarginEnd(dp(8));
            actions.addView(edit, editParams);
            actions.addView(send, weighted());
            add(c, actions, 8, 0);
            page.addView(c);
        }
        page.addView(button("پیشنهادهای تازه", false, () -> { renewOptions(); render(); }));
        addPage(button("متن خودم را می‌نویسم", false, () -> editText("")), 10, 0);
    }

    private void renewOptions() {
        String nickname = draft == null ? "عزیزم" : draft.nickname;
        options = store.hasCachedCatalog(category) ? store.cachedSuggestions(category,nickname)
                : TextBank.suggestions(category, nickname, random);
    }

    private void editText(String initial) {
        LinearLayout content = dialogContent();
        content.addView(body(store.settings().enabled
                ? "متن را ویرایش کن و به صف اضافه کن؛ در بازهٔ زمانی تنظیم‌شده ارسال می‌شود."
                : "متن را ویرایش کن و به صف اضافه کن؛ با روشن‌کردن ارسال خودکار، زمان‌بندی می‌شود.", 14));
        EditText editor = input(initial, "متن پیام", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setMinLines(4);
        editor.setMaxLines(10);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1200)});
        content.addView(editor, match());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ویرایش پیام")
                .setView(content)
                .setPositiveButton("افزودن به صف", null)
                .setNegativeButton("انصراف", null).create();
        activeEditor = editor;
        activeEditorDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (activeEditorDialog == dialog) {
                activeEditor = null;
                activeEditorDialog = null;
            }
        });
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            String text = editor.getText().toString().trim();
            if (text.isEmpty()) { editor.setError("متن پیام را وارد کن"); return; }
            if (enqueueMessage(text)) dialog.dismiss();
        }));
        dialog.show();
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
        try {
            store.approve(text, snapshot, simLabel(snapshot.subscriptionId));
        } catch (RuntimeException error) {
            toast("پیام ذخیره نشد؛ دوباره تلاش کن.");
            return false;
        }
        if (store.settings().enabled) {
            try {
                SmsRuntime.reconcile(this);
                toast("به صف اضافه شد؛ در بازهٔ انتخابی ارسال می‌شود.");
            } catch (RuntimeException error) {
                toast("به صف اضافه شد؛ زمان‌بندی نیاز به بررسی دارد.");
            }
        } else {
            toast("به صف اضافه شد؛ برای ارسال، ارسال خودکار را روشن کن.");
        }
        return true;
    }

    private void confirmNewSend(String text) {
        ir.delneveshte.app.Settings snapshot = snapshotForAction();
        if (snapshot == null) return;
        if (!SmsRuntime.hasSendPermission(this)) { explainSmsPermission(); return; }
        showSendConfirmation(text, snapshot.recipient, snapshot.subscriptionId,
                simLabel(snapshot.subscriptionId), () -> SmsRuntime.sendNewNow(this, text, snapshot,
                        simLabel(snapshot.subscriptionId)));
    }

    private void queuePage() {
        List<Message> queue = store.queue();
        heading("صف پیام‌های تو", fa(queue.size()) + " پیام تأییدشده");
        boolean enabled = store.settings().enabled;
        LinearLayout info = card(PALE);
        info.addView(label(enabled ? "ارسال خودکار روشن است" : "ارسال خودکار متوقف است",
                16, ROSE, true));
        info.addView(body(enabled
                ? "زمان‌های زیر تقریبی‌اند. محدودیت باتری گوشی ممکن است زمان اجرا را کمی جابه‌جا کند؛ خارج از بازهٔ مجاز پیام ارسال نمی‌شود."
                : "متن‌ها محفوظ می‌مانند. برای ارسال تصادفی، در تنظیمات ارسال خودکار را روشن کن.", 14));
        if (!enabled) info.addView(button("رفتن به تنظیمات", false, () -> selectTab(3)));
        page.addView(info);
        if (queue.isEmpty()) {
            empty("صف، منتظر حرف‌های توست", "چند پیشنهاد را انتخاب و تأیید کن. بدون متن تأییدشده، چیزی ارسال نمی‌شود.",
                    "دیدن پیشنهادها", () -> selectTab(0));
            return;
        }
        for (Message message : queue) {
            LinearLayout c = card(Color.WHITE);
            c.addView(label(message.plannedAt > 0 && "SCHEDULED".equals(message.status)
                    ? "زمان تقریبی: " + formatDate(message.plannedAt) : "آمادهٔ زمان‌بندی", 12, ROSE, true));
            c.addView(body(message.body, 17));
            c.addView(divider());
            c.addView(phoneLabel(message.recipient));
            c.addView(label(message.simLabel, 12, MUTED, false));
            if (!message.recipient.equals(store.settings().recipient)
                    || message.subscriptionId != store.settings().subscriptionId) {
                c.addView(body("این پیام با مقصد و سیم‌کارت زمان تأیید ارسال می‌شود؛ تنظیمات تازه آن را تغییر نداده است.", 12));
            }
            LinearLayout actions = horizontal();
            Button sendNow = actionButton("ارسال فوری", R.drawable.ic_send, false, () -> confirmSendNow(message));
            Button cancel = button("حذف از صف", false, () -> cancelMessage(message));
            actions.addView(sendNow, weighted());
            LinearLayout.LayoutParams cp = weighted();
            cp.setMarginStart(dp(8));
            actions.addView(cancel, cp);
            add(c, actions, 12, 0);
            page.addView(c);
        }
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
            LinearLayout c = card(Color.WHITE);
            c.addView(label(statusTitle(message.status), 14,
                    "SENT".equals(message.status) ? GOOD : ROSE, true));
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
        LinearLayout destination = card(Color.WHITE);
        destination.addView(label("مقصد و لحن پیام", 17, INK, true));
        destination.addView(fieldTitle("شمارهٔ همسرت"));
        EditText phone = input(draft.recipient, "مثلاً ‎+98912…", InputType.TYPE_CLASS_PHONE);
        phone.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        phone.setTextDirection(View.TEXT_DIRECTION_LTR);
        phone.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        phone.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        watch(phone, value -> draft.recipient = asciiDigits(value).replace(" ", ""));
        destination.addView(phone, match());
        destination.addView(fieldTitle("نام یا لقب در پیشنهادها"));
        EditText nickname = input(draft.nickname, "مثلاً عزیزم", InputType.TYPE_CLASS_TEXT);
        nickname.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        watch(nickname, value -> draft.nickname = value.trim());
        destination.addView(nickname, match());
        destination.addView(fieldTitle("سیم‌کارت ارسال‌کننده"));
        destination.addView(button(simLabel(draft.subscriptionId) + "  ›", false, this::showSimPicker));
        destination.addView(body("شماره و سیم‌کارت هر پیام هنگام تأیید آن ذخیره می‌شوند. تغییر تنظیمات، مقصد پیام‌های قبلی را عوض نمی‌کند.", 12));
        page.addView(destination);

        LinearLayout timing = card(Color.WHITE);
        timing.addView(label("زمان‌بندی تصادفی", 17, INK, true));
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
        page.addView(button("ذخیرهٔ تنظیمات", true, this::saveDraft));

        LinearLayout control = card(PALE);
        boolean active = store.settings().enabled;
        control.addView(label(active ? "ارسال خودکار فعال است" : "شروع، هر وقت تو بخواهی", 17, ROSE, true));
        control.addView(body("فقط پیام‌های تأییدشده ارسال می‌شوند. اگر صف خالی باشد، برنامه پیام تازه‌ای را خودسرانه ارسال نمی‌کند.", 14));
        if (active) {
            String readiness = SmsRuntime.readiness(this);
            if (readiness != null && !readiness.isEmpty()) control.addView(body(readiness, 14));
        }
        control.addView(button(active ? "توقف ارسال خودکار" : "فعال‌کردن ارسال خودکار", true, () -> {
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
            permissions.addView(button("اجازهٔ ارسال پیامک", false, this::explainSmsPermission));
        if (!SmsRuntime.hasSimPermission(this))
            add(permissions, button("اجازهٔ انتخاب سیم‌کارت", false, this::explainSimPermission), 8, 0);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(permissions, button("فعال‌کردن اعلان‌ها (اختیاری)", false,
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS)), 8, 0);
        }
        permissions.addView(body("این اپ برای کار خودش نیازی به خواندن پیامک‌های شخصی تو ندارد.", 12));
        page.addView(permissions);

        LinearLayout phoneHelp = card(Color.WHITE);
        phoneHelp.addView(label("آماده‌سازی شیائومی", 17, INK, true));
        phoneHelp.addView(body("۱. در تنظیمات برنامه، Background autostart یا شروع خودکار را روشن کن.\n۲. Battery saver را روی No restrictions یا بدون محدودیت بگذار.\n۳. Google Messages را پیام‌رسان پیش‌فرض نگه دار.\nنام و مسیر منوها در MIUI و HyperOS ممکن است متفاوت باشد.", 14));
        phoneHelp.addView(button("بازکردن تنظیمات برنامه", false, this::openAppSettings));
        add(phoneHelp, button("تنظیم شروع خودکار شیائومی", false, this::openAutostart), 8, 0);
        String smsPackage = Telephony.Sms.getDefaultSmsPackage(this);
        phoneHelp.addView(body("com.google.android.apps.messaging".equals(smsPackage)
                ? "✓  Google Messages پیام‌رسان پیش‌فرض گوشی است."
                : "برای بررسی ثبت پیام‌ها، Google Messages را در برنامه‌های پیش‌فرض انتخاب کن.", 13));
        add(phoneHelp, button("برنامه‌های پیش‌فرض گوشی", false,
                () -> openSystem(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))), 8, 0);
        phoneHelp.addView(body("برای آزمون اول، یک متن را تأیید کن و در صف «ارسال همین حالا» را بزن؛ سپس ثبت آن را در گفت‌وگوی همسرت در Google Messages ببین. محدودیت‌های گوشی و نصب باید روی همین دستگاه بررسی شوند.", 13));
        page.addView(phoneHelp);
        cloudSettings();
        addPage(body("دل‌نوشته · " + BuildConfig.VERSION_NAME + "\nصف همیشه ابتدا روی گوشی ذخیره می‌شود. همگام‌سازی به اینترنت و اجازهٔ اجرای پس‌زمینه نیاز دارد.", 12), 0, 0);
    }

    private static final String CLOUD_DISCLOSURE = "با فعال‌کردن همگام‌سازی، متن پیام‌های تأییدشده، شمارهٔ گیرنده و وضعیت ارسال آن‌ها همراه شناسهٔ نصب به سرور محصول فرستاده می‌شود و مدیر محصول آن‌ها را می‌بیند. پیامک‌های دیگر گوشی خوانده نمی‌شوند. ارسال پیامک همچنان از سیم‌کارت تو انجام می‌شود.";

    private void maybeOfferCloudSync() {
        CloudConfig cloud=new CloudConfig(this);
        if(cloud.prompted()||cloud.baseUrl().isEmpty()||activeEditorDialog!=null)return;
        cloud.setPrompted();
        new AlertDialog.Builder(this).setTitle("همگام‌سازی با سرور")
                .setMessage(CLOUD_DISCLOSURE)
                .setPositiveButton("فعال‌کردن",(dialog,which)->{
                    cloud.configure(cloud.baseUrl(),true);CloudScheduler.ensure(this);render();
                    toast("همگام‌سازی با اتصال به اینترنت انجام می‌شود");
                }).setNegativeButton("فعلاً نه",null).show();
    }

    private void cloudSettings() {
        CloudConfig cloud=new CloudConfig(this);
        LinearLayout section=card(Color.WHITE);
        section.addView(label("بانک پیام‌ها و همگام‌سازی",17,INK,true));
        section.addView(body(CLOUD_DISCLOSURE,13));
        section.addView(body(cloud.enabled()?"همگام‌سازی فعال است":"همگام‌سازی غیرفعال است",14));
        section.addView(body("پیام‌های در انتظار همگام‌سازی: "+fa(store.pendingSyncCount()),13));
        if(cloud.lastSync()>0)section.addView(body("آخرین همگام‌سازی: "+DateFormat.getDateTimeInstance().format(new Date(cloud.lastSync())),12));
        if(!cloud.lastError().isEmpty())section.addView(body(cloud.lastError(),13));
        if(cloud.enabled()) {
            section.addView(button("همگام‌سازی با اینترنت",false,()->{
                CloudScheduler.requestSync(this);toast("با دسترسی به اینترنت، همگام‌سازی شروع می‌شود");
            }));
            add(section,button("خاموش‌کردن همگام‌سازی",false,()->{
                cloud.configure(cloud.baseUrl(),false);CloudScheduler.ensure(this);render();
                toast("همگام‌سازی متوقف شد؛ اطلاعات قبلی سرور حذف نمی‌شود");
            }),8,0);
        }
        add(section,button(cloud.enabled()?"تغییر سرور":"راه‌اندازی همگام‌سازی",false,()->{
            LinearLayout content=dialogContent();content.addView(body(CLOUD_DISCLOSURE,13));
            EditText address=input(cloud.baseUrl(),"https://…workers.dev",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
            address.setTextDirection(View.TEXT_DIRECTION_LTR);content.addView(address,match());
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("اتصال به سرور")
                    .setView(content).setPositiveButton("ذخیره و فعال‌کردن",null).setNegativeButton("انصراف",null).create();
            dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try {
                    String url=CloudConfig.normalizeUrl(address.getText().toString());
                    boolean changed=!url.equals(cloud.baseUrl());cloud.configure(url,true);
                    if(changed){store.clearCatalog();cloud.catalogUpdatedReset();}
                    CloudScheduler.ensure(this);renewOptions();dialog.dismiss();render();
                }catch(IllegalArgumentException error){address.setError(error.getMessage());}
            }));dialog.show();
        }),8,0);
        page.addView(section);
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
        if (draft.enabled && (!SmsRuntime.hasSendPermission(this) || !isActiveSim(draft.subscriptionId))) {
            draft.enabled = false;
        }
        store.saveSettings(draft.copy());
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
        return a.recipient.equals(b.recipient) && a.nickname.equals(b.nickname)
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
        new AlertDialog.Builder(this).setTitle("انتخاب بین دو سیم‌کارت")
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
        render();
        if (requestCode == REQUEST_SIM && allowed) showSimPicker();
        else if (requestCode == REQUEST_SMS && allowed)
            toast("مجوز ثبت شد؛ برای شروع، خودت ارسال خودکار را فعال کن یا پیام موردنظر را از صف بفرست.");
        else if (!allowed && requestCode != REQUEST_NOTIFICATIONS)
            toast("مجوز داده نشد؛ ارسال فعال نمی‌شود.");
    }

    private void openAppSettings() {
        openSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void openAutostart() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        try { startActivity(intent); }
        catch (RuntimeException error) {
            toast("این مسیر در نسخهٔ گوشی متفاوت است؛ در تنظیمات، «شروع خودکار» را جست‌وجو کن.");
            openAppSettings();
        }
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
        page.addView(label(title, 22, INK, true));
        addPage(body(subtitle, 14), 3, 20);
    }

    private void empty(String title, String text, String action, Runnable task) {
        LinearLayout c = card(Color.WHITE);
        TextView icon = label("♡", 48, ROSE, false);
        icon.setGravity(Gravity.CENTER);
        c.addView(icon);
        TextView t = label(title, 18, INK, true);
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
        view.setPadding(0, dp(18), 0, dp(8));
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
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(shape(color, 20, color == Color.WHITE ? LINE : 0));
        LinearLayout.LayoutParams lp = match();
        lp.bottomMargin = dp(14);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setGravity(Gravity.RIGHT);
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView body(String text, int size) {
        TextView view = label(text, size, MUTED, false);
        view.setLineSpacing(dp(4), 1.18f);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(primary ? Color.WHITE : ROSE);
        view.setMinHeight(dp(50));
        view.setMinimumHeight(dp(50));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(ripple(primary ? ROSE : PALE, 14));
        view.setStateListAnimator(null);
        view.setContentDescription(title);
        view.setLayoutParams(match());
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private Button actionButton(String title, int icon, boolean primary, Runnable action) {
        Button view = button(title, primary, action);
        setButtonIcon(view, icon, primary ? Color.WHITE : ROSE);
        return view;
    }

    private void setButtonIcon(Button view, int icon, int color) {
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(20), dp(20));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(6));
    }

    private EditText input(String text, String hint, int type) {
        EditText view = new EditText(this);
        view.setTextSize(16);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setInputType(type);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setGravity(Gravity.RIGHT | Gravity.TOP);
        view.setBackground(shape(BG, 12, LINE));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setMinHeight(dp(50));
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
        layout.setPadding(dp(24), dp(10), dp(24), dp(8));
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

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(30, 148, 56, 82)),
                shape(color, radius, 0), shape(Color.WHITE, radius, 0));
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private void addPage(View view, int top, int bottom) { add(page, view, top, bottom); }
    private void add(LinearLayout parent, View view, int top, int bottom) {
        LinearLayout.LayoutParams lp = match();
        lp.setMargins(0, dp(top), 0, dp(bottom));
        parent.addView(view, lp);
    }
    private int dp(int number) { return Math.round(number * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private String formatDate(long millis) {
        if (millis <= 0) return "";
        DateFormat format = new SimpleDateFormat("EEE d MMM، HH:mm", new Locale("fa"));
        return format.format(new Date(millis));
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
