package ir.delneveshte.app;

import java.net.URLEncoder;
import org.json.JSONObject;

/** Registration is reused; a server reset is recovered by one authenticated retry. */
public final class CatalogClient {
    private final CloudSync.Transport api;
    private final CloudConfig config;
    private final int generation;
    public CatalogClient(CloudSync.Transport transport, CloudConfig cloud) { api=transport;config=cloud;generation=cloud.generation(); }
    private void register() throws Exception {
        api.request("/api/v1/installations/register",new JSONObject().put("app_version",BuildConfig.VERSION_NAME).put("device_hash",config.deviceHash()));
        config.registrationSucceeded(generation);
    }
    public JSONObject browse(String category,String query,String cursor) throws Exception {
        if(!config.registered())register();
        String path="/api/v1/messages/browse?category="+URLEncoder.encode(category,"UTF-8")+"&q="+URLEncoder.encode(query,"UTF-8");
        if(cursor!=null)path+="&cursor="+URLEncoder.encode(cursor,"UTF-8");
        try{return api.request(path,null);}catch(CloudApi.Failure failure){
            if(failure.status!=401)throw failure;
            register();return api.request(path,null);
        }
    }
}
