package com.novra.video;

import android.Manifest;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.content.ContentUris;
import android.content.Intent;
import android.content.ClipData;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import android.database.Cursor;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name="NovraMedia", permissions={
  @Permission(alias="legacy", strings={Manifest.permission.READ_EXTERNAL_STORAGE}),
  @Permission(alias="videos", strings={Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}),
  @Permission(alias="audio", strings={Manifest.permission.READ_MEDIA_AUDIO})
})
public class NovraMediaPlugin extends Plugin {
  private ContentObserver mediaObserver;

  @Override
  public void load() {
    super.load();
    Handler handler = new Handler(Looper.getMainLooper());
    mediaObserver = new ContentObserver(handler) {
      @Override
      public void onChange(boolean selfChange, Uri uri) {
        JSObject payload = new JSObject();
        payload.put("uri", uri == null ? "" : uri.toString());
        notifyListeners("mediaChanged", payload);
      }
    };
    getContext().getContentResolver().registerContentObserver(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
    );
    getContext().getContentResolver().registerContentObserver(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
    );
  }
  @PluginMethod
  public void requestAccess(PluginCall call) {
    String kind=call.getString("kind","video");
    if(!"video".equals(kind)&&!"audio".equals(kind)){call.reject("Unsupported media kind.");return;}
    String alias=Build.VERSION.SDK_INT>=33?("audio".equals(kind)?"audio":"videos"):"legacy";
    if(hasAccess(kind)){JSObject out=new JSObject();out.put("granted",true);out.put("kind",kind);call.resolve(out);return;}
    if(getPermissionState(alias)==PermissionState.GRANTED){JSObject out=new JSObject();out.put("granted",hasAccess(kind));out.put("kind",kind);call.resolve(out);return;}
    requestPermissionForAlias(alias,call,"accessResult");
  }
  @PermissionCallback
  private void accessResult(PluginCall call){String kind=call.getString("kind","video");JSObject out=new JSObject();out.put("granted",hasAccess(kind));out.put("kind",kind);call.resolve(out);}
  @PluginMethod
  public void checkAccess(PluginCall call){String kind=call.getString("kind","video");JSObject out=new JSObject();out.put("granted",hasAccess(kind));out.put("kind",kind);call.resolve(out);}

  @PluginMethod
  public void getThumbnail(PluginCall call) {
    String rawUri = call.getString("uri");
    if (rawUri == null || !rawUri.startsWith("content://")) { call.reject("Invalid media URI."); return; }
    if (!hasAccess("video")) { call.reject("Video permission has not been granted.", "PERMISSION_REQUIRED"); return; }
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    Bitmap frame = null;
    Bitmap scaled = null;
    try {
      retriever.setDataSource(getContext(), Uri.parse(rawUri));
      frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      if (frame == null) frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      if (frame == null) { JSObject empty = new JSObject(); empty.put("data", ""); call.resolve(empty); return; }
      int maxDimension = 480;
      float scale = Math.min(1f, maxDimension / (float)Math.max(frame.getWidth(), frame.getHeight()));
      Bitmap output = frame;
      if (scale < 1f) {
        scaled = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth()*scale)), Math.max(1, Math.round(frame.getHeight()*scale)), true);
        output = scaled;
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      output.compress(Bitmap.CompressFormat.JPEG, 74, bytes);
      JSObject result = new JSObject();
      result.put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
      call.resolve(result);
    } catch (Exception error) {
      call.reject("Could not generate video thumbnail.", null, error);
    } finally {
      if (scaled != null && !scaled.isRecycled()) scaled.recycle();
      if (frame != null && !frame.isRecycled()) frame.recycle();
      try { retriever.release(); } catch (Exception ignored) {}
    }
  }

  @PluginMethod
  public void listMedia(PluginCall call) {
    String kind=call.getString("kind","video");
    if(!"video".equals(kind)&&!"audio".equals(kind)){call.reject("Unsupported media kind.");return;}
    if(!hasAccess(kind)){call.reject("Media permission has not been granted.","PERMISSION_REQUIRED");return;}
    Uri collection="audio".equals(kind)?MediaStore.Audio.Media.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    String[] projection="audio".equals(kind)?new String[]{
      MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.MIME_TYPE,
      MediaStore.Audio.Media.SIZE,MediaStore.Audio.Media.DATE_ADDED,MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM,MediaStore.Audio.Media.TITLE
    }:new String[]{
      MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.MIME_TYPE,
      MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DATE_ADDED,MediaStore.Video.Media.DURATION,
      MediaStore.Video.Media.WIDTH,MediaStore.Video.Media.HEIGHT
    };
    JSArray items=new JSArray();int count=0;
    try(Cursor cursor=getContext().getContentResolver().query(collection,projection,null,null,MediaStore.MediaColumns.DATE_ADDED+" DESC")){
      if(cursor!=null){
        int idCol=cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
        int nameCol=cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
        int mimeCol=cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
        int sizeCol=cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
        int dateCol=cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED);
        int durCol=cursor.getColumnIndex(MediaStore.MediaColumns.DURATION);
        int artistCol="audio".equals(kind)?cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST):-1;
        int albumCol="audio".equals(kind)?cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM):-1;
        int titleCol="audio".equals(kind)?cursor.getColumnIndex(MediaStore.Audio.Media.TITLE):-1;
        int widthCol="video".equals(kind)?cursor.getColumnIndex(MediaStore.Video.Media.WIDTH):-1;
        int heightCol="video".equals(kind)?cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT):-1;
        while(cursor.moveToNext()&&count<3000){
          long rowId=cursor.getLong(idCol);Uri itemUri=ContentUris.withAppendedId(collection,rowId);
          JSObject item=new JSObject();
          item.put("id","android:"+kind+":"+rowId);item.put("uri",itemUri.toString());
          item.put("name",str(cursor,nameCol,"بدون اسم"));
          item.put("mimeType",str(cursor,mimeCol,"audio".equals(kind)?"audio/*":"video/*"));
          item.put("size",num(cursor,sizeCol));item.put("dateAdded",num(cursor,dateCol));item.put("durationMs",num(cursor,durCol));
          if(artistCol>=0)item.put("artist",str(cursor,artistCol,""));
          if(albumCol>=0)item.put("album",str(cursor,albumCol,""));
          if(titleCol>=0)item.put("title",str(cursor,titleCol,""));
          if(widthCol>=0)item.put("width",num(cursor,widthCol));
          if(heightCol>=0)item.put("height",num(cursor,heightCol));
          items.put(item);count++;
        }
      }
      JSObject out=new JSObject();out.put("kind",kind);out.put("count",count);out.put("items",items);call.resolve(out);
    }catch(Exception error){call.reject("Could not read device media.",null,error);}
  }

  @PluginMethod
  public void consumeSharedFiles(PluginCall call) {
    android.app.Activity activity = getActivity();
    Intent intent = activity == null ? null : activity.getIntent();
    String sharedText = "";
    try {
      if (intent != null) {
        String action = intent.getAction();
        boolean isShare = Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (isShare) {
          sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
          ArrayList<Uri> uris = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          ClipData clip = intent.getClipData();
          if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
              Uri uri = clip.getItemAt(i).getUri();
              addSharedUri(uris, seen, uri);
            }
          }
          if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) for (Uri uri : streams) addSharedUri(uris, seen, uri);
          } else {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            addSharedUri(uris, seen, stream);
          }
          if (uris.isEmpty() && intent.getData() != null) addSharedUri(uris, seen, intent.getData());
          File directory = new File(getContext().getFilesDir(), "novra-share-inbox");
          if (!directory.exists() && !directory.mkdirs()) throw new Exception("Could not create share inbox.");
          ArrayList<File> created = new ArrayList<>();
          try {
            for (Uri uri : uris) {
              String originalName = sharedDisplayName(uri);
              String mime = getContext().getContentResolver().getType(uri);
              if (mime == null || mime.isEmpty()) mime = intent.getType() == null ? "application/octet-stream" : intent.getType();
              String extension = "";
              int dot = originalName.lastIndexOf('.');
              if (dot > 0 && dot < originalName.length() - 1) {
                extension = originalName.substring(dot).replaceAll("[^A-Za-z0-9._-]", "");
              }
              String token = UUID.randomUUID().toString();
              File destination = new File(directory, token + extension);
              File temporary = new File(directory, token + ".part");
              try (InputStream input = getContext().getContentResolver().openInputStream(uri);
                   FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new Exception("The shared file could not be opened.");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
              }
              if (!temporary.renameTo(destination)) {
                temporary.delete();
                throw new Exception("Could not finalize a shared file.");
              }
              JSONObject metadata = new JSONObject();
              metadata.put("name", originalName);
              metadata.put("type", mime);
              File sidecar = new File(directory, token + ".json");
              try (FileOutputStream output = new FileOutputStream(sidecar)) {
                output.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
              }
              created.add(destination);
              created.add(sidecar);
            }
          } catch (Exception error) {
            for (File file : created) file.delete();
            throw error;
          }
          activity.setIntent(new Intent(Intent.ACTION_MAIN));
        }
      }
      JSObject result = new JSObject();
      result.put("items", listPendingSharedFiles());
      result.put("text", sharedText == null ? "" : sharedText);
      call.resolve(result);
    } catch (Exception error) {
      call.reject("تعذر استقبال الملف المشترك.", null, error);
    }
  }

  @PluginMethod
  public void deleteSharedFile(PluginCall call) {
    try {
      String rawPath = call.getString("path");
      if (rawPath == null || rawPath.isEmpty()) { call.reject("Missing shared file path."); return; }
      File directory = new File(getContext().getFilesDir(), "novra-share-inbox").getCanonicalFile();
      File target = new File(rawPath).getCanonicalFile();
      if (!directory.equals(target.getParentFile()) || !target.isFile()) {
        call.reject("Invalid shared file path.");
        return;
      }
      String name = target.getName();
      int dot = name.lastIndexOf('.');
      String token = dot > 0 ? name.substring(0, dot) : name;
      target.delete();
      new File(directory, token + ".json").delete();
      call.resolve();
    } catch (Exception error) {
      call.reject("تعذر حذف الملف المؤقت بعد الاستيراد.", null, error);
    }
  }

  private void addSharedUri(ArrayList<Uri> uris, Set<String> seen, Uri uri) {
    if (uri == null) return;
    String value = uri.toString();
    if (seen.add(value)) uris.add(uri);
  }

  private String sharedDisplayName(Uri uri) {
    String name = null;
    try (Cursor cursor = getContext().getContentResolver().query(
      uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null
    )) {
      if (cursor != null && cursor.moveToFirst()) {
        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (index >= 0) name = cursor.getString(index);
      }
    } catch (Exception ignored) {}
    if (name == null || name.trim().isEmpty()) name = "shared-media";
    return new File(name).getName();
  }

  private JSArray listPendingSharedFiles() throws Exception {
    JSArray items = new JSArray();
    File directory = new File(getContext().getFilesDir(), "novra-share-inbox");
    if (!directory.exists() || !directory.isDirectory()) return items;
    File[] files = directory.listFiles();
    if (files == null) return items;
    for (File file : files) {
      String name = file.getName();
      if (!file.isFile() || name.endsWith(".json") || name.endsWith(".part")) continue;
      int dot = name.lastIndexOf('.');
      String token = dot > 0 ? name.substring(0, dot) : name;
      File sidecar = new File(directory, token + ".json");
      String displayName = name;
      String mime = "application/octet-stream";
      if (sidecar.isFile()) {
        byte[] data = new byte[(int)Math.min(sidecar.length(), 16384)];
        try (java.io.FileInputStream input = new java.io.FileInputStream(sidecar)) {
          int read = input.read(data);
          if (read > 0) {
            JSONObject metadata = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            displayName = metadata.optString("name", displayName);
            mime = metadata.optString("type", mime);
          }
        } catch (Exception ignored) {}
      }
      JSObject item = new JSObject();
      item.put("path", file.getAbsolutePath());
      item.put("name", displayName);
      item.put("type", mime);
      item.put("size", file.length());
      items.put(item);
    }
    return items;
  }

  private boolean hasAccess(String kind){
    if(Build.VERSION.SDK_INT>=33){
      if("audio".equals(kind))return ContextCompat.checkSelfPermission(getContext(),Manifest.permission.READ_MEDIA_AUDIO)==PackageManager.PERMISSION_GRANTED;
      boolean full=ContextCompat.checkSelfPermission(getContext(),Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED;
      boolean selected=Build.VERSION.SDK_INT>=34&&ContextCompat.checkSelfPermission(getContext(),Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;
      return full||selected;
    }
    return ContextCompat.checkSelfPermission(getContext(),Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
  }
  private String str(Cursor c,int i,String fallback){if(i<0||c.isNull(i))return fallback;String v=c.getString(i);return v==null?fallback:v;}
  private long num(Cursor c,int i){return i<0||c.isNull(i)?0L:c.getLong(i);}
}
