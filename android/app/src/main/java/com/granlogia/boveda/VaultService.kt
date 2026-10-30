package com.granlogia.boveda

import android.app.*
import android.content.*
import android.content.ContentUris
import android.os.*
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.ThumbnailUtils
import android.util.Size
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class VaultService:Service(){
 private val running=AtomicBoolean(false)
 private var worker:Thread?=null
 private val channel="boveda_estado"
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){super.onCreate();(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(channel,"Gran Logia",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val n=Notification.Builder(this,channel).setContentTitle("Gran Logia").setContentText("Beta Administración activa").setSmallIcon(R.drawable.gl_icon).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(1702,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1702,n)
  if(running.compareAndSet(false,true))worker=Thread{loop()}.apply{start()}
  return START_NOT_STICKY
 }
 override fun onDestroy(){running.set(false);worker?.interrupt();super.onDestroy()}
 private fun form(s:String)=URLEncoder.encode(s,"UTF-8")
 private fun send(base:String,token:String,body:String)=MainActivity.post("$base/panel/boveda?device_api=1",body,token)
 private fun loop(){val p=getSharedPreferences("boveda",MODE_PRIVATE);val base=p.getString("base","https://granlogiaff.lat")?:"";val token=p.getString("token","")?:"";if(!base.startsWith("https://")||token.isEmpty()){stopSelf();return}
  while(running.get()){
   try{val job=send(base,token,"op=next").optJSONObject("job");if(job!=null){val id=job.getLong("id");val params=JSONObject(job.optString("parametros","{}"));try{
    if(job.getString("tipo")=="lista"){
     val bytes=(if(params.optString("mode")=="photos")photos(params) else albums()).toString().toByteArray(Charsets.UTF_8)
     send(base,token,"op=result&job=$id&mime=application%2Fjson&payload="+form(Base64.getEncoder().encodeToString(bytes)))
    }else{
     val media=job.getLong("media_id");val thumb=params.optString("mode")=="thumb"
     val isVideo=params.optString("media_type")=="video"
     val uri=ContentUris.withAppendedId(if(isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,media)
     val mime=if(thumb)"image/jpeg" else (contentResolver.getType(uri)?:"application/octet-stream")
     if(thumb){val out=ByteArrayOutputStream();val bm=if(Build.VERSION.SDK_INT>=29)contentResolver.loadThumbnail(uri,Size(240,240),null) else if(isVideo) ThumbnailUtils.createVideoThumbnail(contentResolver.query(uri,arrayOf(MediaStore.Video.Media.DATA),null,null,null)?.use{c->if(c.moveToFirst()) c.getString(0) else ""}?:"", android.provider.MediaStore.Video.Thumbnails.MINI_KIND)?:error("Sin miniatura") else {contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Sin miniatura")};bm.compress(Bitmap.CompressFormat.JPEG,65,out);bm.recycle();val bytes=out.toByteArray();send(base,token,"op=result&job=$id&mime=image%2Fjpeg&payload="+form(Base64.getEncoder().encodeToString(bytes)))
     }else{
      contentResolver.openInputStream(uri)?.use {input->val buf=ByteArray(160*1024);var seq=0;while(running.get()) {val n=input.read(buf);if(n<0)break;val part=Base64.getEncoder().encodeToString(buf.copyOf(n));send(base,token,"op=chunk&job=$id&seq=$seq&payload="+form(part));seq++};send(base,token,"op=finish&job=$id&mime="+form(mime))}?:error("Foto no disponible")
     }
    }
   }catch(e:Exception){try{send(base,token,"op=fail&job=$id")}catch(_:Exception){}}
   }}catch(_:Exception){}
   try{Thread.sleep(4000)}catch(_:InterruptedException){break}
  };stopSelf()
 }
 private fun albums():JSONObject {
  val arr=JSONArray()
  fun collect(uri:Uri,kind:String){
   val counts=linkedMapOf<String,Pair<String,Int>>()
   val cols=arrayOf(MediaStore.MediaColumns.BUCKET_ID,MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
   contentResolver.query(uri,cols,null,null,null)?.use { c->
    val idx=c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
    val nam=c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
    while(c.moveToNext()){val bucket=c.getString(idx)?:continue;val label=c.getString(nam)?:"Álbum";val prev=counts[bucket];counts[bucket]=Pair(label,(prev?.second?:0)+1)}
   }
   for((id,v) in counts)arr.put(JSONObject().put("id",kind+":"+id).put("name",v.first).put("type",kind).put("count",v.second))
  }
  collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"image")
  collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"video")
  return JSONObject().put("albums",arr)
 }
 private fun photos(p:JSONObject):JSONObject {
  val raw=p.optString("album","");val parts=raw.split(":",limit=2)
  if(parts.size!=2 || parts[0] !in listOf("image","video"))return JSONObject().put("items",JSONArray()).put("hasMore",false)
  val isVideo=parts[0]=="video";val uri=if(isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val offset=p.optInt("offset",0).coerceAtLeast(0);val limit=40;val arr=JSONArray()
  val cols=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE)
  val condition="${MediaStore.MediaColumns.BUCKET_ID} = ?"
  contentResolver.query(uri,cols,condition,arrayOf(parts[1]),"${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")?.use{c->
   val id=c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);val name=c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);val mime=c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
   if(offset==0 || c.moveToPosition(offset-1)){var count=0;while(c.moveToNext()&&count<limit){arr.put(JSONObject().put("id",c.getLong(id)).put("name",c.getString(name)?:"Archivo").put("mime",c.getString(mime)?:"").put("type",if(isVideo)"video" else "image"));count++}}
  }
  return JSONObject().put("items",arr).put("offset",offset).put("hasMore",arr.length()==limit)
 }
}
