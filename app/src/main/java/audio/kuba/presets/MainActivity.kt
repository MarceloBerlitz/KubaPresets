package audio.kuba.presets

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private val host = "equalizador.kuba.audio"
    private val home = "https://$host/"

    private lateinit var web: WebView
    private lateinit var bridge: UsbBridge

    private val errorHtml = """
        <html><body style="background:#0F0F12;color:#eee;font-family:sans-serif;text-align:center;padding:48px 24px">
        <h2>Sem conexão</h2>
        <p>Não foi possível carregar o Kuba Presets.</p>
        <p><a style="color:#8ab4ff;font-size:18px" href="$home">Tentar novamente</a></p>
        </body></html>
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect
        }

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF0F0F12.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }

        bridge = UsbBridge(this, web)
        bridge.register()
        web.addJavascriptInterface(bridge, "AndroidUsb")

        val script = assets.open("usb-bridge.js").bufferedReader().use { it.readText() }
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("https://$host"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "https" && request.url.host == host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val u = Uri.parse(url)
                bridge.trusted = u.scheme == "https" && u.host == host
                if (!docStart && bridge.trusted) view.evaluateJavascript(script, null) // fallback
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadData(errorHtml, "text/html", "UTF-8")
            }
        }

        web.loadUrl(home)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        bridge.unregister()
        web.destroy()
        super.onDestroy()
    }
}
