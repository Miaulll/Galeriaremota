package com.galeriaremota.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import java.net.NetworkInterface
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var secret: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("auth", MODE_PRIVATE)
        if (!prefs.contains("token")) {
            val bytes = ByteArray(24); SecureRandom().nextBytes(bytes)
            prefs.edit().putString("token", bytes.joinToString("") { "%02x".format(it) }).apply()
        }
        val root = LinearLayout(this).apply { orientation = 1; setPadding(36, 45, 36, 25) }
        root.addView(TextView(this).apply { text="GALERÍA REMOTA"; textSize=25f })
        root.addView(TextView(this).apply { text="Instala Tailscale, conecta tu cuenta y autoriza las fotos. Activa el servicio para acceder desde tu laptop."; textSize=16f })
        status = TextView(this).apply { textSize=16f }; root.addView(status)
        secret = TextView(this).apply { textSize=12f; setTextIsSelectable(true) }; root.addView(secret)
        val permissionsBtn = Button(this).apply { text="1. Autorizar fotografías"; setOnClickListener { askPermissions() } }; root.addView(permissionsBtn)
        root.addView(Button(this).apply { text="2. Iniciar servidor privado"; setOnClickListener { startServer() } })
        root.addView(Button(this).apply { text="Detener servidor"; setOnClickListener { stopService(Intent(this@MainActivity, PhotoService::class.java)); refresh() } })
        root.addView(Button(this).apply { text="Ajustes de la aplicación"; setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } })
        setContentView(ScrollView(this).apply { addView(root) }); refresh()
    }
    private fun tailIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .mapNotNull { a -> a.hostAddress?.substringBefore('%') }
            .firstOrNull { s -> s.matches(Regex("100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.\\d+\\.\\d+")) }
    } catch (_: Exception) { null }
    private fun refresh() {
        val ip = tailIp()
        status.text = "IP Tailscale: ${ip ?: "No detectada (abre Tailscale)"}\nPuerto: 8765\nNecesita permiso de fotos y servicio activo."
        secret.text = "CLAVE PRIVADA (solo en tu laptop):\n"+getSharedPreferences("auth", MODE_PRIVATE).getString("token", "")
    }
    private fun askPermissions() {
        val requested = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(requested, 101)
    }
    private fun hasPhotos(): Boolean = if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED) else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    private fun startServer() {
        if (!hasPhotos()) { askPermissions(); Toast.makeText(this,"Autoriza las fotografías y vuelve a pulsar iniciar",Toast.LENGTH_LONG).show(); return }
        if (tailIp()==null) { Toast.makeText(this,"Conecta primero Tailscale en este celular",Toast.LENGTH_LONG).show(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        try { startForegroundService(Intent(this, PhotoService::class.java)) } catch (e: Exception) { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() }
        refresh()
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
}
