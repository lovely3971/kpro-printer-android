package com.lovelyshoppe.kanakkupullapro;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
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
  private WebView web; private BluetoothAdapter bt; private BluetoothSocket socket; private final Queue<byte[]> pendingQueue=new ArrayDeque<>(); private final Object printLock=new Object(); private boolean chooserOpen=false;
  private ValueCallback<Uri[]> fileCallback;
  private PermissionRequest pendingWebPermissionRequest;

  @Override public void onCreate(Bundle b){super.onCreate(b);bt=BluetoothAdapter.getDefaultAdapter();setupWeb();}
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
  @Override protected void onDestroy(){disconnectSocket();if(web!=null)web.destroy();super.onDestroy();}
}
