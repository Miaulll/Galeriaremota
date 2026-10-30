package com.granlogia.boveda

import android.app.*
import android.content.*
import android.content.ContentUris
import android.os.*
import android.provider.MediaStore
import android.net.Uri
import android.database.Cursor
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class VaultService:Service(){
 private val running=AtomicBoolean(false)
 private var worker:Thread?=null
 private val channel="boveda_estado"
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){super.onCreate();val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager;nm.createNotificationChannel(NotificationChannel(channel,"Bóveda Gran Logia",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val notification=Notification.Builder(this,channel).setContentTitle("Mi Bóveda · Gran Logia").setContentText("Bóveda activa · Acceso remoto habilitado").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(1702,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1702,notification)
  if(running.compareAndSet(false,true)){worker=Thread{loop()}.apply{start()}}
  return START_NOT_STICKY
 }
 override fun onDestroy(){running.set(false);worker?.interrupt();super.onDestroy()}
 private fun loop(){val p=getSharedPreferences("boveda",MODE_PRIVATE);val base=p.getString("base","")?:"";val token=p.getString("token","")?:"";if(!base.startsWith("https://")||token.isEmpty()){stopSelf();return}
  while(running.get()){try{val next=MainActivity.post("$base/panel/boveda.php?device_api=1","op=next",token).optJSONObject("job")
   if(next!=null){val id=next.getLong("id");try {val (mime,payload)=if(next.getString("tipo")=="lista") Pair("application/json",listPhotos().toString().toByteArray()) else imageBytes(next.getLong("media_id"));val encoded=Base64.getEncoder().encodeToString(payload);val body="op=result&job=$id&mime="+URLEncoder.encode(mime,"UTF-8")+"&payload="+URLEncoder.encode(encoded,"UTF-8");MainActivity.post("$base/panel/boveda.php?device_api=1",body,token)}catch(_:Exception){ /* El trabajo expirará sin enviar imágenes */ }}
  }catch(_:Exception){ /* Red desconectada: reintentar */ }
  try{Thread.sleep(4000)}catch(_:InterruptedException){break}
  };stopSelf()
 }
 private fun listPhotos():JSONArray {val arr=JSONArray();val uri=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.DATE_ADDED)
  val cur=contentResolver.query(uri,projection,null,null,"${MediaStore.Images.Media.DATE_ADDED} DESC")?:return arr
  cur.use{val idIndex=it.getColumnIndexOrThrow(MediaStore.Images.Media._ID);val nIndex=it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);while(it.moveToNext()&&arr.length()<150){arr.put(JSONObject().put("id",it.getLong(idIndex)).put("name",it.getString(nIndex)?:"Fotografía"))}}
  return arr
 }
 private fun imageBytes(mediaId:Long):Pair<String,ByteArray>{
  val uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,mediaId)
  val mime=contentResolver.getType(uri)?:"image/jpeg";if(mime !in listOf("image/jpeg","image/png","image/webp"))throw IllegalArgumentException("Tipo no admitido")
  val stream=contentResolver.openInputStream(uri)?:error("No disponible")
  val out=java.io.ByteArrayOutputStream();stream.use{val buf=ByteArray(16384);var n:Int;while(true){n=it.read(buf);if(n<0)break;out.write(buf,0,n);if(out.size()>4*1024*1024)error("La imagen excede el límite de 4 MB")}}
  return Pair(mime,out.toByteArray())
 }
}
