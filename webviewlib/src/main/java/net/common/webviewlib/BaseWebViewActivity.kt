package net.common.webviewlib

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * WebView 전용 화면의 공통 베이스 액티비티.
 *
 * 서브클래스는 보통 [getUrl] 만 구현하면 된다.
 * 파일 업/다운로드, 카메라/마이크 권한, 동영상 전체화면, 뒤로가기, 쿠키 flush, 진행률 표시를
 * 모두 베이스가 처리한다.
 *
 * 최소 사용:
 * ```
 * class MainWebActivity : BaseWebViewActivity() {
 *     override fun getUrl() = "https://example.com"
 * }
 * ```
 *
 * 필요 시 [getLayoutResId]/[useAssetLoader]/[enableThirdPartyCookies]/[setupBridge] 등을 override 한다.
 * 라이브러리에는 INTERNET 만 선언돼 있으므로, 카메라/마이크/위치/다운로드(API≤28)를 쓰면
 * 앱 매니페스트에 해당 권한을 추가해야 한다.
 */
abstract class BaseWebViewActivity : AppCompatActivity() {

    /** 서브클래스에서 직접 제어할 수 있도록 노출 */
    protected lateinit var webView: WebView
        private set

    protected var progressBar: ProgressBar? = null
    private var fullscreenContainer: FrameLayout? = null
    protected lateinit var chromeClient: BaseWebChromeClient
        private set

    // --- override 가능한 hook ----------------------------------------------

    /** 로드할 URL (필수) */
    protected abstract fun getUrl(): String

    /** 화면 레이아웃. 커스텀 시 webviewlib_webview/_progress/_fullscreen id 를 유지할 것 */
    protected open fun getLayoutResId(): Int = R.layout.webviewlib_activity_web

    /** 로컬 assets(https://appassets.androidplatform.net/assets/...) 를 쓸 경우 true */
    protected open fun useAssetLoader(): Boolean = false

    /** 서드파티 쿠키 허용 여부 */
    protected open fun enableThirdPartyCookies(): Boolean = true

    /** JS Bridge 등록 지점. webView.registerBridge("...", obj) 호출 */
    protected open fun setupBridge(webView: WebView) {}

    /** 페이지 로딩 이벤트 hook */
    protected open fun onPageStarted(url: String?) {}
    protected open fun onPageFinished(url: String?) {}
    protected open fun onWebError(errorCode: Int, description: String?, isMainFrame: Boolean) {}

    // --- 내부 상태 ----------------------------------------------------------

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    private var pendingPermissionRequest: PermissionRequest? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request != null) {
                val allGranted = results.isNotEmpty() && results.values.all { it }
                if (allGranted) request.grant(request.resources) else request.deny()
            }
        }

    private var pendingDownload: (() -> Unit)? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val action = pendingDownload
            pendingDownload = null
            if (isGranted) action?.invoke()
        }

    // --- 생명주기 ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getLayoutResId())

        webView = findViewById(R.id.webviewlib_webview)
        progressBar = findViewById(R.id.webviewlib_progress)
        fullscreenContainer = findViewById(R.id.webviewlib_fullscreen)

        setupWebView()
        setupOnBackPressed()

        if (savedInstanceState == null) webView.loadUrl(getUrl())
        else webView.restoreState(savedInstanceState)
    }

    private fun setupWebView() {
        WebViewConfigurator.apply(webView, enableThirdPartyCookies())

        val assetLoader = if (useAssetLoader()) WebViewConfigurator.createAssetLoader(this) else null

        webView.webViewClient = BaseWebViewClient(this, assetLoader).apply {
            onPageStartedCallback = { url ->
                progressBar?.visibility = View.VISIBLE
                this@BaseWebViewActivity.onPageStarted(url)
            }
            onPageFinishedCallback = { url ->
                progressBar?.visibility = View.GONE
                this@BaseWebViewActivity.onPageFinished(url)
            }
            onErrorCallback = { code, desc, isMainFrame ->
                if (isMainFrame) progressBar?.visibility = View.GONE
                onWebError(code, desc, isMainFrame)
            }
        }

        chromeClient = BaseWebChromeClient(this, fullscreenContainer).apply {
            progressCallback = { p ->
                progressBar?.progress = p
                progressBar?.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            fileChooserCallback = { callback, params -> openFileChooser(callback, params) }
            permissionRequestCallback = { request -> handleWebPermission(request) }
            onFullscreenChanged = { isFull -> applyFullscreen(isFull) }
        }
        webView.webChromeClient = chromeClient

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        // 서브클래스가 JS Bridge 를 등록할 지점
        setupBridge(webView)
    }

    // --- 파일 선택 ----------------------------------------------------------

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ) {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        val intent = params?.createIntent()
        if (intent == null) {
            filePathCallback = null
            return
        }
        try {
            fileChooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    // --- 다운로드 -----------------------------------------------------------

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !granted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pendingDownload = { startDownload(url, userAgent, contentDisposition, mimeType) }
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Downloading…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        } catch (e: Exception) {
            // 잘못된 URL/권한 문제 등 — 크래시 방지
        }
    }

    // --- 권한 (웹 getUserMedia → Android 런타임 권한) ------------------------

    private fun handleWebPermission(request: PermissionRequest) {
        val androidPermissions = mutableListOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions.add(android.Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        if (androidPermissions.isEmpty()) {
            request.grant(request.resources)
            return
        }
        val notGranted = androidPermissions.filter { !granted(it) }
        if (notGranted.isEmpty()) {
            request.grant(request.resources)
        } else {
            pendingPermissionRequest = request
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // --- 전체화면 -----------------------------------------------------------

    private fun applyFullscreen(isFullScreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullScreen) {
            webView.visibility = View.GONE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            webView.visibility = View.VISIBLE
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // --- 뒤로가기 -----------------------------------------------------------

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    chromeClient.isFullScreen -> chromeClient.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // --- 상태 보존 / 정리 ---------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
