package ir.delneveshte.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS only, bounded responses, no redirects carrying credentials to another host. */
public final class CloudApi implements CloudSync.Transport {
    private final String base,authorization;
    public CloudApi(CloudConfig config) { base=config.baseUrl();authorization="Bearer "+config.installationId()+"."+config.credential(); }
    public static class Failure extends Exception {
        public final int status;
        public Failure(int status,String message) { super(message);this.status=status; }
        public boolean retryable() { return status==0||status==401||status==408||status==429||status>=500; }
    }
    @Override public JSONObject request(String path,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
            connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Accept","application/json");
            if(body!=null){
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(java.io.OutputStream output=connection.getOutputStream()){output.write(bytes);}
            }
            int status=connection.getResponseCode();
            InputStream input=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            if(input!=null)try(InputStream stream=input){byte[] bytes=new byte[4096];int n;while((n=stream.read(bytes))!=-1){output.write(bytes,0,n);if(output.size()>1_000_000)throw new Failure(0,"پاسخ سرور بیش از حد بزرگ است.");}}
            JSONObject result;
            try{result=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));}
            catch(Exception error){throw new Failure(status,"سرور پاسخ قابل‌استفاده‌ای نداد؛ اطلاعات روی گوشی محفوظ است.");}
            if(status<200||status>=300)throw new Failure(status,result.optString("error","ارتباط با سرور کامل نشد."));
            return result;
        }finally{connection.disconnect();}
    }
}
