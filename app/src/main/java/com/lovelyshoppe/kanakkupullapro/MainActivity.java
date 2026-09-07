package com.lovelyshoppe.kanakkupullapro;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.app.DownloadManager;
import android.database.Cursor;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.*;

public class MainActivity extends Activity {
  private static final UUID SPP=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static final int REQ_BT=501, REQ_FILE=502, REQ_CAMERA=504;
  private static final String UPDATE_JSON_URL="https://raw.githubusercontent.com/lovely3971/kpro-printer-android/main/update.json";
  private static final String UPDATE_PREFS="kpro_update_prefs";
  private long updateDownloadId=-1; private String downloadedApkName="kanakku-pulla-pro-update.apk";
  private int pendingLatestVersionCode=-1;
  private WebView web; private BluetoothAdapter bt; private BluetoothSocket socket; private final Queue<byte[]> pendingQueue=new ArrayDeque<>(); private final Object printLock=new Object(); private boolean chooserOpen=false;
  private ValueCallback<Uri[]> fileCallback;
  private PermissionRequest pendingWebPermissionRequest;

  @Override public void onCreate(Bundle b){
    super.onCreate(b);bt=BluetoothAdapter.getDefaultAdapter();setupWeb();registerUpdateReceiver();
    resumePendingDownloadIfAny();
    new Handler(Looper.getMainLooper()).postDelayed(()->{checkPendingUpdateMismatch();checkForAppUpdate(false);},2500);
  }
  @Override protected void onResume(){
    super.onResume();
    // Returning from another app/settings must never lose a completed update.
    new Handler(Looper.getMainLooper()).postDelayed(this::resumePendingDownloadIfAny,700);
  }

  private void setupWeb(){
    web=new WebView(this);setContentView(web);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setAllowFileAccess(true);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    web.addJavascriptInterface(new PrinterBridge(),"KPRO_NATIVE");
    web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){Uri u=r.getUrl();String scheme=u.getScheme();if("http".equals(scheme)||"https".equals(scheme))return false;try{startActivity(new Intent(Intent.ACTION_VIEW,u));return true;}catch(Exception e){return false;}}});
    web.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> cb,FileChooserParams p){if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=cb;try{startActivityForResult(p.createIntent(),REQ_FILE);}catch(Exception e){fileCallback=null;return false;}return true;}
      @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback cb){if(Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)cb.invoke(origin,true,false);else{requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},503);cb.invoke(origin,true,false);}}
      @Override public void onPermissionRequest(PermissionRequest request){
        runOnUiThread(()->{
          boolean needsCamera=false;
          for(String res:request.getResources()) if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) needsCamera=true;
          if(needsCamera && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            pendingWebPermissionRequest=request;
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
          }else{
            request.grant(request.getResources());
          }
        });
      }
    });
    web.loadUrl("file:///android_asset/www/index.html");
  }
  @Override protected void onActivityResult(int r,int c,Intent data){super.onActivityResult(r,c,data);if(r==REQ_FILE&&fileCallback!=null){fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(c,data));fileCallback=null;}}
  @Override public void onBackPressed(){
    if(web==null){super.onBackPressed();return;}
    web.evaluateJavascript("(function(){try{var a=document.querySelector('.screen.active');if(a){if(a.id==='screenBillingApp'&&typeof kpBillingBack==='function'){kpBillingBack();return 'handled';}if((a.id==='screenLogin'||a.id==='screenCreateShop'||a.id==='screenJoinShop')&&typeof showScreen==='function'){showScreen('shop');return 'handled';}if(a.id==='screenShop'&&typeof kpBackFromShop==='function'){kpBackFromShop();return 'handled';}}}catch(e){}return 'no';})()", v->{
      if(v!=null&&v.contains("handled"))return;
      if(web.canGoBack())web.goBack(); else super.onBackPressed();
    });
  }


  private void registerUpdateReceiver(){
    IntentFilter f=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    if(Build.VERSION.SDK_INT>=33) registerReceiver(updateReceiver,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updateReceiver,f);
  }
  private final BroadcastReceiver updateReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context c,Intent i){
      long id=i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
      if(id==updateDownloadId) installDownloadedUpdate();
    }
  };
  private void checkForAppUpdate(boolean manual){
    new Thread(()->{
      HttpURLConnection con=null;
      try{
        con=(HttpURLConnection)new URL(UPDATE_JSON_URL+"?t="+System.currentTimeMillis()).openConnection();
        con.setConnectTimeout(8000);con.setReadTimeout(8000);con.setUseCaches(false);
        BufferedReader br=new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line);br.close();
        JSONObject j=new JSONObject(sb.toString());
        int latest=j.getInt("versionCode"); String name=j.optString("versionName","New update"); String apk=j.getString("apkUrl"); String notes=j.optString("notes","Latest improvements and fixes.");
        int current=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
        runOnUiThread(()->{if(latest>current){pendingLatestVersionCode=latest;showUpdateDialog(name,notes,apk);}else if(manual)toast("App already up to date ✓");});
      }catch(Exception e){if(manual)runOnUiThread(()->toast("Update check failed — internet check pannunga"));}
      finally{if(con!=null)con.disconnect();}
    }).start();
  }
  private void showUpdateDialog(String name,String notes,String apkUrl){
    new AlertDialog.Builder(this).setTitle("Kanakku Pulla PRO Update")
      .setMessage(name+" available.\n\n"+notes)
      .setPositiveButton("Update Now",(d,w)->downloadUpdate(apkUrl))
      .setNegativeButton("Later",null).show();
  }
  private void downloadUpdate(String apkUrl){
    try{
      File existing=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),downloadedApkName);
      if(existing.exists()) existing.delete();
      DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
      DownloadManager.Request r=new DownloadManager.Request(Uri.parse(apkUrl));
      r.setTitle("Kanakku Pulla PRO Update");r.setDescription("Downloading latest version…");
      r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      r.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,downloadedApkName);
      updateDownloadId=dm.enqueue(r);
      getSharedPreferences(UPDATE_PREFS,MODE_PRIVATE).edit()
        .putLong("pendingDownloadId",updateDownloadId)
        .putInt("pendingDownloadTargetVersion",pendingLatestVersionCode)
        .apply();
      toast("Update downloading…");
    }catch(Exception e){toast("Update download failed");}
  }
  private void installDownloadedUpdate(){
    try{
      if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
        toast("Allow 'Install unknown apps', then tap Update Now again");
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;
      }
      File apk=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),downloadedApkName);
      if(!apk.exists()){toast("Downloaded update file not found");return;}
      if(pendingLatestVersionCode>0){
        int currentBeforeInstall=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
        getSharedPreferences(UPDATE_PREFS,MODE_PRIVATE).edit()
          .putInt("pendingFromVersion",currentBeforeInstall)
          .putInt("pendingToVersion",pendingLatestVersionCode)
          .apply();
      }
      // Never open a stale/old APK even if GitHub/CDN returned an older cached file.
      try{
        android.content.pm.PackageInfo pi=getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
        if(pi==null){ apk.delete(); toast("Downloaded update is invalid. Please try again."); return; }
        long apkCode=Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;
        long currentCode=Build.VERSION.SDK_INT>=28?getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode():getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
        if(apkCode<=currentCode || (pendingLatestVersionCode>0 && apkCode<pendingLatestVersionCode)){
          apk.delete();
          getSharedPreferences(UPDATE_PREFS,MODE_PRIVATE).edit().remove("pendingDownloadId").apply();
          toast("Old update file blocked. Checking latest update again…");
          checkForAppUpdate(false);
          return;
        }
      }catch(Exception e){ apk.delete(); toast("Update verification failed. Please try again."); return; }
      Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",apk);
      Intent in=new Intent(Intent.ACTION_VIEW);in.setDataAndType(uri,"application/vnd.android.package-archive");
      in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(in);
    }catch(Exception e){toast("Update install open failed: "+e.getMessage());}
  }
  private void resumePendingDownloadIfAny(){
    try{
      SharedPreferences sp=getSharedPreferences(UPDATE_PREFS,MODE_PRIVATE);
      long id=sp.getLong("pendingDownloadId",-1);
      if(id<=0) return;
      updateDownloadId=id;
      if(pendingLatestVersionCode<=0) pendingLatestVersionCode=sp.getInt("pendingDownloadTargetVersion",-1);
      DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
      Cursor c=dm.query(new DownloadManager.Query().setFilterById(id));
      if(c==null) return;
      try{
        if(c.moveToFirst()){
          int statusIdx=c.getColumnIndex(DownloadManager.COLUMN_STATUS);
          int status=statusIdx>=0?c.getInt(statusIdx):-1;
          if(status==DownloadManager.STATUS_SUCCESSFUL){
            installDownloadedUpdate();
          }else if(status==DownloadManager.STATUS_FAILED){
            sp.edit().remove("pendingDownloadId").apply();
          }
          // STATUS_RUNNING/STATUS_PENDING: still going, the broadcast receiver (if we're still alive) will catch it
        }else{
          sp.edit().remove("pendingDownloadId").apply();
        }
      }finally{ c.close(); }
    }catch(Exception ignored){}
  }
  private void checkPendingUpdateMismatch(){
    try{
      SharedPreferences sp=getSharedPreferences(UPDATE_PREFS,MODE_PRIVATE);
      int pendingTo=sp.getInt("pendingToVersion",-1);
      if(pendingTo<=0) return;
      int current=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
      if(current>=pendingTo){
        sp.edit().remove("pendingToVersion").remove("pendingFromVersion").apply();
        return;
      }
      showSignatureMismatchDialog();
    }catch(Exception ignored){}
  }
  private void showSignatureMismatchDialog(){
    new AlertDialog.Builder(this).setTitle("Update Could Not Install")
      .setMessage("Last time, the update did not actually install — this app is still on the older version. This usually happens when the currently installed app was signed with a different security key than the new update.\n\nOru vaati mattum: please UNINSTALL this app completely, then install the latest APK fresh. After that, future updates will apply automatically without any issue.")
      .setPositiveButton("Uninstall Now",(d,w)->{
        try{ startActivity(new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+getPackageName()))); }
        catch(Exception e){ toast("Settings > Apps > Kanakku Pulla PRO > Uninstall pannunga"); }
      })
      .setNegativeButton("Later",null).setCancelable(true).show();
  }

  public class PrinterBridge {
    @JavascriptInterface public boolean available(){return bt!=null;}
    @JavascriptInterface public String printerName(){try{return socket!=null&&socket.isConnected()?socket.getRemoteDevice().getName():"Android Bluetooth Printer";}catch(Exception e){return "Android Bluetooth Printer";}}
    @JavascriptInterface public void connect(){runOnUiThread(()->ensurePermissionThenChoose());}
    @JavascriptInterface public void disconnect(){disconnectSocket();}
    @JavascriptInterface public void writeBase64(String b64){
      try{
        byte[] d=Base64.decode(b64,Base64.DEFAULT);
        synchronized(pendingQueue){
          if(socket!=null&&socket.isConnected()){send(d);return;}
          pendingQueue.add(d);
        }
        runOnUiThread(()->ensurePermissionThenChoose());
      }catch(Exception e){toast("Print data error");}
    }
    @JavascriptInterface public void checkUpdate(){runOnUiThread(()->checkForAppUpdate(true));}
    @JavascriptInterface public void shareFile(String base64,String filename,String mime){
      runOnUiThread(()->{
        try{
          byte[] bytes=Base64.decode(base64,Base64.DEFAULT);
          File dir=new File(getCacheDir(),"shared");
          if(!dir.exists())dir.mkdirs();
          File f=new File(dir,filename==null||filename.isEmpty()?"share.pdf":filename);
          FileOutputStream fos=new FileOutputStream(f);
          fos.write(bytes);fos.close();
          Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".fileprovider",f);
          Intent share=new Intent(Intent.ACTION_SEND);
          share.setType(mime==null||mime.isEmpty()?"application/octet-stream":mime);
          share.putExtra(Intent.EXTRA_STREAM,uri);
          share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(Intent.createChooser(share,"Share"));
        }catch(Exception e){toast("Share failed: "+e.getMessage());}
      });
    }
  }
  private boolean btPerm(){
    if(Build.VERSION.SDK_INT<31)return true;
    return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
      && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
  }
  private void ensurePermissionThenChoose(){
    if(bt==null){toast("Bluetooth not supported");return;}
    if(Build.VERSION.SDK_INT>=31&&!btPerm()){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},REQ_BT);return;}
    if(!bt.isEnabled()){toast("Bluetooth ON pannunga");try{startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}catch(Exception ignored){}return;}
    if(chooserOpen)return;
    chooserOpen=true;choosePrinter();
  }
  private void choosePrinter(){
    try{
      List<BluetoothDevice> ds=new ArrayList<>(bt.getBondedDevices());
      if(ds.isEmpty()){chooserOpen=false;toast("Android Bluetooth Settings-la printer pair pannunga first");startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}
      String[] names=new String[ds.size()];for(int i=0;i<ds.size();i++)names[i]=(ds.get(i).getName()==null?"Bluetooth Printer":ds.get(i).getName())+"\n"+ds.get(i).getAddress();
      new AlertDialog.Builder(this).setTitle("Select Bluetooth Printer")
        .setItems(names,(d,w)->{chooserOpen=false;connectDevice(ds.get(w));})
        .setOnCancelListener(d->chooserOpen=false)
        .setNegativeButton("Cancel",(d,w)->chooserOpen=false).show();
    }catch(Exception e){chooserOpen=false;toast("Printer list open aagala");}
  }
  private void connectDevice(BluetoothDevice d){toast("Connecting "+d.getName()+"…");new Thread(()->{try{disconnectSocket();bt.cancelDiscovery();BluetoothSocket s=d.createRfcommSocketToServiceRecord(SPP);s.connect();socket=s;runOnUiThread(()->{toast("Connected: "+d.getName());web.evaluateJavascript("try{kpBtSetStatus('Connected — "+js(d.getName())+"',true)}catch(e){}",null);});flushPending();}catch(Exception e){runOnUiThread(()->toast("Connect failed — printer paired/ON check pannunga"));}}).start();}
  private void send(byte[] d){new Thread(()->{try{synchronized(printLock){OutputStream o=socket.getOutputStream();o.write(d);o.flush();}runOnUiThread(()->toast("Print data sent ✓"));}catch(Exception e){runOnUiThread(()->toast("Print failed — reconnect printer"));disconnectSocket();}}).start();}
  private void flushPending(){
    new Thread(()->{
      try{
        OutputStream o=socket.getOutputStream();
        while(true){
          byte[] d; synchronized(pendingQueue){d=pendingQueue.poll();}
          if(d==null)break; o.write(d);o.flush();try{Thread.sleep(120);}catch(Exception ignored){}
        }
        runOnUiThread(()->toast("Print data sent ✓"));
      }catch(Exception e){runOnUiThread(()->toast("Print failed — reconnect printer"));disconnectSocket();}
    }).start();
  }
  private void disconnectSocket(){try{if(socket!=null)socket.close();}catch(Exception ignored){}socket=null;}
  private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show());}
  private String js(String s){if(s==null)return "Printer";return s.replace("\\","\\\\").replace("'","\\'");}
  @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
    super.onRequestPermissionsResult(r,p,g);
    if(r==REQ_BT&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)ensurePermissionThenChoose();
    if(r==REQ_CAMERA&&pendingWebPermissionRequest!=null){
      if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
      else pendingWebPermissionRequest.deny();
      pendingWebPermissionRequest=null;
    }
  }
  @Override protected void onDestroy(){try{unregisterReceiver(updateReceiver);}catch(Exception ignored){}disconnectSocket();if(web!=null)web.destroy();super.onDestroy();}
}
