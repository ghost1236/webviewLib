package kr.smiling.smwebview

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
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
import net.common.webviewlib.BaseWebChromeClient
import net.common.webviewlib.BaseWebViewClient
import net.common.webviewlib.WebViewConfigurator

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var chromeClient: BaseWebChromeClient

    // 파일 선택 결과를 WebView 로 되돌려줄 콜백 보관
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 파일 선택 화면 실행기
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    // 웹 권한 요청(getUserMedia)을 보류했다가, 런타임 권한 결과를 받아 grant/deny 처리하기 위한 보관
    private var pendingPermissionRequest: PermissionRequest? = null

    // 런타임 권한 요청기 — onPermissionRequest 가 요구한 카메라/마이크 권한을 요청하고 그 결과로 grant/deny
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request != null) {
                // 요청한 권한이 모두 허용된 경우에만 웹 권한 grant, 하나라도 거부면 deny
                val allGranted = results.isNotEmpty() && results.values.all { it }
                if (allGranted) request.grant(request.resources) else request.deny()
            }
        }

    // 저장소 권한 획득 후 재개할 다운로드 작업 보관 (API 28 이하 전용)
    private var pendingDownload: (() -> Unit)? = null

    // 다운로드용 저장소 권한 요청기 — 허용되면 보류했던 다운로드를 재개
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            val action = pendingDownload
            pendingDownload = null
            if (isGranted) action?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        setupWebView()
        setupOnBackPressed()

        // 회전 등으로 재생성되지 않도록 configChanges 를 설정했으므로, 최초 1회만 로드
        if (savedInstanceState == null) {
            webView.loadUrl(REMOTE_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun setupWebView() {
        // 1) 공통 WebSettings 적용
        WebViewConfigurator.apply(webView)

        // 2) 로컬 assets 로딩용 AssetLoader (https://appassets.androidplatform.net/assets/...)
        val assetLoader = WebViewConfigurator.createAssetLoader(this)

        // 3) WebViewClient — URL 라우팅 + assets 가로채기 + 에러 처리
        val viewClient = BaseWebViewClient(this, assetLoader).apply {
            onPageStartedCallback = { progressBar.visibility = View.VISIBLE }
            onPageFinishedCallback = { progressBar.visibility = View.GONE }
            onErrorCallback = { _, _, isMainFrame ->
                if (isMainFrame) progressBar.visibility = View.GONE
            }
        }
        webView.webViewClient = viewClient

        // 4) WebChromeClient — 파일선택/진행률/권한/위치/전체화면
        chromeClient = BaseWebChromeClient(this, fullscreenContainer).apply {
            progressCallback = { p ->
                progressBar.progress = p
                progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
            }
            fileChooserCallback = { callback, params -> openFileChooser(callback, params) }
            permissionRequestCallback = { request -> handleWebPermission(request) }
            onFullscreenChanged = { isFull -> applyFullscreen(isFull) }
        }
        webView.webChromeClient = chromeClient

        // 5) 다운로드 처리
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

    }

    // --- 파일 선택 ----------------------------------------------------------

    private fun openFileChooser(
        callback: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ) {
        // 이전 요청이 남아 있으면 취소 처리(WebView 멈춤 방지)
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
        // API 28 이하에서는 공개 다운로드 폴더 쓰기에 런타임 저장소 권한이 필요.
        // 미보유 시 권한을 요청하고, 허용되면 동일 다운로드를 재개한다. (API 29+ 는 권한 불필요)
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
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            // 잘못된 URL/권한 문제 등 — 크래시 방지
        }
    }

    // --- 권한 (웹 getUserMedia → Android 런타임 권한 연동) -------------------

    private fun handleWebPermission(request: PermissionRequest) {
        // 웹이 요청한 리소스를 대응하는 Android 런타임 권한으로 매핑
        val androidPermissions = mutableListOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> androidPermissions.add(android.Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> androidPermissions.add(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        // 런타임 권한이 필요 없는 리소스(DRM 등)는 그대로 허용
        if (androidPermissions.isEmpty()) {
            request.grant(request.resources)
            return
        }
        val notGranted = androidPermissions.filter { !granted(it) }
        if (notGranted.isEmpty()) {
            // 이미 필요한 권한을 모두 보유 → 즉시 허용
            request.grant(request.resources)
        } else {
            // 미보유 권한을 요청하고, 결과 콜백(permissionLauncher)에서 grant/deny 결정
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

    // --- 생명주기 / 뒤로가기 ------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // 세션 쿠키가 디스크에 보존되도록 flush (앱 재시작 시 로그인 유지)
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // targetSdk 35 권장 방식: onBackPressed 오버라이드 대신 OnBackPressedDispatcher 사용(predictive back 대응)
    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // 전체화면 동영상이면 먼저 빠져나옴
                    chromeClient.isFullScreen -> chromeClient.onHideCustomView()
                    // 웹 히스토리가 있으면 뒤로
                    webView.canGoBack() -> webView.goBack()
                    // 더 처리할 게 없으면 콜백을 끄고 시스템 기본 동작(액티비티 종료)에 위임
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        // 메모리 누수 방지: 부모에서 분리 후 파기
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // 원격 HTTPS 페이지
        private const val REMOTE_URL = "https://m.naver.com"

        // 로컬 assets 로드 시(src/main/assets/index.html):
        // private const val LOCAL_URL = "https://appassets.androidplatform.net/assets/index.html"
    }
}
