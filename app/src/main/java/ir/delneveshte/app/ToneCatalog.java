package ir.delneveshte.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/** A catalog row owns both registers. Approved SMS bodies never pass through this class. */
public final class ToneCatalog {
    private static JSONArray bundled;
    private ToneCatalog() { }
    public static String body(JSONObject row,String tone) {
        if("شعر".equals(row.optString("category")))return row.optString("body",null);
        String key="formal".equals(tone)?"body_formal":"body_colloquial";
        if(row.isNull(key))return null;
        String text=row.optString(key,"").trim();return text.isEmpty()?null:text;
    }
    public static JSONArray select(JSONArray rows,String tone) throws JSONException {
        JSONArray result=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=new JSONObject(rows.getJSONObject(i).toString());String text=body(row,tone);
            // An old server without paired fields cannot silently supply the wrong register.
            if(text!=null)result.put(row.put("body",text));
        }
        return result;
    }
    private static synchronized JSONArray bundled(Context context) {
        if(bundled==null)try(InputStream in=new java.util.zip.GZIPInputStream(context.getResources().openRawResource(R.raw.catalog_tones));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            bundled=new JSONArray(out.toString("UTF-8"));
        }catch(Exception error){throw new IllegalStateException("بانک پیام آماده خوانده نشد",error);}
        return bundled;
    }
    public static List<String> offline(Context context,String category,String nickname,String tone) {
        List<String> result=new ArrayList<>();JSONArray rows=bundled(context);
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||(!"همه".equals(category)&&!category.equals(row.optString("category"))))continue;
            String text=body(row,tone);if(text!=null)result.add(text);
        }
        if("همه".equals(category)||"شعر".equals(category))result.addAll(TextBank.allSuggestions("شعر",nickname));
        return result;
    }
}
