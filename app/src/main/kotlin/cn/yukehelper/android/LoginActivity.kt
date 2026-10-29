package cn.yukehelper.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import cn.yukehelper.core.*
import kotlinx.coroutines.*

class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = application as HelperApplication
    private lateinit var domain: String
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var webView: WebView? = null
    private var loginJob: Job? = null
    private var cookieJob: Job? = null
    private var bitmap: Bitmap? = null
    private var completing = false
    private var loginMode = "qr"
    private var qrAttempt = -1L
    private var pendingSave: Bitmap? = null
    private var pendingAttempt = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        domain = intent.getStringExtra("domain") ?: Domain.all.first().host
        if (runCatching { Domain.requireHost(domain) }.isFailure) { finish(); return }
        val root = column().apply { setBackgroundColor(Palette.background) }
        val header = row().apply { setPadding(dp(16), dp(12), dp(16), dp(8)) }
        header.addView(action("返回", false) { finish() }, touchParams(dp(72)))
        header.addView(label("  ${Domain.requireHost(domain).label}登录", 19f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        status = label("正在连接…", 13f, Palette.muted).apply { setPadding(dp(22), dp(10), dp(22), dp(14)) }
        root.addView(status)
        content = column()
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        showContent(root)
        if ((savedInstanceState?.getString("loginMode") ?: intent.getStringExtra("mode")) == "web") showWeb() else showQr()
    }

    private fun showQr(force: Boolean = false) {
        loginMode = "qr"
        loginJob?.cancel(); cookieJob?.cancel()
        webView?.let { it.stopLoading(); it.destroy() }; webView = null
        content.removeAllViews()
        val scroll = ScrollView(this)
        val page = column().apply { setPadding(dp(24), dp(16), dp(24), dp(24)); gravity = Gravity.CENTER_HORIZONTAL }
        bitmap = null
        val image = object : ImageView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val side = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(256))
                setMeasuredDimension(side, side)
            }
        }.apply { setBackgroundColor(android.graphics.Color.WHITE); contentDescription = "微信登录二维码"; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        page.addView(image, LinearLayout.LayoutParams(-1, -2)); page.gap(22)
        page.block(label("使用微信扫一扫登录", 21f, bold = true).apply { gravity = Gravity.CENTER }, 12)
        page.block(label("保存二维码，在微信扫一扫中从相册识别；确认后返回助手。过期时请重新获取。", 14f, Palette.muted), 20)
        val save = action("保存二维码到相册", false) { saveQr() }
        val refresh = action("重新获取二维码", false) { showQr(force = true) }
        val web = action("改用网页登录", false) { showWeb() }
        page.block(save, 10); page.block(refresh, 10); page.block(web)
        scroll.addView(page); content.addView(scroll)
        qrAttempt = graph.qrLogin.start(domain, force)
        val observingAttempt = qrAttempt
        var protected = true
        if (graph.qrLogin.state.value.active) try {
            startForegroundService(Intent(this, LoginService::class.java))
        } catch (_: RuntimeException) { protected = false }
        loginJob = scope.launch {
            var displayed: ByteArray? = null
            graph.qrLogin.state.collect { state ->
                if (state.attempt != observingAttempt || state.domain != domain) return@collect
                status.text = if (!protected && state.phase == QrLoginPhase.WAITING)
                    getString(R.string.login_without_background, state.message) else state.message
                val busy = state.phase in listOf(QrLoginPhase.VERIFYING, QrLoginPhase.SAVING)
                refresh.isEnabled = !busy; web.isEnabled = !busy
                save.isEnabled = state.phase == QrLoginPhase.WAITING && state.image != null
                if (displayed !== state.image) {
                    displayed = state.image
                    bitmap = state.image?.let { bytes ->
                        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    }
                    image.setImageBitmap(bitmap)
                    if (state.image != null && bitmap == null) {
                        graph.qrLogin.cancel(observingAttempt, "二维码图片无法识别，请重新获取")
                    }
                }
                if (state.phase == QrLoginPhase.SUCCESS) {
                    completing = true
                    finish()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb() {
        loginMode = "web"
        loginJob?.cancel(); cookieJob?.cancel()
        graph.qrLogin.cancel(qrAttempt)
        bitmap = null
        content.removeAllViews()
        status.text = "请在雨课堂页面完成登录，完成后会自动返回"
        val browser = try { WebView(this) } catch (_: RuntimeException) {
            webUnavailable(); return
        } catch (_: LinkageError) { webUnavailable(); return }
        webView?.destroy(); webView = browser
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(browser, true) }
        // No JavaScript interface is installed: web content has no native credential bridge.
        browser.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (webView === view) {
                    cookieJob?.cancel()
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy(); webView = null
                    webUnavailable()
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https") return false
                if (uri.scheme == "weixin") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel(); status.text = "页面证书验证失败，请检查手机时间和网络"
            }
        }
        content.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(action("返回扫码登录", false) { showQr() }, touchParams())
        browser.loadUrl("https://$domain/")
        cookieJob = scope.launch {
            var lastAttempt = ""
            var attemptAt = 0L
            while (isActive && !completing) {
                delay(1_500)
                val cookie = CookieManager.getInstance().getCookie("https://$domain/").orEmpty()
                val session = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("sessionid=") }
                    ?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: continue
                if (lastAttempt == session && System.currentTimeMillis() - attemptAt < 15_000) continue
                lastAttempt = session; attemptAt = System.currentTimeMillis()
                try {
                    val account = withContext(Dispatchers.IO) { graph.api.account(domain, session) }
                    complete(account)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Wait for the site to finish exchanging its login cookie. */ }
            }
        }
    }

    private fun webUnavailable() {
        content.removeAllViews()
        status.setText(R.string.webview_unavailable)
        content.block(action("改用扫码登录") { showQr() })
        content.block(action("重试网页登录", false) { showWeb() })
    }

    private suspend fun complete(account: Account) {
        if (completing) return
        completing = true
        status.text = "登录成功，正在保存…"
        try {
            withContext(Dispatchers.IO) { graph.store.signIn(account) }
            finish()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { completing = false; status.text = "手机暂时无法保存登录信息，请重试" }
    }

    private fun saveQr() {
        val image = bitmap ?: run { Toast.makeText(this, "请等待二维码加载", Toast.LENGTH_SHORT).show(); return }
        if (graph.qrLogin.state.value.let { it.attempt != qrAttempt || it.phase != QrLoginPhase.WAITING }) {
            Toast.makeText(this, "本次二维码已结束，请重新获取", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT < 29) {
            pendingSave = image; pendingAttempt = qrAttempt
            // Android 8/9 use the system document picker: no broad storage permission needed.
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/png"
                    putExtra(Intent.EXTRA_TITLE, "yuke-login-${System.currentTimeMillis()}.png")
                }, 41)
            } catch (_: Exception) { Toast.makeText(this, "此系统没有可用的文件保存界面，请改用网页登录或另一台设备扫码", Toast.LENGTH_LONG).show() }
            return
        }
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                var uri: Uri? = null
                try {
                    uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "yuke-login-${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YukeHelper")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }) ?: return@withContext false
                    contentResolver.openOutputStream(uri)?.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        ?: error("Image stream unavailable")
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    true
                } catch (_: Exception) { uri?.let { contentResolver.delete(it, null, null) }; false }
            }
            Toast.makeText(this@LoginActivity, if (saved) "已保存，请在微信扫一扫中选择相册" else "保存失败，请重试", Toast.LENGTH_LONG).show()
        }
    }
    @Deprecated("Platform document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val image = pendingSave
        pendingSave = null
        if (image == null || pendingAttempt != qrAttempt || graph.qrLogin.state.value.let {
                it.attempt != pendingAttempt || it.phase != QrLoginPhase.WAITING
            }) { Toast.makeText(this, "本次二维码已结束，请重新保存", Toast.LENGTH_LONG).show(); return }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openOutputStream(uri)?.use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    ?: error("Image stream unavailable")
            }.isSuccess }
            Toast.makeText(this@LoginActivity, if (saved) "二维码已保存到所选位置，可在微信中选择此图片" else "保存失败，请重新选择位置", Toast.LENGTH_LONG).show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("loginMode", loginMode)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (isFinishing && loginMode == "qr") graph.qrLogin.cancel(qrAttempt)
        scope.cancel()
        webView?.let { it.stopLoading(); it.destroy() }
        super.onDestroy()
    }
}
