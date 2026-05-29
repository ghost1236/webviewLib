# 단일 WebView Activity + JS 브리지 모듈 조립 — 사용법

> 작성일: 2026-05-27
> 실제 서버 구동 앱: **WebView Activity 1개**에서 **페이지 이동(navigation)** 만 일어난다(페이지마다 Activity ❌).
> 페이지마다 다른 브리지 기능은 **단일 라우터(action 분기) + 기능별 모듈 조립**으로 처리하고 Activity 는 얇게 유지한다.
> A/B 오프라인 퍼스트(로컬 즉시 → 서버 교체 → 끊기면 로컬 복귀)는 베이스 `OfflineFirstWebActivity` 가 담당한다.

## 샘플 파일 (복붙용, 빌드 비대상) — 기능별 패키지 구성
```
samples/web/                         (kr.smiling.smwebview.web — 코어/공통)
  OfflineFirstWebActivity.kt           오프라인 퍼스트 A/B 상태머신 공통 추상 베이스
  AppWebActivity.kt                    단일 WebView Activity(모듈 조립만)
  WebBridgeRouter.kt                   단일 JS 브리지 + action 라우터
  BridgeModule.kt                      기능 모듈 인터페이스
  DeviceBridge.kt                      공통 네이티브(토스트 등)
  LocalStore.kt                        SQLite 저장소
  index.offline-first.html             로컬 HTML(서버와 동일한 사본, 오프라인 시 renderData 로 데이터 주입)
samples/web/dashboard/               (kr.smiling.smwebview.web.dashboard)
  DashboardBridge.kt                   대시보드 기능 모듈(getSummary/getList/addItem)
samples/web/splash/                  (kr.smiling.smwebview.web.splash)
  SplashBridge.kt                      스플래시/부트스트랩 기능 모듈(getBootstrap)
samples/proguard-rules.pro             브리지 keep 규칙
```
> 코어 타입(`WebBridgeRouter`/`BridgeModule`/`LocalStore`)은 `web` 에 두고, 기능 모듈은 `web.<기능>` 패키지에 분리해 import 한다.

---

## 1. 단일 Activity — 조립만 (얇게 유지)

```kotlin
class AppWebActivity : OfflineFirstWebActivity() {
    private val store by lazy { LocalStore(this) }

    override fun serverUrl() = "https://m.example.com"
    // localUrl 은 베이스가 "보던 서버 경로 → 동일 경로의 로컬 사본"으로 자동 미러링(toLocalUrl).
    override fun localData() = store.snapshotJson()      // 오프라인일 때만 베이스가 push(온라인은 서버가 그림)

    override fun setupBridge(webView: WebView) {
        val router = WebBridgeRouter()
        listOf(                                          // ← 기능 추가 = 모듈 한 줄
            SplashBridge(),                              // web.splash
            DashboardBridge(store, onChanged = ::refreshLocalData),  // web.dashboard
            DeviceBridge(this),                          // web (공통)
        ).forEach { it.register(router) }
        webView.registerBridge("NativeBridge", router)
    }
}
```
> `AppWebActivity`(web)는 `import kr.smiling.smwebview.web.dashboard.DashboardBridge` / `…splash.SplashBridge` 로 각 기능 패키지 모듈을 가져와 조립한다.
> 끊기면 베이스가 "보던 서버 경로"의 로컬 사본을 자동 로드한다(§6 멀티 페이지 미러).

## 2. 단일 브리지 + 액션 라우터

```kotlin
class WebBridgeRouter {
    private val handlers = HashMap<String, (JSONObject) -> String>()
    fun on(action: String, h: (JSONObject) -> String) = apply { handlers[action] = h }

    @JavascriptInterface
    fun postMessage(message: String): String {           // 웹→네이티브 단일 진입점
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return err("bad json")
        val h = handlers[msg.optString("action")] ?: return err("no handler")
        return runCatching { h(msg.optJSONObject("params") ?: JSONObject()) }.getOrElse { err(it.message) }
    }
    companion object { const val OK = """{"ok":true}""" }
}
```
```js
// JS 공통 헬퍼(서버/로컬 페이지 공통) — 페이지마다 필요한 action 만 호출
function native(action, params){
  return JSON.parse(NativeBridge.postMessage(JSON.stringify({action, params: params||{}})));
}
native('addItem', { title: '새 항목' });   // 쓰기
native('toast',   { text: '저장됨' });     // 공통 네이티브
```

## 3. 기능별 모듈 — 패키지로 분리, god object 방지

코어 인터페이스는 `web`, 기능 모듈은 `web.<기능>` 패키지에 둔다. 모듈은 코어 타입을 import 해서 쓴다.

```kotlin
// web/BridgeModule.kt
package kr.smiling.smwebview.web
fun interface BridgeModule { fun register(router: WebBridgeRouter) }

// web/dashboard/DashboardBridge.kt  ← 다른 패키지
package kr.smiling.smwebview.web.dashboard
import kr.smiling.smwebview.web.BridgeModule
import kr.smiling.smwebview.web.LocalStore
import kr.smiling.smwebview.web.WebBridgeRouter

class DashboardBridge(val store: LocalStore, val onChanged: () -> Unit) : BridgeModule {
    override fun register(router: WebBridgeRouter) {
        router.on("getSummary") { store.summaryJson() }   // 온디맨드 읽기(선택)
        router.on("getList")    { store.recentJson() }    // 온디맨드 읽기(선택)
        router.on("addItem")    { p ->                    // 쓰기 → 오프라인이면 onChanged 로 재push
            store.insert(p.getString("title")); onChanged(); WebBridgeRouter.OK
        }
    }
}

// web/splash/SplashBridge.kt  ← 또 다른 패키지(의존 없는 모듈 예시)
package kr.smiling.smwebview.web.splash
import kr.smiling.smwebview.web.BridgeModule
import kr.smiling.smwebview.web.WebBridgeRouter
import org.json.JSONObject

class SplashBridge : BridgeModule {
    override fun register(router: WebBridgeRouter) {
        router.on("getBootstrap") { JSONObject().put("version", "1.0").put("ready", true).toString() }
    }
}
```

- 페이지/기능 추가 = **모듈 파일 1개(자기 패키지) + 조립 목록 1줄.** Activity 는 얇게 유지.
- 모듈은 필요한 의존만 주입(데이터=store, 갱신=onChanged 콜백, UI=Activity) → 베이스 protected API 에 직접 안 묶임.
- **모듈은 서로 다른 패키지에 둬도 된다.** 공유 타입(`WebBridgeRouter`/`BridgeModule`)을 public(또는 같은 Gradle 모듈이면 internal)으로 두고 import 하면 끝. ProGuard keep 은 `@JavascriptInterface` 가 있는 `WebBridgeRouter` 의 **실제 패키지**만 맞추면 된다(모듈 패키지는 무관).

## 4. ProGuard (release)
`@JavascriptInterface` 가 난독화로 사라지지 않도록 keep(라우터만):
```pro
-keepclassmembers class kr.smiling.smwebview.web.WebBridgeRouter {
    @android.webkit.JavascriptInterface <methods>;
}
```
→ `samples/proguard-rules.pro` (실제 적용 시 `app/proguard-rules.pro` 에).

## 6. 멀티 페이지 미러 — 서버=로컬 동일 HTML (오프라인 연속성)

서버 HTML 과 **동일한 HTML 을 앱 assets 에 미러링**해 두면, 끊겨도 "보던 그 페이지"를 그대로 보여줄 수 있다(빈 화면 0 + 읽기 연속성).

- **경로 보존 매핑**: 베이스가 현재 서버 URL(`currentServerUrl`)을 추적해, 끊기면 **origin 만 assets 도메인으로 바꾼**(path 유지) 로컬 사본을 로드한다.
  ```
  https://m.example.com/detail.html  →  https://appassets.androidplatform.net/assets/detail.html
  ```
  서버 path 와 assets 파일명이 다르면 `toLocalUrl(serverUrl)` 만 override. 복구되면 보던 서버 페이지로 재로드.
- **데이터: 온라인은 서버, 오프라인만 SQLite(push)** —
  · 온라인(서버 HTML): 서버가 데이터를 그린다. **네이티브는 관여하지 않음**(굳이 SQLite 읽지 않음).
  · 오프라인(로컬 사본): 베이스가 로컬 페이지 로드 직후 SQLite 데이터를 **push**(`localData()` → `renderData`). 동일 HTML 은 `renderData` 주입 지점만 정의.
  ```kotlin
  override fun localData() = store.snapshotJson()   // 오프라인일 때만 베이스가 이 값을 push
  // 멀티 페이지면: when(currentPath()){ "/list.html"->store.recentJson(); ... }
  ```
  ```js
  // 동일 HTML: 데이터 주입 지점만 정의. 온라인=서버가 채움 / 오프라인=네이티브가 호출
  window.renderData = (jsonText) => { /* JSON.parse 후 렌더 */ };
  ```
- **페이지가 많아도**: 페이지 추가 = (서버 페이지 + assets 사본) + (필요 시) `localData` 의 `currentPath()` 분기 한 줄. Activity/베이스는 그대로.
- 쓰기는 bridge action. 오프라인에서 쓰면 `onChanged`(=`refreshLocalData`)가 다시 push, 온라인은 서버가 갱신(no-op).

## 7. 주의점
- 동기 응답은 `postMessage` 반환값(요청/응답), 네이티브→JS 푸시는 `pushToWeb("onNativeMessage", json)` 같은 단일 콜백.
- `@JavascriptInterface` 는 **바인더 스레드**에서 호출 → UI/WebView 는 `runOnUiThread`.
- 세션은 쿠키(`CookieManager` 전역)라 같은 도메인이면 페이지/화면 간 자동 공유.
- assets 미러는 서버 페이지와 **동기화 유지**가 필요(서버 HTML 변경 시 사본도 갱신 — 빌드/CI 에서 복사 권장).
- origin 제한이 필요하면 `addJavascriptInterface` 대신 androidx.webkit 의 `WebViewCompat.addWebMessageListener`.
