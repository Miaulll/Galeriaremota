package com.galeriaremota.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.*
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.Executors

class PhotoService : Service() {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var running = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "galeria_remota"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,"Servidor de fotos",NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this,0, Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this,channel).setContentTitle("Galería remota activa").setContentText("Acceso privado desde tu laptop · toca para gestionar").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentIntent(pending).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1,notification)
        if (!running) { running=true; Thread { serve() }.start() }
        return START_STICKY
    }
    private fun tailIp(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { val b=it.address; b.size==4 && (b[0].toInt() and 255)==100 && (b[1].toInt() and 255) in 64..127 }
    } catch (_: Exception) { null }
    private fun serve() {
        try {
            val ip = tailIp() ?: throw IllegalStateException("No hay Tailscale")
            server = ServerSocket(8765, 20, ip)
            while (running) { val client = server!!.accept(); pool.execute { handle(client) } }
        } catch (e: Exception) { android.util.Log.e("GaleriaRemota","Servidor detenido",e) }
        finally { running=false; stopSelf() }
    }
    private fun respond(out:OutputStream, status:String, type:String, data:ByteArray) {
        val header="HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Private-Network: true\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8)); out.write(data); out.flush()
    }
    private fun json(out:OutputStream, obj:JSONObject, status:String="200 OK") = respond(out,status,"application/json; charset=utf-8",obj.toString().toByteArray())
    private fun handle(client:Socket) {
        client.use { socket ->
            try {
                socket.soTimeout=15000
                val input=BufferedInputStream(socket.getInputStream()); val out=BufferedOutputStream(socket.getOutputStream())
                val first=readLine(input) ?: return; val parts=first.split(' ')
                if (parts.size < 2) return
                val headers=mutableMapOf<String,String>()
                while(true) { val line=readLine(input) ?: return; if(line.isEmpty()) break; val p=line.indexOf(':'); if(p>0) headers[line.substring(0,p).lowercase()] = line.substring(p+1).trim() }
                if(parts[0]=="OPTIONS") { respond(out,"204 No Content","text/plain",byteArrayOf()); return }
                if(parts[0]!="GET") { json(out,JSONObject().put("error","Método no permitido"),"405 Method Not Allowed"); return }
                val token=getSharedPreferences("auth",MODE_PRIVATE).getString("token","")!!
                val auth=headers["authorization"]?.removePrefix("Bearer ") ?: ""
                if (auth!=token || token.isEmpty()) { json(out,JSONObject().put("error","Clave inválida"),"401 Unauthorized"); return }
                val uri=Uri.parse("http://localhost"+parts[1]); val path=uri.path
                when(path) {
                    "/api/health" -> json(out,JSONObject().put("ok",true).put("device",Build.MANUFACTURER+" "+Build.MODEL))
                    "/api/photos" -> listPhotos(out,uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1,500) ?: 120,uri.getQueryParameter("offset")?.toIntOrNull()?.coerceIn(0,1000000) ?: 0,uri.getQueryParameter("album"))
                    "/api/albums" -> albums(out)
                    "/api/image", "/api/thumb" -> {
                        val id=uri.getQueryParameter("id")?.toLongOrNull()
                        if(id==null || id<0) json(out,JSONObject().put("error","ID inválido"),"400 Bad Request")
                        else sendImage(out,id,path=="/api/thumb")
                    }
                    else -> json(out,JSONObject().put("error","Ruta desconocida"),"404 Not Found")
                }
            } catch (e: Exception) { android.util.Log.w("GaleriaRemota","Error de petición",e) }
        }
    }
    private fun readLine(input:InputStream):String? {
        val output=ByteArrayOutputStream()
        while(output.size()<8192) { val c=input.read(); if(c<0) return null; if(c==10) return output.toString("UTF-8").trimEnd('\r'); output.write(c) }
        return null
    }
    private val collection get() = if (Build.VERSION.SDK_INT>=29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private fun listPhotos(out:OutputStream,limit:Int,offset:Int,album:String?) {
        val arr=JSONArray(); var count=0
        val columns=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.BUCKET_DISPLAY_NAME,MediaStore.Images.Media.DATE_ADDED)
        contentResolver.query(collection,columns,null,null,"${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idx=c.getColumnIndexOrThrow(MediaStore.Images.Media._ID); val ni=c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME); val ai=c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME); val di=c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            var skipped=0
            while(c.moveToNext()) {
                val bucket=c.getString(ai) ?: "Otras"
                if(!album.isNullOrBlank() && album!=bucket) continue
                if(skipped++<offset) continue
                if(count++>=limit) break
                arr.put(JSONObject().put("id",c.getLong(idx)).put("name",c.getString(ni)).put("album",bucket).put("date",c.getLong(di)))
            }
        }
        json(out,JSONObject().put("photos",arr).put("limit",limit).put("offset",offset))
    }
    private fun albums(out:OutputStream) {
        val counts=linkedMapOf<String,Int>()
        contentResolver.query(collection,arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),null,null,"${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while(c.moveToNext()) { val n=c.getString(0)?:"Otras"; counts[n]=(counts[n]?:0)+1 }
        }
        val arr=JSONArray(); for((name,count) in counts) arr.put(JSONObject().put("name",name).put("count",count))
        json(out,JSONObject().put("albums",arr))
    }
    private fun sendImage(out:OutputStream,id:Long,thumb:Boolean) {
        val uri=ContentUris.withAppendedId(collection,id)
        try {
            if (thumb) {
                val bmp=contentResolver.loadThumbnail(uri,android.util.Size(400,400),null)
                val bytes=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,75,bytes)
                respond(out,"200 OK","image/jpeg",bytes.toByteArray())
            } else {
                val mime=contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "application/octet-stream"
                contentResolver.openInputStream(uri)?.use { stream ->
                    // Stream original bytes, never keep a server-side copy.
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nCache-Control: no-store\r\nContent-Disposition: attachment; filename=photo_$id\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray())
                    stream.copyTo(out,64*1024); out.flush()
                } ?: json(out,JSONObject().put("error","No se encontró"),"404 Not Found")
            }
        } catch (e:Exception) { json(out,JSONObject().put("error","Foto inaccesible"),"404 Not Found") }
    }
    override fun onDestroy() { running=false; try { server?.close() } catch (_:Exception){}; pool.shutdownNow(); super.onDestroy() }
}
