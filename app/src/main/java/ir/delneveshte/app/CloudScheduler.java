package ir.delneveshte.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class CloudScheduler {
    private static final int ONE_SHOT=8701,PERIODIC=8702;
    private CloudScheduler() { }
    public static void requestSync(Context context) {
        if(!new CloudConfig(context).enabled())return;
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        try{scheduler.schedule(new JobInfo.Builder(ONE_SHOT,new ComponentName(context,CloudSyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1500)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}catch(RuntimeException ignored){ }
    }
    public static void ensure(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        if(!new CloudConfig(context).enabled()){scheduler.cancel(ONE_SHOT);scheduler.cancel(PERIODIC);return;}
        try{if(scheduler.getPendingJob(PERIODIC)==null)scheduler.schedule(new JobInfo.Builder(PERIODIC,new ComponentName(context,CloudSyncJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}catch(RuntimeException ignored){ }
        requestSync(context);
    }
}
