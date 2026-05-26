# webviewlib 사용 가이드

WebView 화면을 빠르게 구성하기 위한 안드로이드 라이브러리. 설정·URL 라우팅·에러/SSL·파일 업/다운로드·권한·진행률·전체화면·팝업·네트워크 감지·JS Bridge 를 제공한다.

> 의존성은 이미 `build.gradle` 에 등록돼 있다고 가정한다.
> (jitpack 좌표 예: `com.github.ghost1236.webviewLib:webviewlib:1.0.1` — 정확한 좌표는 jitpack 페이지 참조)

---

## 제공 컴포넌트

| 컴포넌트 | 종류 | 역할 |
|---|---|---|
| **`BaseWebViewActivity`** | abstract Activity | **상속만으로 WebView 화면 완성** (아래 기능 모두 내장) |
| `WebViewConfigurator` | object | WebSettings 일괄 적용 + 쿠키 + 로컬 assets 로더 생성 |
| `BaseWebViewClient` | class | URL 라우팅 + assets 가로채기 + 에러/SSL/HTTP 에러 (콜백) |
| `BaseWebChromeClient` | class | 파일선택·진행률·권한·위치·전체화면·팝업 (콜백) |
| `NetworkMonitor` | class | 네트워크 끊김/복구 감지(메인스레드), `isOnline()` |
| `Reachability` | object | 서버 도달성 프리체크(백그라운드→메인 콜백) |
| `WebView.registerBridge` / `WebView.callJsFunction` | 확장함수 | JS Bridge 등록 + 네이티브→JS 호출 |

**두 가지 사용 방식:**
- **방식 A — 상속형(권장, 간단)**: `BaseWebViewActivity` 상속 + URL 지정 → 끝
- **방식 B — 조합형(세밀 제어)**: `WebViewConfigurator`/`BaseWebViewClient`/`BaseWebChromeClient` 를 직접 조립

권한: `INTERNET` 은 라이브러리 매니페스트에서 자동 병합된다. 카메라/마이크/위치/다운로드(API≤28)를 쓸 때만 앱 매니페스트에 해당 권한을 추가한다.

---

# 방식 A — 상속형 `BaseWebViewActivity` (권장)

대부분의 WebView 화면은 이걸로 충분하다. 파일 업/다운로드·권한·전체화면·뒤로가기·쿠키·진행률을 **베이스가 모두 처리**한다.

## A-1. 최소 사용
```kotlin
class MainWebActivity : BaseWebViewActivity() {
    override fun getUrl() = "https://example.com"
}
```
매니페스트 등록(회전 시 재생성 방지 권장):
```xml
<activity android:name=".MainWebActivity" android:exported="true"
    android:hardwareAccelerated="true"
    android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden"/>
```
> 레이아웃·WebView 바인딩·생명주기는 베이스가 처리하므로 별도 XML 이 필요 없다.

## A-2. 화면마다 액티비티 구분
상황에 따라 별도 액티비티가 필요하면 똑같이 상속만 한다.
```kotlin
class DetailWebActivity : BaseWebViewActivity() {
    override fun getUrl() = "https://example.com/detail"
}
```

## A-3. 커스터마이징 (필요할 때만 override)
```kotlin
class MyWebActivity : BaseWebViewActivity() {
    override fun getUrl() = "https://appassets.androidplatform.net/assets/index.html"

    override fun useAssetLoader() = true              // 로컬 assets(앱 src/main/assets) 사용
    override fun enableThirdPartyCookies() = false    // 서드파티 쿠키 차단
    override fun getLayoutResId() = R.layout.my_web   // 커스텀 레이아웃(아래 id 유지 필수)

    override fun setupBridge(webView: WebView) {       // JS Bridge 등록 지점
        webView.registerBridge("NativeBridge", AppBridge(this))
    }
    override fun onPageFinished(url: String?) { /* 로딩 완료 후 처리 */ }
    override fun onWebError(code: Int, desc: String?, isMainFrame: Boolean) { /* 에러 처리 */ }
}
```
- 서브클래스에서 `webView` 를 직접 제어 가능: `webView.loadUrl(...)`, `webView.callJsFunction(...)` 등
- **커스텀 레이아웃**을 쓸 때도 id 는 `webviewlib_webview` / `webviewlib_progress` / `webviewlib_fullscreen` 를 유지해야 한다
- 카메라/마이크/위치/다운로드(API≤28)를 쓰면 **앱 매니페스트에 해당 권한 선언** 필요(런타임 요청은 베이스가 처리)

> ⚠️ `BaseWebViewActivity` 는 `AppCompatActivity` 를 상속한다. 다른 베이스(commonlib `BaseActivity` 등)를 동시에 상속해야 하는 화면은 단일 상속 제약상 **방식 B** 로 구성한다.

---

# 방식 B — 조합형 (세밀 제어)

WebView 가 화면 일부이거나 특수 구성이 필요할 때, 또는 다른 베이스를 상속 중이라 `BaseWebViewActivity` 를 쓸 수 없을 때 도구를 직접 조립한다.

## B-1. 최소 사용
```xml
<!-- res/layout/activity_main.xml -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent">
    <WebView android:id="@+id/webView"
        android:layout_width="match_parent" android:layout_height="match_parent"/>
</FrameLayout>
```
```kotlin
webView = findViewById(R.id.webView)
WebViewConfigurator.apply(webView)                  // 공통 설정 + 쿠키
webView.webViewClient = BaseWebViewClient(this)     // URL/에러 처리
webView.webChromeClient = BaseWebChromeClient(this) // 파일/권한/팝업
webView.loadUrl("https://example.com")
```

## B-2. 로컬 assets 로드
HTML 은 **앱의 `src/main/assets/`** 에 둔다.
```kotlin
val assetLoader = WebViewConfigurator.createAssetLoader(this)
webView.webViewClient = BaseWebViewClient(this, assetLoader)   // 주입이 핵심
webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
```

## B-3. 로딩/에러/SSL (BaseWebViewClient 콜백)
```kotlin
val client = BaseWebViewClient(this, assetLoader).apply {
    onPageStartedCallback  = { progressBar.visibility = View.VISIBLE }
    onPageFinishedCallback = { progressBar.visibility = View.GONE }
    onErrorCallback = { code, desc, isMainFrame -> if (isMainFrame) { /* 처리 */ } }
    onSslErrorCallback = { handler, error -> false }   // true 반환 시 직접 proceed/cancel
}
webView.webViewClient = client
```
> URL 라우팅 내장: `http/https` 는 WebView 로드, `tel:/mailto:/intent:/market:` 등은 외부 앱 위임.

## B-4. 진행률/파일/권한/전체화면 (BaseWebChromeClient 콜백)
```kotlin
val chrome = BaseWebChromeClient(this, fullscreenContainer).apply {
    progressCallback = { p -> progressBar.progress = p }
    fileChooserCallback = { callback, params -> openFileChooser(callback, params) }
    permissionRequestCallback = { request -> handleWebPermission(request) }  // 카메라/마이크
    geolocationCallback = { origin, cb -> cb?.invoke(origin, true, false) }  // 위치
    onFullscreenChanged = { isFull -> applyFullscreen(isFull) }              // 동영상 전체화면
}
webView.webChromeClient = chrome
```
파일 업로드(ActivityResultLauncher), 카메라 권한 매핑, 다운로드, 뒤로가기, 쿠키 flush 등의 상세 구현은
`BaseWebViewActivity` 소스가 완성형 예시이니 그대로 참고하면 된다.

## B-5. 네트워크 감지 / 서버 프리체크
```kotlin
val monitor = NetworkMonitor(this).apply {
    onAvailable = { /* 온라인 복구 */ }
    onLost      = { /* 오프라인 */ }
}
monitor.register()    // onResume
monitor.unregister()  // onPause
// monitor.isOnline()

Reachability.check("https://server.com") { reachable ->
    if (reachable) webView.loadUrl("https://server.com")   // 실패면 로컬 유지
}
```

## B-6. JS Bridge (네이티브 ↔ 웹)
```kotlin
class AppBridge(private val ctx: Context) {
    @JavascriptInterface fun getData(): String = """{"name":"value"}"""
}
webView.registerBridge("NativeBridge", AppBridge(this))
webView.callJsFunction("onDataUpdated", jsonString)   // JS: onDataUpdated(jsonString)
```
> 웹에서는 `window.NativeBridge.getData()` 로 호출.
> ⚠️ **ProGuard(앱 모듈)**: `-keepclassmembers class <bridge클래스> { @android.webkit.JavascriptInterface <methods>; }`
> ⚠️ 보안: `addJavascriptInterface` 는 WebView 전역 노출 → 신뢰 가능한 출처에서만 사용.
