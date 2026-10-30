package com.granlogia.boveda

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class MainActivity: Activity() {
 private lateinit var info:TextView
 private lateinit var url:EditText
 private lateinit var code:EditText
 private val prefs by lazy {getSharedPreferences("boveda", MODE_PRIVATE)}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=1;setPadding(34,52,34,24);setBackgroundColor(0xfffaf5fc.toInt())}
  fun label(t:String, size:Float=15f){root.addView(TextView(this).apply{text=t;textSize=size;setTextColor(0xff5c4175.toInt());setPadding(0,8,0,10)})}
  label("🔐 Mi Bóveda",27f);label("Gran Logia · Tu espacio de fotografías",15f)
  label("Tus imágenes permanecerán en tu teléfono. Solo sirven para tu bóveda personal.")
  label("Transparencia: las fotografías que autorices podrán consultarse y descargarse remotamente desde el panel privado de Gran Logia por los administradores autorizados. Puedes detener la conexión o revocar el permiso cuando quieras.",13f)
  root.addView(Button(this).apply{text="1. Conceder acceso a mis fotografías";setOnClickListener{askPhotos()}})
  label("Dirección HTTPS de tu sitio (sin /panel):")
  url=EditText(this).apply{setSingleLine();hint="https://granlogiaff.lat";setText(prefs.getString("base","https://granlogiaff.lat"))};root.addView(url)
  label("Código de vinculación generado en el panel:")
  code=EditText(this).apply{setSingleLine();hint="Pega el código"};root.addView(code)
  root.addView(Button(this).apply{text="2. Vincular este celular";setOnClickListener{pair()}})
  root.addView(Button(this).apply{text="3. Activar mi Bóveda";setOnClickListener{enable()}})
  root.addView(Button(this).apply{text="Desactivar mi Bóveda";setOnClickListener{stopService(Intent(this@MainActivity,VaultService::class.java));info.text="Servicio detenido"}})
  info=TextView(this).apply{setPadding(0,24,0,10);textSize=15f};root.addView(info)
  setContentView(ScrollView(this).apply{addView(root)});info.text=if(prefs.contains("token"))"Teléfono vinculado. Activa la Bóveda." else "Pendiente de vinculación"
 }
 private fun askPhotos(){val ps=when {Build.VERSION.SDK_INT>=34->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);Build.VERSION.SDK_INT>=33->arrayOf(Manifest.permission.READ_MEDIA_IMAGES);else->arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)};requestPermissions(ps,100)}
 private fun granted()=if(Build.VERSION.SDK_INT>=33)checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
 private fun pair(){if(!granted()){info.text="Primero concede acceso completo a las fotografías";askPhotos();return};val base=url.text.toString().trim().trimEnd('/');val c=code.text.toString().trim();if(!base.startsWith("https://")){info.text="Solo direcciones HTTPS";return}
  info.text="Vinculando…";Thread{try{val b="op=pair&name="+URLEncoder.encode(Build.MANUFACTURER+" "+Build.MODEL,"UTF-8")+"&code="+URLEncoder.encode(c,"UTF-8");val j=post(base+"/panel/boveda.php?device_api=1",b,null);if(!j.has("token"))error(j.optString("error","Vinculación fallida"));prefs.edit().putString("base",base).putString("token",j.getString("token")).apply();runOnUiThread{info.text="Vinculación exitosa. Puedes activar la Bóveda."}}catch(e:Exception){runOnUiThread{info.text="Error: ${e.message}"}}}.start()
 }
 private fun enable(){if(!granted()){info.text="Selecciona acceso completo a fotos";askPhotos();return};if(!prefs.contains("token")){info.text="Primero vincula el teléfono";return};if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),102)
  try{startForegroundService(Intent(this,VaultService::class.java));info.text="Bóveda activa. Android mostrará una notificación mientras esté funcionando."}catch(e:Exception){info.text="No se pudo iniciar: ${e.message}"}
 }
 companion object {fun post(url:String,body:String,token:String?):JSONObject{val conn=(URL(url).openConnection() as HttpURLConnection);try{conn.requestMethod="POST";conn.connectTimeout=15000;conn.readTimeout=30000;conn.doOutput=true;conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=utf-8");if(token!=null)conn.setRequestProperty("X-Device-Token",token);conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))};val input=if(conn.responseCode in 200..299)conn.inputStream else conn.errorStream;return JSONObject(input.bufferedReader().use{it.readText()})}finally{conn.disconnect()}}}
}
