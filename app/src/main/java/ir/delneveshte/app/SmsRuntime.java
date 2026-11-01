package ir.delneveshte.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Android integration. A queued message's body, destination and SIM are never rewritten. */
public final class SmsRuntime {
    private static final Object LOCK = new Object();
    private static final String ACTION_ALARM = "ir.delneveshte.app.SEND_ALARM";
    static final String ACTION_SENT = "ir.delneveshte.app.SMS_SENT";
    static final String EXTRA_ID = "message_id";
    static final String EXTRA_PART = "part_index";
    private static final String CHANNEL_ID = "delneveshte_status";
    private static final int ALARM_REQUEST = 401;
    private static final int NOTICE_ID = 402;
    private static final long MINUTE = 60_000L;
    private static final Random RANDOM = new java.security.SecureRandom();

    private SmsRuntime() { }

    public static final class Sim {
        public final int id;
        public final String label;

        public Sim(int id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override public String toString() { return label; }
    }

    public static boolean hasSendPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasSimPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** No phone numbers or SIM identifiers other than Android's subscription id are read. */
    public static List<Sim> sims(Context context) {
        if (!hasSimPermission(context)) return Collections.emptyList();
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        if (manager == null) return Collections.emptyList();
        try {
            List<SubscriptionInfo> subscriptions = manager.getActiveSubscriptionInfoList();
            if (subscriptions == null) return Collections.emptyList();
            List<SubscriptionInfo> ordered = new ArrayList<>(subscriptions);
            Collections.sort(ordered, Comparator.comparingInt(SubscriptionInfo::getSimSlotIndex));
            List<Sim> result = new ArrayList<>();
            for (SubscriptionInfo info : ordered) {
                int subscriptionId = info.getSubscriptionId();
                if (subscriptionId < 0 || subscriptionId == Integer.MAX_VALUE) continue;
                String display = info.getDisplayName() == null ? "" : info.getDisplayName().toString();
                if (display.trim().isEmpty() && info.getCarrierName() != null) {
                    display = info.getCarrierName().toString();
                }
                String label = "سیم‌کارت " + (info.getSimSlotIndex() + 1);
                if (!display.trim().isEmpty()) label += " · " + display;
                result.add(new Sim(info.getSubscriptionId(), label));
            }
            return result;
        } catch (SecurityException | IllegalStateException exception) {
            return Collections.emptyList();
        }
    }

    public static String readiness(Context context) {
        Settings settings = new AppStore(context).settings();
        if (!settings.valid()) {
            return "شماره، تعداد پیام، بازهٔ زمانی و سیم‌کارت را در تنظیمات کامل کن.";
        }
        return transportIssue(context, settings.subscriptionId);
    }

    private static String transportIssue(Context context, int subscriptionId) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return "این دستگاه امکان ارسال پیامک از سیم‌کارت را ندارد.";
        }
        if (!hasSendPermission(context)) return "مجوز ارسال پیامک داده نشده است.";
        if (!hasSimPermission(context)) return "برای انتخاب و بررسی سیم‌کارت، مجوز تلفن لازم است.";
        for (Sim sim : sims(context)) if (sim.id == subscriptionId) return "";
        return "سیم‌کارت تأییدشده اکنون فعال نیست. پیام به سیم‌کارت دیگری منتقل نمی‌شود.";
    }

    /** Safe to call from UI, boot, permission callbacks or completion receivers: never sends. */
    public static void reconcile(Context context) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            AppStore store = new AppStore(app);
            store.recoverUncertain(System.currentTimeMillis());
            reconcileLocked(app, store, System.currentTimeMillis());
        }
    }

    public static void pause(Context context) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            AppStore store = new AppStore(app);
            Settings settings = store.settings();
            settings.enabled = false;
            store.saveSettings(settings);
            store.requeueFuture();
            cancelAlarm(app);
        }
    }

    private static void reconcileLocked(Context app, AppStore store, long now) {
        Settings settings = store.settings();
        if (!settings.enabled) {
            cancelAlarm(app);
            return;
        }
        String problem = readiness(app);
        if (!problem.isEmpty()) {
            cancelAlarm(app);
            notifyStatus(app, "ارسال نیاز به بررسی دارد", problem);
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long start = atMinute(today, zone, settings.startMinute);
        long end = atMinute(today, zone, settings.endMinute);
        String day = today.toString();

        // Stale days and alarms delayed beyond the allowed window are not caught up by sending.
        boolean stale = false;
        for (Message message : store.scheduled()) {
            if (!day.equals(message.planDay) || now >= end) {
                stale = true;
                break;
            }
        }
        if (stale) store.requeueFuture();

        int quota = Math.max(0, settings.dailyCount - store.attemptedOn(day));
        List<Message> scheduled = store.scheduled();
        if (quota == 0 || scheduled.size() > quota) {
            store.requeueFuture();
            scheduled = store.scheduled();
        }

        // Preserve existing random times. Reopening the app must not reshuffle the day's plan.
        if (!scheduled.isEmpty()) {
            arm(app, Math.max(now + 1000L, scheduled.get(0).plannedAt));
            return;
        }

        List<Message> approved = store.unscheduled();
        if (quota > 0 && !approved.isEmpty() && now < end) {
            int count = Math.min(quota, approved.size());
            List<Long> times = SchedulePlanner.plan(today, zone, settings.startMinute,
                    settings.endMinute, count, now, RANDOM);
            for (int i = 0; i < times.size() && i < approved.size(); i++) {
                store.schedule(approved.get(i).id, times.get(i), day);
            }
            scheduled = store.scheduled();
            if (!scheduled.isEmpty()) {
                arm(app, Math.max(now + 1000L, scheduled.get(0).plannedAt));
                return;
            }
        }
        // There may be approved texts for tomorrow, or the queue may later be replenished.
        // One lightweight inexact alarm replaces repeating timers and survives normal process death.
        long tomorrow = atMinute(today.plusDays(1), zone, settings.startMinute);
        arm(app, Math.max(now + MINUTE, tomorrow));
    }

    /** Invoked exclusively by our alarm PendingIntent. Sends at most one delayed message per wake. */
    public static void onAlarm(Context context) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            AppStore store = new AppStore(app);
            long now = System.currentTimeMillis();
            store.recoverUncertain(now);
            Settings settings = store.settings();
            if (!settings.enabled) {
                cancelAlarm(app);
                return;
            }
            String issue = readiness(app);
            if (!issue.isEmpty()) {
                cancelAlarm(app);
                notifyStatus(app, "ارسال متوقف شد", issue);
                return;
            }
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            String day = today.toString();
            long start = atMinute(today, zone, settings.startMinute);
            long end = atMinute(today, zone, settings.endMinute);
            if (now >= start && now < end && store.attemptedOn(day) < settings.dailyCount) {
                for (Message message : store.scheduled()) {
                    if (message.plannedAt > now) break;
                    if (!day.equals(message.planDay)) continue;
                    try {
                        sendLocked(app, store, message, true);
                    } catch (IllegalStateException exception) {
                        store.failBeforeSend(message.id, exception.getMessage());
                        notifyStatus(app, "پیام ارسال نشد", exception.getMessage());
                    }
                    break;
                }
            }
            // Re-plan other overdue items instead of sending a burst after a delayed wake-up.
            store.expireSchedules(System.currentTimeMillis());
            reconcileLocked(app, store, System.currentTimeMillis());
        }
    }

    /** New suggestions never enter the automatic queue when their intent is immediate sending. */
    public static long sendNewNow(Context context, String text, Settings snapshot, String simLabel) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            if (!snapshot.valid()) throw new IllegalStateException("تنظیمات ارسال را بررسی کن.");
            String problem = transportIssue(app, snapshot.subscriptionId);
            if (!problem.isEmpty()) throw new IllegalStateException(problem);
            AppStore store = new AppStore(app);
            long id = store.approveImmediate(text, snapshot, simLabel);
            try {
                sendLocked(app, store, store.get(id), false);
            } catch (RuntimeException exception) {
                store.failBeforeSend(id, exception.getMessage());
                throw exception;
            } finally {
                reconcileLocked(app, store, System.currentTimeMillis());
            }
            return id;
        }
    }

    /** This entry point is only used after the UI's explicit immediate-send confirmation. */
    public static void sendApprovedNow(Context context, long id) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            AppStore store = new AppStore(app);
            Message message = store.get(id);
            if (message == null || !("APPROVED".equals(message.status)
                    || "SCHEDULED".equals(message.status))) {
                throw new IllegalStateException("این پیام دیگر در صف تأییدشده نیست.");
            }
            String problem = transportIssue(app, message.subscriptionId);
            if (!problem.isEmpty()) throw new IllegalStateException(problem);
            long now = System.currentTimeMillis();
            String day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString();
            if (!store.schedule(id, now, day)) {
                throw new IllegalStateException("وضعیت پیام تغییر کرده است؛ دوباره صف را بررسی کن.");
            }
            message = store.get(id);
            try {
                sendLocked(app, store, message, false);
            } catch (IllegalStateException exception) {
                store.failBeforeSend(id, exception.getMessage());
                throw exception;
            } finally {
                reconcileLocked(app, store, System.currentTimeMillis());
            }
        }
    }

    private static void sendLocked(Context app, AppStore store, Message message, boolean scheduled) {
        if(java.util.regex.Pattern.compile("https?://|www\\.|[a-z0-9-]+\\.(com|ir|net|org)\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message.body).find()){
            store.failBeforeSend(message.id,"این پیام لینک دارد؛ متن بدون لینک را دوباره انتخاب و تأیید کن.");return;
        }
        String problem = transportIssue(app, message.subscriptionId);
        if (!problem.isEmpty()) throw new IllegalStateException(problem);
        if (message.body == null || message.body.trim().isEmpty()
                || message.recipient == null || message.recipient.trim().isEmpty()) {
            throw new IllegalStateException("متن یا شمارهٔ تأییدشده معتبر نیست.");
        }

        SmsManager manager;
        ArrayList<String> parts;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SmsManager base = app.getSystemService(SmsManager.class);
                if (base == null) throw new IllegalStateException("سرویس پیامک در دسترس نیست.");
                manager = base.createForSubscriptionId(message.subscriptionId);
            } else {
                manager = SmsManager.getSmsManagerForSubscriptionId(message.subscriptionId);
            }
            parts = manager.divideMessage(message.body);
        } catch (SecurityException | UnsupportedOperationException exception) {
            throw new IllegalStateException("دسترسی یا سرویس ارسال پیامک در دسترس نیست.");
        }
        if (parts == null || parts.isEmpty() || parts.size() > 255) {
            throw new IllegalStateException("طول متن برای ارسال پیامک مناسب نیست.");
        }
        ArrayList<PendingIntent> sentIntents = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            Intent sent = new Intent(app, SentReceiver.class)
                    .setAction(ACTION_SENT)
                    .setData(Uri.parse("delneveshte://sent/" + message.id + "/" + i))
                    .putExtra(EXTRA_ID, message.id)
                    .putExtra(EXTRA_PART, i);
            sentIntents.add(PendingIntent.getBroadcast(app, 0, sent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        // Preparation and SIM queries can take time. Re-read the clock and current settings at
        // the last possible moment; a wake just before the window closes must not use stale time.
        long submitAt = System.currentTimeMillis();
        if (scheduled && !eligibleScheduled(store, message, submitAt)) return;
        if (!store.claim(message.id, submitAt, parts.size())) {
            throw new IllegalStateException("وضعیت پیام تغییر کرده است؛ صف را دوباره بررسی کن.");
        }

        try {
            // Ordinary SmsManager sending is intentional: Android persists non-default-app sends
            // in the SMS Provider, so Google Messages remains default and can show the SMS.
            if (parts.size() == 1) {
                manager.sendTextMessage(message.recipient, null, message.body, sentIntents.get(0), null);
            } else {
                manager.sendMultipartTextMessage(message.recipient, null, parts, sentIntents, null);
            }
        } catch (RuntimeException exception) {
            // Once claimed, never silently put a message back into the queue: the outcome could
            // be ambiguous. A user can inspect Google Messages before choosing any later action.
            store.failAttempt(message.id,
                    "درخواست ارسال تأیید نشد؛ برای جلوگیری از پیام تکراری، تلاش خودکار انجام نمی‌شود.");
            notifyStatus(app, "ارسال نیاز به بررسی دارد",
                    "وضعیت این پیام را در Google Messages بررسی کن؛ تلاش مجدد خودکار انجام نمی‌شود.");
        }
    }

    private static boolean eligibleScheduled(AppStore store, Message message, long now) {
        Settings current = store.settings();
        if (!current.enabled || !current.valid()) return false;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        return date.toString().equals(message.planDay)
                && now >= atMinute(date, zone, current.startMinute)
                && now < atMinute(date, zone, current.endMinute)
                && store.attemptedOn(date.toString()) < current.dailyCount;
    }

    static void recordSentResult(Context context, long id, int part, int resultCode) {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            AppStore store = new AppStore(app);
            Message message = store.get(id);
            if (message == null || part < 0 || part >= message.partCount) return;
            boolean success = resultCode == Activity.RESULT_OK;
            String detail = success ? "تحویل به شبکه تأیید شد؛ رسیدن به مخاطب تأیید نشده است."
                    : resultDetail(resultCode);
            store.recordPart(id, part, success, detail);
            if (!success) notifyStatus(app, "ارسال یک پیام ناموفق بود", detail);
            reconcileLocked(app, store, System.currentTimeMillis());
        }
    }

    private static String resultDetail(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                return "رادیوی تلفن خاموش است؛ تلاش مجدد خودکار انجام نمی‌شود.";
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                return "شبکهٔ تلفن در دسترس نیست؛ تلاش مجدد خودکار انجام نمی‌شود.";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                return "محدودیت ارسال پیامک اعمال شده است؛ تلاش مجدد خودکار انجام نمی‌شود.";
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
                return "محدودیت شماره‌گیری سیم‌کارت مانع ارسال شد.";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED:
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED:
                return "ارسال به این شماره توسط سیستم مجاز نیست.";
            case SmsManager.RESULT_ERROR_NULL_PDU:
                return "سیستم نتوانست پیامک را آماده کند.";
            default:
                return "ارسال همهٔ بخش‌های پیام تأیید نشد (کد " + code
                        + "). پیش از هر ارسال مجدد، Google Messages را بررسی کن.";
        }
    }

    private static long atMinute(LocalDate date, ZoneId zone, int minute) {
        return date.atStartOfDay().plusMinutes(minute).atZone(zone).toInstant().toEpochMilli();
    }

    private static PendingIntent alarmIntent(Context app) {
        Intent intent = new Intent(app, AlarmReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(app, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context app, long triggerAt) {
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        if (manager == null) {
            notifyStatus(app, "زمان‌بندی در دسترس نیست", "سرویس زمان‌بندی گوشی در دسترس نیست.");
            return;
        }
        try {
            // Exact-alarm special access is deliberately unnecessary for random send times.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmIntent(app));
        } catch (RuntimeException exception) {
            notifyStatus(app, "زمان‌بندی فعال نشد", "تنظیمات اجرای پس‌زمینهٔ گوشی را بررسی کن.");
        }
    }

    private static void cancelAlarm(Context app) {
        AlarmManager manager = app.getSystemService(AlarmManager.class);
        if (manager != null) manager.cancel(alarmIntent(app));
    }

    public static void ensureNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "وضعیت ارسال دل‌نوشته", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("اشکال در مجوزها، سیم‌کارت یا ارسال پیامک");
        manager.createNotificationChannel(channel);
    }

    private static void notifyStatus(Context context, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureNotificationChannel(context);
        Intent open = new Intent(context, MainActivity.class).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        try { manager.notify(NOTICE_ID, notification); } catch (SecurityException ignored) { }
    }
}
