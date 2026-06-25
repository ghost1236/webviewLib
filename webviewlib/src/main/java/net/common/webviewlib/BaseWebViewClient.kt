package net.common.webviewlib

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * 공통 WebViewClient.
 *
 * 책임:
 *  - URL 라우팅: 웹 스킴(http/https)은 WebView 가 처리, 그 외(tel/mailto/intent/market 등)는 외부 앱으로 위임
 *  - 로컬 assets 가로채기(WebViewAssetLoader)
 *  - 페이지 로딩 상태 / 에러 / SSL / HTTP 에러를 콜백으로 외부에 전달
 *
 * @param activity 외부 인텐트 실행 및 컨텍스트 용도
 * @param assetLoader 로컬 assets 로딩 시 주입(없으면 가로채기 안 함)
 */
class BaseWebViewClient(
    private val activity: Activity,
    private val assetLoader: WebViewAssetLoader? = null
) : WebViewClient() {

    /** 페이지 로딩 시작/종료 콜백 — 프로그레스바 표시 등에 사용 */
    var onPageStartedCallback: ((url: String?) -> Unit)? = null
    var onPageFinishedCallback: ((url: String?) -> Unit)? = null

    /** 리소스 로딩 에러 콜백(errorCode, description, 메인 프레임 여부) */
    var onErrorCallback: ((errorCode: Int, description: String?, isMainFrame: Boolean) -> Unit)? = null

    /**
     * SSL 에러 처리 콜백. 미지정 시 기본 정책은 "거부"(보안 우선).
     * true 반환 = 직접 처리함(콜백에서 proceed/cancel 결정), false = 기본 거부.
     */
    var onSslErrorCallback: ((handler: SslErrorHandler, error: SslError) -> Boolean)? = null

    /**
     * 내비게이션 가로채기 훅. 웹 스킴(http/https — 로컬 appassets 포함) 메인 이동 직전에 호출된다.
     * 반환값:
     *  - 다른 URL(non-null & 원본과 다름): 그 URL 로 대체 로드(원 로드 취소).
     *  - null 또는 같은 URL: WebView 가 원래대로 로드.
     *  - [CANCEL_NAVIGATION]: 이동 취소 — 대체 로드/외부 위임 없이 현재 화면을 그대로 유지(stay).
     * (오프라인 소스 전환/이동 차단용. 기본 null = 동작 변화 없음 — 하위호환)
     */
    var navigationResolver: ((url: String) -> String?)? = null

    // --- URL 라우팅 ---------------------------------------------------------

    // API 24+ : WebResourceRequest 기반
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        handleUrl(view, request?.url)

    // API 21~23 : 문자열 기반(deprecated 지만 minSdk 21 대응 위해 함께 구현)
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
        handleUrl(view, url?.let { runCatching { Uri.parse(it) }.getOrNull() })

    /**
     * @return true = 외부에서 처리(WebView 로드 중단), false = WebView 가 계속 로드
     */
    private fun handleUrl(view: WebView?, uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase() ?: return false
        return when (scheme) {
            // 웹 스킴: 내비게이션 가로채기 훅으로 소스 전환(로컬↔서버)/취소 기회를 준 뒤, 아니면 WebView 가 로드
            "http", "https" -> {
                val target = uri.toString()
                when (val replacement = navigationResolver?.invoke(target)) {
                    // 이동 취소 신호: 대체 로드도 외부 위임도 없이 현재 화면을 그대로 유지(오프라인 차단 등)
                    CANCEL_NAVIGATION -> true
                    null, target -> false                              // 원래 URL 그대로 로드
                    else -> { view?.loadUrl(replacement); true }       // 대체 URL 로 재로드(원 로드 취소)
                }
            }
            // 그 외 스킴은 외부 앱으로 위임(전화/메일/지도/스토어/커스텀 스킴 등)
            else -> {
                launchExternal(uri)
                true
            }
        }
    }

    private fun launchExternal(uri: Uri) {
        try {
            // intent:// 스킴은 Intent.parseUri 로 복원
            val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 처리할 앱이 없으면 조용히 무시(필요 시 마켓 fallback 을 호출부에서 추가)
        } catch (e: Exception) {
            // 잘못된 intent URI 등 — 크래시 방지
        }
    }

    // --- 로컬 assets 가로채기 -----------------------------------------------

    // API 21+ : WebResourceRequest 기반. assetLoader 가 처리 못하면 null 반환 → 정상 네트워크 로드
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        return assetLoader?.shouldInterceptRequest(url)
            ?: super.shouldInterceptRequest(view, request)
    }

    // --- 로딩 상태 ----------------------------------------------------------

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStartedCallback?.invoke(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback?.invoke(url)
    }

    // --- 에러 처리 ----------------------------------------------------------

    // API 23+ : 상세 에러 정보 제공
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isMain = request?.isForMainFrame ?: false
            onErrorCallback?.invoke(error?.errorCode ?: -1, error?.description?.toString(), isMain)
        }
    }

    // API 21~22 : 구형 시그니처
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        @Suppress("DEPRECATION")
        super.onReceivedError(view, errorCode, description, failingUrl)
        // 구형에서는 메인 프레임 여부를 알 수 없으므로 true 로 간주
        onErrorCallback?.invoke(errorCode, description, true)
    }

    // HTTP 상태 에러(404, 500 등). onReceivedHttpError 는 리소스 단위로도 불리므로(이미지/CSS 등),
    // 메인 프레임일 때만 에러 콜백으로 전달한다 — 서브리소스 404 로 페이지 전체가 폴백되지 않도록.
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            onErrorCallback?.invoke(errorResponse?.statusCode ?: -1, errorResponse?.reasonPhrase, true)
        }
    }

    // SSL 에러 — 기본은 거부(cancel). 콜백이 있으면 위임.
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (handler == null) return
        val cb = onSslErrorCallback
        if (cb != null && error != null && cb(handler, error)) {
            return // 콜백이 직접 proceed/cancel 처리
        }
        // 안전 기본값: 인증서 오류 시 연결 거부
        handler.cancel()
    }

    companion object {
        /**
         * [navigationResolver] 가 이 값을 반환하면 해당 내비게이션을 "취소"한다 — 대체 로드도 외부 위임도 없이
         * 현재 화면을 그대로 유지(stay)한다. (오프라인 이동 차단 등에서 팝업만 띄우고 화면은 안 바꿀 때)
         * 일반 URL 과 절대 겹치지 않도록 내부 전용 스킴 문자열을 쓴다.
         */
        const val CANCEL_NAVIGATION = "webviewlib://cancel-navigation"
    }
}
