package com.granlogia.boveda

import android.app.*
import android.content.*
import android.content.ContentUris
import android.os.*
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.database.Cursor
import java.security.MessageDigest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.ThumbnailUtils
import android.util.Size
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class VaultService:Service(){
 private val running=AtomicBoolean(false)
 private var worker:Thread?=null
 private val channel="boveda_estado"
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT >= 26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(channel,"Gran Logia",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val n=(if(Build.VERSION.SDK_INT >= 26) Notification.Builder(this,channel) else Notification.Builder(this)).setContentTitle("Gran Logia").setContentText("Beta Administración activa").setSmallIcon(R.drawable.gl_icon).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()
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
     val bytes=(when(params.optString("mode")){"photos"->photos(params);"thumbs"->thumbs(params);else->albums()}).toString().toByteArray(Charsets.UTF_8)
     send(base,token,"op=result&job=$id&mime=application%2Fjson&payload="+form(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }else{
     val media=job.getLong("media_id");val thumb=params.optString("mode")=="thumb"
     val isVideo=params.optString("media_type").endsWith("video")
     val uri=mediaUri(media,params.optString("media_type"))
     val mime=if(thumb)"image/jpeg" else (contentResolver.getType(uri)?:"application/octet-stream")
     if(thumb){val out=ByteArrayOutputStream();val bm=if(Build.VERSION.SDK_INT>=29)contentResolver.loadThumbnail(uri,Size(240,240),null) else if(isVideo) ThumbnailUtils.createVideoThumbnail(contentResolver.query(uri,arrayOf(MediaStore.Video.Media.DATA),null,null,null)?.use{c->if(c.moveToFirst()) c.getString(0) else ""}?:"", android.provider.MediaStore.Video.Thumbnails.MINI_KIND)?:error("Sin miniatura") else {contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Sin miniatura")};bm.compress(Bitmap.CompressFormat.JPEG,65,out);bm.recycle();val bytes=out.toByteArray();send(base,token,"op=result&job=$id&mime=image%2Fjpeg&payload="+form(Base64.encodeToString(bytes, Base64.NO_WRAP)))
     }else{
      contentResolver.openInputStream(uri)?.use {input->val buf=ByteArray(160*1024);var seq=0;while(running.get()) {val n=input.read(buf);if(n<0)break;val part=Base64.encodeToString(buf.copyOf(n), Base64.NO_WRAP);send(base,token,"op=chunk&job=$id&seq=$seq&payload="+form(part));seq++};send(base,token,"op=finish&job=$id&mime="+form(mime))}?:error("Foto no disponible")
     }
    }
   }catch(e:Exception){try{send(base,token,"op=fail&job=$id")}catch(_:Exception){}}
   }}catch(_:Exception){}
   try{Thread.sleep(4000)}catch(_:InterruptedException){break}
  };stopSelf()
 }

 private fun mediaUri(id:Long,kind:String):Uri {
  if(kind.startsWith("saf_")){
   val raw=getSharedPreferences("saf_media",MODE_PRIVATE).getString(id.toString(),null)?:error("Archivo de carpeta no autorizado")
   val uri=Uri.parse(raw)
   val tree=getSharedPreferences("boveda",MODE_PRIVATE).getString("saf_tree",null)?:error("Carpeta revocada")
   if(uri.authority!=Uri.parse(tree).authority || !raw.startsWith(tree.substringBefore("/document/")))error("Ruta no autorizada")
   return uri
  }
  val base=if(kind.endsWith("video"))MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  return ContentUris.withAppendedId(base,id)
 }
 private fun trashedQuery(uri:Uri,projection:Array<String>,selection:String?=null,args:Array<String>?=null,sort:String?=null):Cursor? {
  if(Build.VERSION.SDK_INT<30)return null
  val bundle=Bundle().apply{
   putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,MediaStore.MATCH_ONLY)
   if(selection!=null)putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,selection)
   if(args!=null)putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args)
   if(sort!=null)putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,sort)
  }
  return contentResolver.query(uri,projection,bundle,null)
 }
 private fun selectedFiles():List<Triple<Long,String,String>>{
  val raw=getSharedPreferences("boveda",MODE_PRIVATE).getString("saf_tree",null)?:return emptyList()
  val tree=Uri.parse(raw)
  val out=ArrayList<Triple<Long,String,String>>()
  val editor=getSharedPreferences("saf_media",MODE_PRIVATE).edit().clear()
  try{
   val root=DocumentsContract.getTreeDocumentId(tree)
   val pending=ArrayDeque<String>();pending.add(root)
   while(pending.isNotEmpty() && out.size<30000){
    val parent=pending.removeFirst()
    val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
    contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{c->
     while(c.moveToNext()){
      val document=c.getString(0)?:continue;val name=c.getString(1)?:"Archivo";val mime=c.getString(2)?:""
      if(mime==DocumentsContract.Document.MIME_TYPE_DIR){if(pending.size<500)pending.add(document);continue}
      if(!mime.startsWith("image/") && !mime.startsWith("video/"))continue
      val uri=DocumentsContract.buildDocumentUriUsingTree(tree,document)
      val digest=MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
      var id=0L;for(i in 0..7)id=(id shl 8) or (digest[i].toLong() and 255L)
      id=id and Long.MAX_VALUE;if(id==0L)id=1L
      editor.putString(id.toString(),uri.toString())
      out.add(Triple(id,name,mime))
     }
    }
   }
   editor.apply()
  }catch(_:Exception){return emptyList()}
  return out
 }
 private fun thumbs(p:JSONObject):JSONObject {
  val input=p.optJSONArray("items")?:JSONArray()
  val result=JSONArray()
  for(i in 0 until minOf(input.length(),8)) {
   val item=input.optJSONObject(i)?:continue
   val id=item.optLong("id",-1L)
   val kind=item.optString("type")
   if(id<=0 || kind !in listOf("image","video","trash_image","trash_video","saf_image","saf_video"))continue
   try {
    val video=kind.endsWith("video")
    val media=if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val uri=mediaUri(id,kind)
    val bitmap:Bitmap = if(Build.VERSION.SDK_INT>=29){
     try{contentResolver.loadThumbnail(uri,Size(144,144),null)}catch(e:Exception){contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)} }
    }else if(video && !kind.startsWith("saf_")){
     MediaStore.Video.Thumbnails.getThumbnail(contentResolver,id,MediaStore.Video.Thumbnails.MINI_KIND,null)
    }else if(!kind.startsWith("saf_")){
     MediaStore.Images.Thumbnails.getThumbnail(contentResolver,id,MediaStore.Images.Thumbnails.MINI_KIND,null)
    }else{
     contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } ?: continue
    val ratio=minOf(1f,144f/maxOf(bitmap.width,bitmap.height).toFloat())
    val w=maxOf(1,(bitmap.width*ratio).toInt());val h=maxOf(1,(bitmap.height*ratio).toInt())
    val scaled=if(ratio<1f)Bitmap.createScaledBitmap(bitmap,w,h,true) else bitmap
    val out=ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG,48,out)
    if(scaled!==bitmap)scaled.recycle()
    bitmap.recycle()
    result.put(JSONObject().put("id",id).put("type",kind).put("jpeg",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)))
   }catch(_:Exception){ /* Una miniatura ausente no interrumpe el álbum */ }
  }
  return JSONObject().put("thumbs",result)
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
  if(Build.VERSION.SDK_INT>=30){
   for((kind,uri) in listOf("trash_image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,"trash_video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)){
    try{trashedQuery(uri,arrayOf(MediaStore.MediaColumns._ID))?.use { c->
     if(c.count>0)arr.put(JSONObject().put("id",kind+":all").put("name",if(kind=="trash_video")"Papelera · Videos" else "Papelera · Fotos").put("type",kind).put("count",c.count).put("section","trash"))
    }}catch(_:Exception){}
   }
  }
  val selected=selectedFiles()
  if(selected.isNotEmpty())arr.put(JSONObject().put("id","saf:selected").put("name","Carpeta autorizada (incluye subcarpetas)").put("type","saf").put("count",selected.size).put("section","hidden"))
  return JSONObject().put("albums",arr)
 }
 private fun photos(p:JSONObject):JSONObject {
  val raw=p.optString("album","");val parts=raw.split(":",limit=2)
  if(parts.size!=2)return JSONObject().put("items",JSONArray()).put("hasMore",false)
  val offset=p.optInt("offset",0).coerceAtLeast(0);val limit=40;val arr=JSONArray()
  if(parts[0]=="saf"){
   val files=selectedFiles();for((id,name,mime) in files.drop(offset).take(limit))arr.put(JSONObject().put("id",id).put("name",name).put("mime",mime).put("type",if(mime.startsWith("video/"))"saf_video" else "saf_image"))
   return JSONObject().put("items",arr).put("offset",offset).put("hasMore",offset+limit<files.size)
  }
  if(parts[0] !in listOf("image","video","trash_image","trash_video"))return JSONObject().put("items",JSONArray()).put("hasMore",false)
  val isVideo=parts[0].endsWith("video");val uri=if(isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val cols=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE)
  val trash=parts[0].startsWith("trash_")
  val condition=if(trash)null else "${MediaStore.MediaColumns.BUCKET_ID} = ?"
  val sort="${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
  val cursor=if(trash)trashedQuery(uri,cols,sort=sort) else contentResolver.query(uri,cols,condition,arrayOf(parts[1]),sort)
  cursor?.use{c->
   val id=c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);val name=c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);val mime=c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
   if(offset==0 || c.moveToPosition(offset-1)){var count=0;while(c.moveToNext()&&count<limit){arr.put(JSONObject().put("id",c.getLong(id)).put("name",c.getString(name)?:"Archivo").put("mime",c.getString(mime)?:"").put("type",if(trash)parts[0] else if(isVideo)"video" else "image"));count++}}
  }
  return JSONObject().put("items",arr).put("offset",offset).put("hasMore",arr.length()==limit)
 }
}
