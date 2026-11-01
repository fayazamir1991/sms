package ir.delneveshte.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudSyncJobService extends JobService {
    private final ConcurrentHashMap<Integer,AtomicBoolean> stopped=new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        AtomicBoolean stop=new AtomicBoolean(false);stopped.put(params.getJobId(),stop);
        new Thread(()->{
            boolean retry=CloudSync.run(getApplicationContext(),stop);
            if(stopped.remove(params.getJobId(),stop)&&!stop.get())jobFinished(params,retry);
        },"delneveshte-sync").start();return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean stop=stopped.remove(params.getJobId());if(stop!=null)stop.set(true);return true;
    }
    @Override public void onDestroy() { for(AtomicBoolean stop:stopped.values())stop.set(true);stopped.clear();super.onDestroy(); }
}
