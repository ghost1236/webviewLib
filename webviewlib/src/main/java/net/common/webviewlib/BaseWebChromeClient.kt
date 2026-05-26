package net.common.webviewlib

import android.app.Activity
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/**
 * 공통 WebChromeClient.
 *
 * 책임:
 *  - 파일 선택(onShowFileChooser) — 실제 오버라이드하여 콜백으로 위임
 *  - 로딩 진행률(onProgressChanged)
 *  - getUserMedia 권한(onPermissionRequest, 카메라/마이크)
 *  - 위치 권한(onGeolocationPermissionsShowPrompt)
 *  - 동영상 전체화면(onShowCustomView / onHideCustomView)
 *  - 팝업 창(onCreateWindow / onCloseWindow)
 *
 * @param activity 컨텍스트 용도
 * @param layout 전체화면 커스텀 뷰를 올릴 컨테이너(없으면 전체화면 미동작)
 */
class BaseWebChromeClient constructor(
    activity: Activity,
    layout: FrameLayout? = null
) : WebChromeClient() {

    private var mActivity: Activity? = null
    private var mLayout: FrameLayout? = null
    private var mCustomView: View? = null
    private var mCustomViewCallback: CustomViewCallback? = null
    var isFullScreen = false
        private set

    // --- 외부 위임 콜백 ------------------------------------------------------

    /** 파일 선택 요청 — Activity 에서 ActivityResultLauncher 로 파일 선택 후 ValueCallback 에 결과 전달 */
    var fileChooserCallback: ((ValueCallback<Array<Uri>>?, FileChooserParams?) -> Unit)? = null

    /** window.open / target=_blank 등으로 새 창 요청 */
    var createWindowCallback: ((String?, Message?) -> Unit)? = null

    /** 창 닫기 요청 */
    var closeWindowCallback: (() -> Unit)? = null

    /** 로딩 진행률(0~100) */
    var progressCallback: ((Int) -> Unit)? = null

    /** getUserMedia 권한 요청 — 미지정 시 요청한 리소스를 그대로 grant */
    var permissionRequestCallback: ((PermissionRequest) -> Unit)? = null

    /** 위치 권한 프롬프트 — 미지정 시 허용 */
    var geolocationCallback: ((String?, GeolocationPermissions.Callback?) -> Unit)? = null

    /** 전체화면 진입/이탈 — Activity 에서 시스템 UI/화면 방향을 처리하도록 알림 */
    var onFullscreenChanged: ((isFullScreen: Boolean) -> Unit)? = null

    init {
        mActivity = activity
        mLayout = layout
    }

    // --- 파일 선택 ----------------------------------------------------------

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // 콜백 미등록 시 시스템에 즉시 null 을 돌려줘야 WebView 가 멈추지 않음
        val cb = fileChooserCallback ?: run {
            filePathCallback?.onReceiveValue(null)
            return false
        }
        cb.invoke(filePathCallback, fileChooserParams)
        return true
    }

    // --- 진행률 -------------------------------------------------------------

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        progressCallback?.invoke(newProgress)
    }

    // --- 권한(getUserMedia) -------------------------------------------------

    override fun onPermissionRequest(request: PermissionRequest?) {
        request ?: return
        val cb = permissionRequestCallback
        if (cb != null) {
            cb.invoke(request)
        } else {
            // 기본 동작: 요청 리소스를 grant (앱이 런타임 권한을 이미 확보한 경우를 전제)
            request.grant(request.resources)
        }
    }

    // --- 위치 권한 ----------------------------------------------------------

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val cb = geolocationCallback
        if (cb != null) {
            cb.invoke(origin, callback)
        } else {
            // 기본 동작: 허용(retain=false). 정밀 제어가 필요하면 콜백을 등록할 것.
            callback?.invoke(origin, true, false)
        }
    }

    // --- 동영상 전체화면 ----------------------------------------------------

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        // 이미 전체화면이면 이전 것을 정리
        if (mCustomView != null) {
            onHideCustomView()
            return
        }
        val container = mLayout
        if (container == null || view == null) {
            // 컨테이너가 없으면 전체화면 불가 — 콜백을 닫아 WebView 상태를 정리
            callback?.onCustomViewHidden()
            return
        }
        mCustomView = view
        mCustomViewCallback = callback
        isFullScreen = true
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.visibility = View.VISIBLE
        onFullscreenChanged?.invoke(true)
    }

    override fun onHideCustomView() {
        val view = mCustomView ?: return
        mLayout?.removeView(view)
        mLayout?.visibility = View.GONE
        mCustomView = null
        isFullScreen = false
        mCustomViewCallback?.onCustomViewHidden()
        mCustomViewCallback = null
        onFullscreenChanged?.invoke(false)
    }

    // --- 팝업 창 ------------------------------------------------------------

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        resultMsg ?: return false

        // 콜백이 등록돼 있으면 새 창 처리를 앱에 위임(별도 팝업/탭 관리 등)
        val cb = createWindowCallback
        if (cb != null) {
            cb.invoke(view.url, resultMsg)
            return true
        }

        // 기본 동작: 별도 창을 만들지 않고, 새 창이 열려는 URL 을 메인 WebView 에서 로드한다.
        // window.open / target=_blank 는 onCreateWindow 시점에 URL 을 모르므로,
        // 임시 WebView 로 한 번 받아 URL 만 추출한 뒤 메인(view)에 로드하고 임시 뷰는 파기한다.
        val activity = mActivity ?: return false
        if (activity.isFinishing) return false

        val tempWebView = WebView(activity)
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let { view.loadUrl(it) }
                tempWebView.destroy()
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                url?.let { view.loadUrl(it) }
                tempWebView.destroy()
                return true
            }
        }

        val transport = resultMsg.obj as? WebView.WebViewTransport
        transport?.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        window?.destroy()
        super.onCloseWindow(window)
        closeWindowCallback?.invoke()
    }

    // --- JS 다이얼로그(alert / confirm / prompt) ----------------------------
    // 기본 시스템 다이얼로그는 출처 URL 이 노출되고 앱 톤과 어긋나므로, AppCompat 다이얼로그로 대체.

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        val activity = mActivity ?: return false
        if (activity.isFinishing) return false
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setCancelable(false)
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        val activity = mActivity ?: return false
        if (activity.isFinishing) return false
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
            .setCancelable(false)
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        val activity = mActivity ?: return false
        if (activity.isFinishing) return false
        val input = EditText(activity).apply { setText(defaultValue) }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
            .setCancelable(false)
            .show()
        return true
    }
}
