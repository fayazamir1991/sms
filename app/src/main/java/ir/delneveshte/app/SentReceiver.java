package ir.delneveshte.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Per-part platform result, distinct from a recipient delivery/read receipt. */
public final class SentReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !SmsRuntime.ACTION_SENT.equals(intent.getAction())) return;
        long id = intent.getLongExtra(SmsRuntime.EXTRA_ID, -1L);
        int part = intent.getIntExtra(SmsRuntime.EXTRA_PART, -1);
        if (id < 0 || part < 0) return;
        int resultCode = getResultCode();
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { SmsRuntime.recordSentResult(app, id, part, resultCode); }
            finally { pending.finish(); }
        }, "delneveshte-sent").start();
    }
}
