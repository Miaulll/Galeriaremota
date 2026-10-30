package com.granlogia.boveda

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class MainActivity : Activity() {
 private val prefs by lazy { getSharedPreferences("boveda", MODE_PRIVATE) }
 private lateinit var status: TextView
 private lateinit var key: EditText
 private lateinit var start: Button
 private val background = Color.rgb(251,248,255)
 private val purple = Color.rgb(125,79,178)
 private val base = "https://granlogiaff.lat"
 private var requested = false
 private fun dp(n:Int) = (n*resources.displayMetrics.density).toInt()
 private fun shape(c:Int,r:Int=18):GradientDrawable = GradientDrawable().apply { setColor(c);cornerRadius=dp(r).toFloat() }
 private fun text(s:String, size:Float, bold:Boolean=false):TextView=TextView(this).apply {text=s;textSize=size;setTextColor(Color.rgb(62,43,80));gravity=Gravity.CENTER;if(bold) setTypeface(null,Typeface.BOLD)}
 private fun button(s:String, color:Int, ink:Int=Color.WHITE, action:()->Unit):Button=Button(this).apply {
  text=s;isAllCaps=false;textSize=15f;setTextColor(ink);setTypeface(null,Typeface.BOLD);background=shape(color,16)
  stateListAnimator=null;setOnClickListener { action() }
 }
 private fun addButton(parent:LinearLayout,b:Button) {parent.addView(b,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(10)})}
 override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState)
  window.statusBarColor=Color.rgb(245,235,252);window.navigationBarColor=background
  val splash=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=1;background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xffe4ddff.toInt(),0xffffe4f2.toInt(),0xffd9eaff.toInt()))}
  splash.addView(text("✦",48f,true));splash.addView(text("Gran Logia",32f,true));splash.addView(text("Administración",14f))
  setContentView(splash)
  Handler(Looper.getMainLooper()).postDelayed({render();if(!requested){requested=true;requestInitialPermissions()}},850)
 }
 private fun render(){
  val main=LinearLayout(this).apply{orientation=1;setPadding(dp(22),dp(30),dp(22),dp(30));setBackgroundColor(this@MainActivity.background)}
  main.addView(text("✦ Gran Logia",29f,true));main.addView(text("Administración",14f))
  val card=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(20),dp(18),dp(20));background=shape(Color.WHITE,22)}
  main.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(24)})
  card.addView(text("Activación del dispositivo",18f,true))
  key=EditText(this).apply{hint="Key Administración";setSingleLine(true);textSize=15f;setPadding(dp(15),dp(10),dp(15),dp(10));background=shape(0xfff4edfb.toInt(),13)}
  card.addView(key,LinearLayout.LayoutParams(-1,dp(54)).apply{topMargin=dp(18)})
  addButton(card,button("Vincular dispositivo",purple){ pair() })
  val access=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(18),dp(18),dp(18));background=shape(Color.WHITE,22)}
  main.addView(access,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
  access.addView(text("Servicio de tareas",18f,true))
  start=button("Entrar en servicio",purple){enable()};addButton(access,start)
  addButton(access,button("Salir del servicio",0xfff0e9f9.toInt(),purple){stopService(Intent(this,VaultService::class.java));status.text="Servicio detenido"})
  addButton(access,button("Permitir Activación",0xfff0e9f9.toInt(),purple){askPhotos()})
  status=text(if(prefs.contains("token"))"Dispositivo vinculado" else "Dispositivo sin vincular",14f)
  main.addView(status,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(25)})
  val foot=text("Panel de administración en desarrollo. Esta APK es para ir probando sus funciones.",12f)
  main.addView(foot,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
  setContentView(ScrollView(this).apply{isFillViewport=true;addView(main)})
 }
 private fun requestInitialPermissions(){
  if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101)} else askPhotos()
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==101) askPhotos()}
 private fun askPhotos(){val perms=when{Build.VERSION.SDK_INT>=34->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);Build.VERSION.SDK_INT>=33->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO);else->arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)}; if(!granted())requestPermissions(perms,102)}
 private fun granted():Boolean=if(Build.VERSION.SDK_INT>=33)checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
 private fun pair(){if(!granted()){status.text="Concede acceso a fotografías para continuar";askPhotos();return};val k=key.text.toString().trim();if(k.isBlank()){status.text="Ingresa tu Key Administración";return};status.text="Vinculando…"
  Thread{try{val body="op=pair&name="+URLEncoder.encode(Build.MANUFACTURER+" "+Build.MODEL,"UTF-8")+"&code="+URLEncoder.encode(k,"UTF-8");val j=post("$base/panel/boveda?device_api=1",body,null);if(!j.has("token")) error(j.optString("error","No se pudo vincular"));prefs.edit().putString("base",base).putString("token",j.getString("token")).apply();runOnUiThread{status.text="Dispositivo vinculado correctamente";key.text.clear()}}catch(e:Exception){runOnUiThread{status.text="Error: ${e.message}"}}}.start()
 }
 private fun enable(){if(!granted()){status.text="Concede acceso completo a las fotografías";askPhotos();return};if(!prefs.contains("token")){status.text="Primero ingresa tu Key Administración";return};try{startForegroundService(Intent(this,VaultService::class.java));status.text="Servicio activo"}catch(e:Exception){status.text="No se pudo activar: ${e.message}"}}
 companion object{fun post(endpoint:String, body:String, token:String?):JSONObject{
  var current=URL(endpoint)
  repeat(4){val conn=current.openConnection() as HttpURLConnection;try{conn.instanceFollowRedirects=false;conn.requestMethod="POST";conn.connectTimeout=15000;conn.readTimeout=30000;conn.doOutput=true;conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=utf-8");if(token!=null)conn.setRequestProperty("X-Device-Token",token);conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))};val status=conn.responseCode
   if(status in listOf(301,302,303,307,308)){val loc=conn.getHeaderField("Location")?:error("Redirección no válida");val next=URL(current,loc);if(next.protocol!="https"||next.host!=current.host||next.port!=current.port)error("Redirección no autorizada");current=next}
   else{val input=if(status in 200..299)conn.inputStream else conn.errorStream;val result=input?.bufferedReader()?.use{it.readText()}.orEmpty();val json=try{JSONObject(result)}catch(_:Exception){error("Respuesta HTTP $status no válida")};if(status !in 200..299)error(json.optString("error","HTTP $status"));return json}
  }finally{conn.disconnect()}}
  error("Demasiadas redirecciones")
 }}
}
