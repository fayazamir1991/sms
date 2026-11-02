package ir.delneveshte.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/** Install-scoped credentials are app-private, never a MAC address or IMEI. */
public final class CloudConfig {
    private static final Object IDENTITY_LOCK=new Object();
    private final Context app;
    private final SharedPreferences prefs;
    public CloudConfig(Context c) {
        app=c.getApplicationContext();prefs=app.getSharedPreferences("cloud",0);
        synchronized(IDENTITY_LOCK){
            String managed=normalizeUrl(BuildConfig.API_BASE_URL);
            if(prefs.getInt("managed_policy",0)!=1||!managed.equals(prefs.getString("managed_url",""))){
                if(!prefs.edit().remove("url").remove("enabled").putInt("managed_policy",1).putString("managed_url",managed)
                        .putInt("generation",prefs.getInt("generation",0)+1).remove("registered_url").remove("registered_generation")
                        .putLong("last_catalog",0).commit())throw new IllegalStateException("آماده‌سازی اتصال انجام نشد.");
            }
        }
    }
    public String baseUrl() { return normalizeUrl(BuildConfig.API_BASE_URL); }
    public boolean enabled() { return !baseUrl().isEmpty(); }
    public int generation() { return prefs.getInt("generation",0); }
    public boolean registered() { return baseUrl().equals(prefs.getString("registered_url",""))&&prefs.getInt("registered_generation",-1)==generation(); }
    public void registrationSucceeded(int expectedGeneration) { if(generation()!=expectedGeneration)return;prefs.edit().putString("registered_url",baseUrl()).putInt("registered_generation",generation()).apply(); }
    public long lastSync() { return prefs.getLong("last_sync",0); }
    public long lastCatalog() { return prefs.getLong("last_catalog",0); }
    public String lastError() { return prefs.getString("error",""); }
    public static String normalizeUrl(String value) {
        String text=value==null?"":value.trim();if(text.isEmpty())return "";
        try {
            URI uri=new URI(text);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
                    ||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/")))
                throw new IllegalArgumentException();
            return "https://"+uri.getRawAuthority();
        }catch(Exception e){throw new IllegalArgumentException("آدرس سرور باید یک آدرس HTTPS معتبر و بدون مسیر اضافی باشد.");}
    }
    public String installationId() { synchronized(IDENTITY_LOCK) {
        String id=prefs.getString("installation_id",null);
        if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("installation_id",id).commit();}return id;
    } }
    public String credential() { synchronized(IDENTITY_LOCK) {
        String secret=prefs.getString("credential",null);
        if(secret==null){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);secret=hex(bytes);prefs.edit().putString("credential",secret).commit();}return secret;
    } }
    public String deviceHash() {
        String id=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ANDROID_ID);
        if(id==null||id.trim().isEmpty())id=installationId();
        try{return hex(MessageDigest.getInstance("SHA-256").digest((app.getPackageName()+":"+id).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("شناسهٔ نصب آماده نشد.");}
    }
    private static String hex(byte[] bytes) { StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString(); }
    public void success() { prefs.edit().putLong("last_sync",System.currentTimeMillis()).putString("error","").apply(); }
    public void catalogUpdated() { prefs.edit().putLong("last_catalog",System.currentTimeMillis()).apply(); }
    public void catalogUpdatedReset() { prefs.edit().putLong("last_catalog",0).putLong("last_sync",0).apply(); }
    public void error(String message) { prefs.edit().putString("error",message==null?"همگام‌سازی کامل نشد.":message.substring(0,Math.min(180,message.length()))).apply(); }
}
