package net.common.webviewlib

import android.webkit.WebView

/**
 * JS Bridge 공통 헬퍼 (네이티브 ↔ 웹 통신).
 *
 * 역할 분담:
 *  - 라이브러리(여기): 등록 + 네이티브→JS 호출 "기반"만 제공. 스키마에 관여하지 않음.
 *  - 앱: SQLite 접근 등 자신의 스키마에 맞는 @JavascriptInterface 객체를 직접 정의.
 *
 * 실제 프로젝트에서는 아래 확장 함수를 라이브러리 수정 없이 그대로 호출하면 된다.
 *
 * ⚠️ 보안: addJavascriptInterface 는 노출 범위가 WebView 전역이다.
 *          신뢰 가능한 출처(로컬 HTML + 자사 서버)에서만 사용할 것.
 * ⚠️ ProGuard(앱 모듈): bridge 클래스의 인터페이스 메서드가 난독화로 사라지지 않도록 keep 필요.
 *          -keepclassmembers class <앱의 bridge 클래스> {
 *              @android.webkit.JavascriptInterface <methods>;
 *          }
 *
 * 사용 예(앱):
 *   class AppBridge(private val ctx: Context) {
 *       @JavascriptInterface fun getInitialData(): String { ...SQLite 읽어 JSON 반환... }
 *   }
 *   webView.registerBridge("NativeBridge", AppBridge(this))
 *   // 네이티브에서 데이터가 갱신되면:
 *   webView.callJsFunction("onDataUpdated", jsonString)
 */

/** 앱이 정의한 @JavascriptInterface 객체를 WebView 에 등록한다. */
fun WebView.registerBridge(name: String, bridge: Any) {
    addJavascriptInterface(bridge, name)
}

/**
 * 네이티브 → JS 전역 함수 호출. 항상 메인 스레드에서 안전하게 실행한다.
 *
 * @param functionName 호출할 JS 전역 함수명
 * @param jsonArgs 이미 직렬화된 인자 문자열들. 문자열 인자는 따옴표를 포함해 전달할 것.
 *                 예) callJsFunction("render", "'{\"id\":1}'")  →  render('{"id":1}');
 */
fun WebView.callJsFunction(functionName: String, vararg jsonArgs: String) {
    val joined = jsonArgs.joinToString(",")
    val script = "$functionName($joined);"
    post { evaluateJavascript(script, null) }
}
