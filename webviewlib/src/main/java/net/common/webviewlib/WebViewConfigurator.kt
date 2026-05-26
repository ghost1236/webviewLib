package net.common.webviewlib

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.webkit.WebViewAssetLoader

/**
 * WebView 의 공통 설정을 한 곳에서 일괄 적용하는 헬퍼.
 *
 * 설정값이 액티비티마다 흩어지면 누락/불일치가 생기므로, 원격(HTTPS)·로컬(assets) 양쪽에
 * 공통으로 필요한 WebSettings 를 여기에 모아 둔다.
 */
object WebViewConfigurator {

    /** 로컬 assets 를 매핑할 가상 도메인. WebViewAssetLoader 기본값과 동일. */
    const val ASSET_DOMAIN = "appassets.androidplatform.net"

    /**
     * WebView 에 표준 설정을 적용한다.
     *
     * @param webView 대상 WebView
     * @param enableThirdPartyCookies 서드파티 쿠키 허용 여부(로그인/결제 연동 시 필요할 수 있음)
     *
     * @JavascriptInterface 보안 경고를 막기 위해 setJavaScriptEnabled 사용은 호출부 책임으로 둔다.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView, enableThirdPartyCookies: Boolean = true) {
        webView.settings.apply {
            // JS 및 저장소 — 대부분의 웹앱이 동작하기 위한 최소 조건
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage / sessionStorage
            @Suppress("DEPRECATION")
            databaseEnabled = true

            // 뷰포트 — 페이지를 화면 폭에 맞춰 표시
            useWideViewPort = true              // <meta viewport> 적용
            loadWithOverviewMode = true         // 전체 페이지를 폭에 맞춤

            // 줌 — 핀치 줌은 허용하되 화면 위 +/- 컨트롤은 숨김
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // 캐시 — 네트워크 우선, 없으면 캐시 사용(기본 정책)
            cacheMode = WebSettings.LOAD_DEFAULT

            // HTTPS 페이지 내 HTTP 리소스 허용(혼합 콘텐츠). 호환성 모드로 완화.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // 동영상 자동재생 등 사용자 제스처 없이 미디어 재생 허용
            mediaPlaybackRequiresUserGesture = false

            // 새 창(window.open / target=_blank) 지원을 위한 멀티윈도우 허용
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            // WebViewAssetLoader 를 쓰므로 file:// 직접 접근은 비활성(보안 권장값)
            allowFileAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        // 쿠키 — 세션 유지를 위해 기본 허용. 서드파티 쿠키는 옵션으로 제어.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, enableThirdPartyCookies)
        }
    }

    /**
     * 로컬 assets 로딩용 AssetLoader 생성.
     *
     * 반환된 loader 로 `https://appassets.androidplatform.net/assets/index.html` 형태의 URL 을 로드하면
     * 앱의 `src/main/assets/` 파일이 응답된다. file:// 방식보다 same-origin 정책상 안전하다.
     */
    fun createAssetLoader(context: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
            .build()
}
