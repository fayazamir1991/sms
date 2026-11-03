package ir.delneveshte.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Product-server updater. Android's package installer always asks the user to approve installation. */
final class AppUpdater {
    static final class Release { final String version,url,sha256; Release(String v,String u,String s){version=v;url=u;sha256=s;} }
    interface Listener { void ready(Release release,String error); }
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"delneveshte-update-check");thread.setDaemon(true);return thread;});
    private static final String PREFS="app_updates";
    private AppUpdater(){}

    static void check(Context context,Listener listener){
        WORK.execute(()->{Release release=null;String error=null;
            try{String base=BuildConfig.API_BASE_URL.replaceAll("/+$","");if(!base.startsWith("https://"))throw new java.io.IOException("نشانی HTTPS سرور به‌روزرسانی تنظیم نشده است");HttpURLConnection c=(HttpURLConnection)new URL(base+"/api/app/latest").openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestProperty("Accept","application/json");
                try{int code=c.getResponseCode();if(code!=200)throw new java.io.IOException("سرور به‌روزرسانی پاسخ "+code+" داد");try(InputStream in=c.getInputStream();java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){byte[] data=new byte[4096];int n;while((n=in.read(data))!=-1)buffer.write(data,0,n);JSONObject json=new JSONObject(buffer.toString("UTF-8"));String version=json.optString("version","");String apk=json.optString("url","");String sha=json.optString("sha256","").toLowerCase();
                    if(!version.isEmpty()){String expected=base+"/api/app/releases/";if(!apk.startsWith(expected)||!apk.matches("https://[^/]+/api/app/releases/delneveshte-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk")||!sha.matches("[a-f0-9]{64}"))throw new java.io.IOException("اطلاعات نسخهٔ سرور معتبر نیست");if(compare(version,BuildConfig.VERSION_NAME)>0)release=new Release(version,apk,sha);}
                }}finally{c.disconnect();}
            }catch(Exception e){error=e.getMessage()==null?"اتصال به سرور به‌روزرسانی برقرار نشد":e.getMessage();}
            Release result=release;String problem=error;if(context instanceof Activity)((Activity)context).runOnUiThread(()->listener.ready(result,problem));
        });
    }
    static int compare(String a,String b){String[] x=a.replaceFirst("^[vV]","").split("\\."),y=b.replaceFirst("^[vV]","").split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int p=i<x.length?digits(x[i]):0,q=i<y.length?digits(y[i]):0;if(p!=q)return Integer.compare(p,q);}return 0;}
    private static int digits(String s){try{return Integer.parseInt(s.replaceAll("[^0-9].*$",""));}catch(Exception e){return 0;}}
    static long download(Context context,Release release){DownloadManager dm=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);DownloadManager.Request r=new DownloadManager.Request(Uri.parse(release.url));r.setTitle("به‌روزرسانی دل‌نوشته");r.setDescription("در حال دریافت نسخهٔ "+release.version);r.setMimeType("application/vnd.android.package-archive");r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);r.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"delneveshte-"+release.version+".apk");long id=dm.enqueue(r);context.getSharedPreferences(PREFS,0).edit().putLong("download_id",id).putString("version",release.version).putString("sha256",release.sha256).apply();return id;}
    static boolean installCompleted(Activity activity){
        android.content.SharedPreferences p=activity.getSharedPreferences(PREFS,0);long id=p.getLong("download_id",-1);if(id<0)return false;
        DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
        try(android.database.Cursor c=dm.query(q)){if(c==null||!c.moveToFirst())return false;int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));if(status==DownloadManager.STATUS_FAILED){p.edit().remove("download_id").apply();activity.runOnUiThread(()->android.widget.Toast.makeText(activity,"دریافت به‌روزرسانی انجام نشد",android.widget.Toast.LENGTH_LONG).show());return false;}if(status!=DownloadManager.STATUS_SUCCESSFUL)return false;
            Uri uri=dm.getUriForDownloadedFile(id);if(uri==null)return false;if(!sameSigner(activity,uri,p.getString("sha256",""))){p.edit().remove("download_id").apply();android.widget.Toast.makeText(activity,"فایل یا امضای APK با نسخهٔ نصب‌شده سازگار نیست؛ نصب لغو شد",android.widget.Toast.LENGTH_LONG).show();return false;}
            if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){if(!p.getBoolean("permission_prompted",false)){p.edit().putBoolean("permission_prompted",true).apply();new android.app.AlertDialog.Builder(activity).setMessage("برای نصب به‌روزرسانی، اجازهٔ نصب برنامه از این منبع را در تنظیمات اندروید فعال کن.").setNegativeButton("بعداً",(d,w)->{}).setPositiveButton("رفتن به تنظیمات",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())))).show();}return false;}
            Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);p.edit().remove("download_id").remove("permission_prompted").apply();return true;
        }catch(Exception e){return false;}
    }
    private static boolean sameSigner(Context c,Uri uri,String expectedHash){File f=null;try{f=File.createTempFile("update-verify-",".apk",c.getCacheDir());try(InputStream in=c.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(f)){if(in==null)return false;byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}if(expectedHash==null||!expectedHash.matches("[a-f0-9]{64}")||!expectedHash.equals(hashFile(f)))return false;
        PackageManager pm=c.getPackageManager();PackageInfo installed,archive;if(Build.VERSION.SDK_INT>=28){installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);if(archive==null||!c.getPackageName().equals(archive.packageName)||archive.getLongVersionCode()<=installed.getLongVersionCode())return false;return java.util.Arrays.equals(fingerprints(installed.signingInfo.getApkContentsSigners()),fingerprints(archive.signingInfo.getApkContentsSigners()));}
        installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNATURES);return archive!=null&&c.getPackageName().equals(archive.packageName)&&archive.versionCode>installed.versionCode&&java.util.Arrays.equals(fingerprints(installed.signatures),fingerprints(archive.signatures));
    }catch(Exception e){return false;}finally{if(f!=null)f.delete();}}
    private static String[] fingerprints(android.content.pm.Signature[] signatures)throws Exception{if(signatures==null)return new String[0];String[] out=new String[signatures.length];for(int i=0;i<signatures.length;i++){byte[] d=MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(java.util.Locale.ROOT,"%02x",x));out[i]=b.toString();}java.util.Arrays.sort(out);return out;}
    private static String hashFile(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new java.io.FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",x));return s.toString();}
}
