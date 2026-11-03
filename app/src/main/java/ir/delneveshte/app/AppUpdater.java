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
import org.json.JSONArray;
import org.json.JSONObject;

/** GitHub Releases updater. Android's package installer always asks the user to approve installation. */
final class AppUpdater {
    static final class Release { final String version,url; Release(String v,String u){version=v;url=u;} }
    interface Listener { void ready(Release release,String error); }
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private static final String PREFS="app_updates",LATEST="https://api.github.com/repos/fayazamir1991/sms/releases/latest";
    private AppUpdater(){}

    static void check(Context context,Listener listener){
        Context app=context.getApplicationContext();WORK.execute(()->{Release release=null;String error=null;
            try{HttpURLConnection c=(HttpURLConnection)new URL(LATEST).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("User-Agent","Delneveshte-Android");
                try(InputStream in=c.getInputStream();java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){byte[] data=new byte[4096];int n;while((n=in.read(data))!=-1)buffer.write(data,0,n);JSONObject json=new JSONObject(buffer.toString("UTF-8"));String tag=json.optString("tag_name").replaceFirst("^[vV]","");JSONArray assets=json.optJSONArray("assets");String apk=null;
                    if(assets!=null)for(int i=0;i<assets.length();i++){JSONObject a=assets.optJSONObject(i);if(a!=null&&a.optString("name").matches("delneveshte-.*\\.apk")){apk=a.optString("browser_download_url");break;}}
                    if(apk==null||!apk.startsWith("https://github.com/fayazamir1991/sms/releases/download/"))throw new java.io.IOException("APK معتبر در ریلیز نیست");
                    if(compare(tag,BuildConfig.VERSION_NAME)>0)release=new Release(tag,apk);
                }finally{c.disconnect();}
            }catch(Exception e){error=e.getMessage()==null?"اتصال به گیت‌هاب برقرار نشد":e.getMessage();}
            Release result=release;String problem=error;if(context instanceof Activity)((Activity)context).runOnUiThread(()->listener.ready(result,problem));
        });
    }
    static int compare(String a,String b){String[] x=a.replaceFirst("^[vV]","").split("\\."),y=b.replaceFirst("^[vV]","").split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int p=i<x.length?digits(x[i]):0,q=i<y.length?digits(y[i]):0;if(p!=q)return Integer.compare(p,q);}return 0;}
    private static int digits(String s){try{return Integer.parseInt(s.replaceAll("[^0-9].*$",""));}catch(Exception e){return 0;}}
    static long download(Context context,Release release){DownloadManager dm=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);DownloadManager.Request r=new DownloadManager.Request(Uri.parse(release.url));r.setTitle("به‌روزرسانی دل‌نوشته");r.setDescription("در حال دریافت نسخهٔ "+release.version);r.setMimeType("application/vnd.android.package-archive");r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);r.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"delneveshte-"+release.version+".apk");long id=dm.enqueue(r);context.getSharedPreferences(PREFS,0).edit().putLong("download_id",id).putString("version",release.version).apply();return id;}
    static boolean installCompleted(Activity activity){
        android.content.SharedPreferences p=activity.getSharedPreferences(PREFS,0);long id=p.getLong("download_id",-1);if(id<0)return false;
        DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
        try(android.database.Cursor c=dm.query(q)){if(c==null||!c.moveToFirst())return false;int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));if(status==DownloadManager.STATUS_FAILED){p.edit().remove("download_id").apply();activity.runOnUiThread(()->android.widget.Toast.makeText(activity,"دریافت به‌روزرسانی انجام نشد",android.widget.Toast.LENGTH_LONG).show());return false;}if(status!=DownloadManager.STATUS_SUCCESSFUL)return false;
            Uri uri=dm.getUriForDownloadedFile(id);if(uri==null)return false;if(!sameSigner(activity,uri)){p.edit().remove("download_id").apply();android.widget.Toast.makeText(activity,"امضای APK با برنامهٔ نصب‌شده سازگار نیست؛ نصب لغو شد",android.widget.Toast.LENGTH_LONG).show();return false;}
            if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){if(!p.getBoolean("permission_prompted",false)){p.edit().putBoolean("permission_prompted",true).apply();new android.app.AlertDialog.Builder(activity).setMessage("برای نصب به‌روزرسانی، اجازهٔ نصب برنامه از این منبع را در تنظیمات اندروید فعال کن.").setNegativeButton("بعداً",(d,w)->{}).setPositiveButton("رفتن به تنظیمات",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())))).show();}return false;}
            Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,"application/vnd.android.package-archive");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);activity.startActivity(intent);p.edit().remove("download_id").remove("permission_prompted").apply();return true;
        }catch(Exception e){return false;}
    }
    private static boolean sameSigner(Context c,Uri uri){File f=null;try{f=File.createTempFile("update-verify-",".apk",c.getCacheDir());try(InputStream in=c.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(f)){if(in==null)return false;byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        PackageManager pm=c.getPackageManager();PackageInfo installed,archive;if(Build.VERSION.SDK_INT>=28){installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);if(archive==null||!c.getPackageName().equals(archive.packageName)||archive.getLongVersionCode()<=installed.getLongVersionCode())return false;return java.util.Arrays.equals(fingerprints(installed.signingInfo.getApkContentsSigners()),fingerprints(archive.signingInfo.getApkContentsSigners()));}
        installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);archive=pm.getPackageArchiveInfo(f.getAbsolutePath(),PackageManager.GET_SIGNATURES);return archive!=null&&c.getPackageName().equals(archive.packageName)&&archive.versionCode>installed.versionCode&&java.util.Arrays.equals(fingerprints(installed.signatures),fingerprints(archive.signatures));
    }catch(Exception e){return false;}finally{if(f!=null)f.delete();}}
    private static String[] fingerprints(android.content.pm.Signature[] signatures)throws Exception{if(signatures==null)return new String[0];String[] out=new String[signatures.length];for(int i=0;i<signatures.length;i++){byte[] d=MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(java.util.Locale.ROOT,"%02x",x));out[i]=b.toString();}java.util.Arrays.sort(out);return out;}
}
