package com.novra.video;

import android.Manifest;
import android.content.ContentUris;
import android.database.Cursor;
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
