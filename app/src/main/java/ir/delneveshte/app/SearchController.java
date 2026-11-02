package ir.delneveshte.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread coordinator; scans, parsing, cache writes and HTTP run only on bounded workers. */
public final class SearchController {
    public interface Factory {CloudSync.Transport create(CloudConfig cloud);}
    public interface Listener {void update(List<String> rows,boolean more,boolean loading,String error);}
    private final Context app;private final AppStore store;private final Factory factory;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor local=pool(1,"catalog-search-local"),remote=pool(2,"catalog-search-http");
    private final List<String> visible=new ArrayList<>();private final ArrayDeque<String> pending=new ArrayDeque<>();private final Set<String> seen=new HashSet<>();
    private volatile int generation;private int target;private String query,tone,cursor,error;
    private boolean localMore,remoteMore,localBusy,remoteBusy,localRequested,remoteRequested;private LocalSearchPager pager;
    private Future<?> localTask,remoteTask;private CloudSync.Transport transport;
    public SearchController(Context context,AppStore store,Factory factory,Listener listener){app=context.getApplicationContext();this.store=store;this.factory=factory;this.listener=listener;}
    private static ThreadPoolExecutor pool(int size,String name){return new ThreadPoolExecutor(size,size,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());}
    public void cancel(){generation++;if(localTask!=null)localTask.cancel(true);if(remoteTask!=null)remoteTask.cancel(true);if(transport!=null)transport.cancel();local.purge();remote.purge();localBusy=false;remoteBusy=false;}
    public void close(){cancel();local.shutdownNow();remote.shutdownNow();}
    public void start(String text,String selectedTone){
        cancel();query=text;tone=selectedTone;cursor=null;error=null;target=20;visible.clear();pending.clear();seen.clear();
        localRequested=false;remoteRequested=false;localMore=CatalogSearch.canSearch(text);remoteMore=localMore&&new CloudConfig(app).enabled();pager=new LocalSearchPager(app,store,text,selectedTone);pump();
    }
    public void more(){if(visible.size()<target&&(localBusy||remoteBusy))return;target=visible.size()+20;localRequested=false;remoteRequested=false;pump();}
    private boolean stale(int token){return token!=generation||Thread.currentThread().isInterrupted();}
    private void accept(List<String> rows){for(String raw:rows){String text=CatalogText.render(raw,"عزیزم");if(text!=null&&seen.add(text))pending.addLast(raw);}}
    private void pump(){
        while(visible.size()<target&&!pending.isEmpty())visible.add(pending.removeFirst());
        boolean need=visible.size()<target;
        if(need&&localMore&&!localBusy&&!localRequested)loadLocal();
        if(need&&remoteMore&&!remoteBusy&&!remoteRequested)loadRemote();
        listener.update(new ArrayList<>(visible),!pending.isEmpty()||localMore||remoteMore,need&&(localBusy||remoteBusy),error);
    }
    private void loadLocal(){
        localBusy=true;localRequested=true;final int token=generation;final LocalSearchPager chosen=pager;
        localTask=local.submit(()->{LocalSearchPager.Page page;try{page=chosen.next(()->stale(token));}catch(Exception failure){page=new LocalSearchPager.Page(new ArrayList<>(),false);}
            final LocalSearchPager.Page result=page;if(stale(token))return;
            main.post(()->{if(token!=generation)return;localBusy=false;localMore=result.more;accept(result.rows);pump();});
        });
    }
    private void loadRemote(){
        remoteBusy=true;remoteRequested=true;final int token=generation;final String q=query,selected=tone,next=cursor;final CloudConfig cloud=new CloudConfig(app);final int cloudToken=cloud.generation();
        final CloudSync.Transport call=factory.create(cloud);transport=call;
        remoteTask=remote.submit(()->{
            List<String> bodies=new ArrayList<>();String nextCursor=null,failure=null;boolean more=false;
            try{
                JSONObject data=new CatalogClient(call,cloud).browse("همه",q,next,selected);if(stale(token))return;
                JSONArray rows=ToneCatalog.select(data.getJSONArray("messages"),selected);if(rows.length()>20)throw new IllegalStateException();
                for(int i=0;i<rows.length();i++){String raw=rows.getJSONObject(i).getString("body"),text=CatalogText.render(raw,"عزیزم");if(text!=null&&CatalogSearch.matches(text,q))bodies.add(raw);}
                if(stale(token)||cloud.generation()!=cloudToken)return;store.rememberCatalog(rows);
                nextCursor=data.isNull("next_cursor")?null:data.optString("next_cursor",null);more=data.optBoolean("has_more",false)&&nextCursor!=null;
                cloud.catalogUpdated();
            }catch(Exception e){if(stale(token))return;failure=e instanceof CloudApi.Failure?e.getMessage():"دریافت نتیجه‌ها کامل نشد؛ پیام‌های روی گوشی در دسترس‌اند.";cloud.error(failure);}
            if(stale(token))return;final String c=nextCursor,e=failure;final boolean m=more;
            main.post(()->{if(token!=generation)return;remoteBusy=false;cursor=c;remoteMore=m;error=e;accept(bodies);pump();});
        });
    }
}
