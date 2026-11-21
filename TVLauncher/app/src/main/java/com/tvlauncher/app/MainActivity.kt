package com.tvlauncher.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(this, webView), "Android")
        webView.loadUrl("file:///android_asset/launcher.html")
        setContentView(webView)

        tryEnterKiosk()
    }

    private fun tryEnterKiosk() {
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                startLockTask()
            } else if (dpm.isDeviceOwnerApp(packageName.not())) {
                // If we're not device owner, still try regular lock task (requires user pin)
                startLockTask()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lock task: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Block Back button completely
    override fun onBackPressed() { /* nothing */ }

    // Block Back key from hardware
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }
}

class Bridge(private val ctx: Context, private val webView: WebView) {

    @JavascriptInterface
    fun getInstalledApps(): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)
        val arr = JSONArray()
        val seen = mutableSetOf<String>()
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            if (seen.contains(pkg) || pkg == ctx.packageName) continue
            seen.add(pkg)
            val obj = JSONObject()
            obj.put("package", pkg)
            obj.put("name", ri.loadLabel(pm).toString())
            try {
                val drawable = ri.loadIcon(pm)
                val bmp = drawableToBitmap(drawable, 144)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 80, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                obj.put("icon", "data:image/png;base64,$b64")
            } catch (_: Exception) {}
            arr.put(obj)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun launchApp(pkg: String) {
        try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось запустить: $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun exitKiosk() {
        if (ctx is MainActivity) {
            try {
                ctx.stopLockTask()
                Toast.makeText(ctx, "Режим киоска отключён", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun isDeviceOwner(): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(ctx.packageName)
    }

    @JavascriptInterface
    fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su -c id")
            p.waitFor() == 0
        } catch (_: Exception) { false }
    }

    @JavascriptInterface
    fun runAsRoot(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) { "" }
    }

    @JavascriptInterface
    fun openSettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    private fun drawableToBitmap(drawable: Drawable, size: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}