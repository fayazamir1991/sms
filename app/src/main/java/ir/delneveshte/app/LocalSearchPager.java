package ir.delneveshte.app;

import android.content.Context;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Worker-only cursor: bounded cache chunks, then bundled rows, at most 21 matches per page. */
public final class LocalSearchPager {
    public static final class Page {public final List<String> rows;public final boolean more;Page(List<String> rows,boolean more){this.rows=rows;this.more=more;}}
    private final AppStore store;private final Context context;private final String query,tone;
    private final Set<String> seen=new HashSet<>();
    private String after="",carry;private boolean cacheDone;private int cacheAt,bundleAt;
    private List<String[]> chunk=new ArrayList<>();private List<String> bundled;
    public LocalSearchPager(Context context,AppStore store,String query,String tone){this.context=context.getApplicationContext();this.store=store;this.query=CatalogSearch.key(query);this.tone=tone;}
    public Page next(BooleanSupplier cancelled){
        List<String> out=new ArrayList<>();if(carry!=null){out.add(carry);carry=null;}
        while(out.size()<21&&!cancelled.getAsBoolean()){
            String raw;
            if(!cacheDone){
                if(cacheAt>=chunk.size()){
                    chunk=store.searchChunk(after,tone);cacheAt=0;
                    if(chunk.isEmpty()){cacheDone=true;continue;}
                }
                String[] row=chunk.get(cacheAt++);after=row[0];raw=row[1];
            }else{
                if(bundled==null)bundled=ToneCatalog.offline(context,"همه","عزیزم",tone);
                if(bundleAt>=bundled.size())break;raw=bundled.get(bundleAt++);
            }
            String text=CatalogText.render(raw,"عزیزم");
            if(text!=null&&CatalogSearch.key(text).contains(query)&&seen.add(text))out.add(raw);
        }
        if(out.size()>20){carry=out.remove(20);return new Page(out,true);}
        return new Page(out,false);
    }
}
