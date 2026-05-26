# smWebview — WebView 구성 분석 및 설정 정리

> 작성일: 2026-05-26
> 목적: WebView로 화면을 띄우는 앱 구성에서 **부족한 부분**과 **필요한 설정값**을 정리한다.
> 범위(확정): 실사용 수준 / 원격 HTTPS + 로컬 assets 동시 지원 / webviewlib 라이브러리 제대로 채우기 / webviewlib에서 commonlib 의존 제거 / **webviewlib 를 jitpack(AAR)으로 배포**.

---

## 1. 현재 상태 진단

2022년에 생성된 거의 빈 스켈레톤 상태이며, "WebView로 화면을 띄운다"는 목표 기준으로 핵심이 대부분 비어 있다.

| 구성요소 | 현재 상태 | 문제 |
|---|---|---|
| `app/.../MainActivity.kt` | `setContentView`만 호출 | WebView 코드 전무 |
| `app/.../res/layout/activity_main.xml` | `TextView "Hello World"` | WebView 위젯 없음 |
| `app/.../AndroidManifest.xml` | 권한 0개 | **INTERNET 권한 누락 → 아무것도 로드 불가** |
| `webviewlib/.../BaseWebViewClient.kt` | 완전히 빈 클래스(생성자만) | URL/에러 처리 전무 |
| `webviewlib/.../BaseWebChromeClient.kt` | onCreateWindow/onCloseWindow만 | 파일선택·권한·진행률·전체화면 미구현 |
| build.gradle | compileSdk/targetSdk **31** | 구버전(이번 범위에서는 유지) |

---

## 2. webviewlib 라이브러리 모듈 분석

### 2.1 모듈 구조
```
webviewlib/  (com.android.library + maven-publish)
├── libs/commonlib.jar          ← 로컬 jar (아래 2.4 참고)
├── release/webviewlib.jar       ← export 산출물 (★ 타 프로젝트 것 — 삭제 대상)
├── src/main/java/net/common/webviewlib/
│   ├── BaseWebViewClient.kt      ← 빈 클래스
│   └── BaseWebChromeClient.kt    ← onCreateWindow/onCloseWindow만
└── build.gradle  (exportJar / maven-publish 포함)
```

### 2.2 핵심 발견 — "WebView 라이브러리"인데 WebView 로직이 없음
디컴파일(javap) 확인 결과, 소스든 release jar든 **둘 다 미완성 스켈레톤**이다.

| 클래스 | 실제 내용 | 빠진 것 |
|---|---|---|
| `BaseWebViewClient` | 생성자만, 메서드 0개 | `shouldOverrideUrlLoading`, `onPageStarted/Finished`, `onReceivedError`, `onReceivedHttpError`, `onReceivedSslError` — 전부 없음 |
| `BaseWebChromeClient` | 콜백 변수 + `onCreateWindow`/`onCloseWindow` 위임만 | `onShowFileChooser`(콜백 변수만 있고 **오버라이드 자체가 없음**), `onProgressChanged`, `onPermissionRequest`(카메라/마이크), `onGeolocationPermissionsShowPrompt`, `onShowCustomView/onHideCustomView`(필드만 선언, 미구현) |

> 즉 WebView 생성·WebSettings 적용·loadUrl 모두 없음. 콜백을 외부로 넘기는 껍데기만 존재.

### 2.3 ⚠️ release/webviewlib.jar 는 stale / 타 프로젝트 산출물
```
현재 소스 패키지 :  net.common.webviewlib.*
jar 내부 패키지  :  kr.co.cesco.android.hygienecarea.web.*   ← 전혀 다름
```
- 세스코 위생관리 앱(cesco hygienecarea)에서 빌드된 산출물로, 현재 소스에서 나온 게 아님 → **신뢰 불가, 삭제 대상.**

### 2.4 commonlib.jar — (정정됨)
- **commonlib 은 사용자 본인의 공통 모듈이며 현재 jitpack 으로 배포 중.** (앞선 "출처 혼재 블랙박스/삭제 대상" 판단은 정정함.)
- 네임스페이스 3종(`kr.common` / `net.common` / `net.newfrom`) 혼재는 시간에 걸쳐 통합해온 공통 라이브러리의 흔적.
- 단, `webviewlib/libs/commonlib.jar` (로컬 파일)은 jitpack 이전의 **구버전 스냅샷**이고, 현재 webviewlib 소스는 commonlib 클래스를 하나도 실제로 사용하지 않음.
- 이번 결정: **webviewlib 에서 commonlib 의존 제거** (WebView 라이브러리는 commonlib 불필요). → commonlib 자체를 버리는 게 아니라, webviewlib 가 안 쓰는 의존을 끊는 것.

### 2.5 배포 방식 — jar export ↔ AAR
- `exportJar` task가 `.jar`를 만드는데, Android 라이브러리는 **AAR**이 정상. jar로 빼면 `res/`(themes·mipmap 등)와 `AndroidManifest`가 누락되어 리소스 참조 코드가 깨짐.
- commonlib을 jitpack으로 배포 중인 만큼, webviewlib도 동일하게 jitpack(AAR) 방식으로 가는 것이 일관적. → **결정 필요(아래 6장).**

---

## 3. 목표 아키텍처

```
app  (애플리케이션)
 └─ WebView 화면 + 런타임 권한 + ActivityResult(파일선택) 담당
      │ depends on
      ▼
webviewlib  (재사용 라이브러리, AAR)   ← commonlib 의존 제거
 ├─ WebViewConfigurator   : WebSettings 일괄 적용 (원격/로컬 공통)
 ├─ BaseWebViewClient     : URL 라우팅 + 에러/SSL/HTTP 에러 처리
 ├─ BaseWebChromeClient   : 파일선택 + 권한 + 진행률 + 전체화면 + 팝업창
 └─ (선택) WebViewActivity: 바로 쓸 수 있는 기본 액티비티
```

---

## 4. 정리(삭제·변경) 대상

| 대상 | 조치 | 이유 |
|---|---|---|
| `release/webviewlib.jar` | **삭제** | 타 프로젝트(cesco) 산출물, 신뢰 불가 |
| `webviewlib/libs/commonlib.jar` | **삭제** | jitpack 이전 구버전 스냅샷, webviewlib 미사용 |
| `build.gradle`의 `commonlib.jar` 의존 | **제거** | WebView 라이브러리는 불필요 |
| `exportJar` task (jar export) | **제거** | AAR이 정상, jar는 리소스 누락 |
| `maven-publish` 블록 | **유지·정비** | jitpack(AAR) 배포에 사용 — 아래 6장 |

---

## 5. 구현 체크리스트 (= 부족한 부분 + 설정값)

### ① 매니페스트 (app)
- [ ] `android.permission.INTERNET` (필수)
- [ ] `android.permission.CAMERA` (getUserMedia 사용 시)
- [ ] `android.permission.WRITE_EXTERNAL_STORAGE` (`maxSdkVersion="28"`, 다운로드용)
- [ ] `network_security_config.xml` 연결 (원격 HTTPS만이면 cleartext 차단 유지 권장)

### ② 레이아웃 (app)
- [ ] `activity_main.xml` 의 TextView → WebView 교체

### ③ webviewlib — WebSettings (기타 웹뷰 세팅)
- [ ] JavaScript / DOM Storage / Database
- [ ] useWideViewPort / loadWithOverviewMode / zoom
- [ ] cacheMode / mixedContentMode
- [ ] **로컬 assets용**: `WebViewAssetLoader`(권장) 또는 `file:///android_asset/` + `allowFileAccess`
- [ ] UserAgent, mediaPlaybackRequiresUserGesture

### ④ webviewlib — BaseWebViewClient (전부 신규)
- [ ] `shouldOverrideUrlLoading` (tel/mailto/intent 스킴 분기)
- [ ] `onPageStarted` / `onPageFinished`
- [ ] `onReceivedError` / `onReceivedHttpError` / `onReceivedSslError`

### ⑤ webviewlib — BaseWebChromeClient (대부분 신규)
- [ ] `onShowFileChooser` **실제 오버라이드** (현재 콜백 변수만 있음)
- [ ] `onProgressChanged` (로딩 진행률)
- [ ] `onPermissionRequest` (카메라/마이크)
- [ ] `onGeolocationPermissionsShowPrompt` (위치)
- [ ] `onShowCustomView` / `onHideCustomView` (동영상 전체화면)

### ⑥ app — MainActivity (전부 신규)
- [ ] WebView 바인딩 + Client/ChromeClient/Settings 연결
- [ ] 파일선택 `ActivityResultLauncher`
- [ ] 런타임 권한 요청 흐름
- [ ] `setDownloadListener` (DownloadManager 연동)
- [ ] CookieManager 설정 (`setAcceptCookie`, 서드파티 쿠키)
- [ ] 뒤로가기 `canGoBack()` 처리

### ⑦ (참고) 버전 — 이번 범위에서는 현 버전 유지
- compileSdk 31 / AGP 7.1.3 로도 위 기능은 동작.
- Play 스토어 배포가 필요해지면 별도로 SDK 35 / AGP 8 / Kotlin 1.9~2.x / Java 17 현대화 진행. (AGP 8 전환 시 Manifest `package=` → `namespace` 이전 필요.)

---

## 6. 미결정 사항 (구현 전 확인 필요)

1. **webviewlib 배포 전략**: commonlib처럼 jitpack(AAR)으로 배포할 것인가?
   - YES → `maven-publish` 정비 + jitpack 설정, `exportJar(jar)` 제거
   - NO  → 단순 로컬 모듈로만 사용, `maven-publish`/`exportJar` 모두 제거
2. **commonlib jitpack 좌표**: (참고용) `com.github.<user>:<repo>:<version>` — webviewlib는 의존 제거지만, app이 추후 필요 시 jitpack 좌표로 추가.

---

## 7. 권장 구현 순서

1. webviewlib 정리: `release/webviewlib.jar`·`libs/commonlib.jar` 삭제, build.gradle에서 commonlib 의존·exportJar 제거
2. webviewlib 구현: `WebViewConfigurator`, `BaseWebViewClient`, `BaseWebChromeClient` 채우기
3. app 구성: 매니페스트 권한 + network_security_config, 레이아웃 WebView, MainActivity 연결
4. 빌드·동작 확인 (원격 HTTPS / 로컬 assets 각각)

---

## 8. WebSettings 권장 설정값 (참고 스니펫)

```kotlin
webView.settings.apply {
    javaScriptEnabled = true            // JS 실행 (대부분 사이트 필수)
    domStorageEnabled = true            // localStorage / sessionStorage
    databaseEnabled = true
    loadWithOverviewMode = true         // 페이지 전체를 화면 폭에 맞춤
    useWideViewPort = true              // viewport 메타태그 적용
    builtInZoomControls = true          // 줌 허용
    displayZoomControls = false         // 줌 +/- 버튼 숨김
    cacheMode = WebSettings.LOAD_DEFAULT
    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE  // https 내 http 리소스
    mediaPlaybackRequiresUserGesture = false  // 자동재생 필요 시
    // 로컬 assets 로드 시 — WebViewAssetLoader 권장 (file:// 직접 접근보다 안전)
    allowFileAccess = false             // WebViewAssetLoader 사용 시 false 유지 권장
}
```

---

## 9. 구현 완료 현황 (2026-05-26)

배포(jitpack/maven-publish/exportJar/release) 관련은 손대지 않고 **기능만** 구현 완료.

### 생성/수정 파일
| 파일 | 내용 |
|---|---|
| `webviewlib/build.gradle` | commonlib.jar 의존 제거, `api 'androidx.webkit:webkit'` 추가, AGP 8 현대화 |
| `webviewlib/.../AndroidManifest.xml` | `INTERNET` 권한 선언(앱에 병합) |
| `webviewlib/.../WebViewConfigurator.kt` | **신규** — WebSettings 일괄 적용 + AssetLoader 생성 |
| `webviewlib/.../BaseWebViewClient.kt` | URL 라우팅 + assets 가로채기 + 에러/SSL/HTTP 에러 |
| `webviewlib/.../BaseWebChromeClient.kt` | 파일선택/진행률/권한/위치/전체화면/팝업창 |
| `app/.../AndroidManifest.xml` | 권한(INTERNET·CAMERA·RECORD_AUDIO·위치·저장소) + networkSecurityConfig + configChanges |
| `app/.../res/xml/network_security_config.xml` | **신규** — cleartext 차단(HTTPS only) |
| `app/.../res/layout/activity_main.xml` | TextView → WebView + ProgressBar + 전체화면 컨테이너 |
| `app/.../MainActivity.kt` | 전체 연결(바인딩·파일선택·권한·다운로드·뒤로가기·전체화면), OnBackPressedCallback 사용 |
| `app/.../assets/index.html` | **신규** — 로컬 assets 동작 확인용 샘플 |
| `local.properties` | sdk.dir 을 macOS 경로 → Windows 경로로 수정 |

### 기능 매핑
- 원격 HTTPS: `MainActivity.REMOTE_URL` 로드
- 로컬 assets: `https://appassets.androidplatform.net/assets/index.html` (주석의 `LOCAL_URL`)
- 파일 업로드 / 다운로드(구형기기 API≤28 저장소 권한 처리 포함) / 카메라·마이크 / 위치 / 동영상 전체화면 / 뒤로가기 / 쿠키(앱 일시정지 시 flush) — 모두 연결됨

---

## 10. 버전 현대화 + 빌드 검증 (B 적용 — 완료)

설치된 **JBR(JDK 21) + SDK 35** 로 추가 설치 없이 빌드되도록 버전을 상향했고, **실제 빌드로 검증 완료**.

### 버전 변경
| 항목 | 변경 전 → 후 |
|---|---|
| AGP | 7.1.3 → **8.6.0** |
| Gradle | 7.2 → **8.7** |
| Kotlin | 1.6.21 → **1.9.24** |
| compileSdk / targetSdk | 31 → **35** |
| Java | 1.8 → **17** |
| Manifest `package` | 제거 → `namespace` 로 이전 (app=`kr.smiling.smwebview`, webviewlib=`net.common.webviewlib`) |
| webviewlib publishing | AGP 8 호환(`android.publishing.singleVariant('release')`)으로 갱신 |
| 의존성 | core-ktx 1.13.1 / appcompat 1.7.0 / material 1.12.0 / webkit 1.11.0 / test 1.1.5·3.5.1 |

### 추가 조정
- `gradle.properties` 에 `org.gradle.java.home` = JBR 경로 고정
  → PATH 의 JDK 26 을 Kotlin 데몬이 파싱 못해 매번 fallback 컴파일되던 문제 해결(빌드 1m26s → 27s).
- `MainActivity` 의 `onBackPressed` 오버라이드 → `OnBackPressedCallback`(predictive back 대응, deprecated 경고 제거).

### 빌드 검증 결과
```
./gradlew :app:assembleDebug   (JAVA_HOME = Android Studio JBR)
→ BUILD SUCCESSFUL in 27s
→ app/build/outputs/apk/debug/app-debug.apk (약 5.9 MB) 생성
→ Kotlin daemon fallback / deprecated 경고 없음
```

### 빌드 방법(재현)
```bash
# Android Studio 로 열면 자동 처리됨. CLI 빌드 시:
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # 또는 JDK 17~21
./gradlew :app:assembleDebug
```

> 참고: SDK XML version 4 경고는 command-line tools 가 AGP 보다 최신이라 나오는 무해한 경고. 빌드에 영향 없음.

### 남은 선택 작업(이번 범위 밖)
- minSdk 21 유지 중 — 필요 시 23~24 상향 검토
- jitpack 배포 설정(미결정 6장) — 별도 진행 예정
- `release/webviewlib.jar`(타 프로젝트 산출물) 정리는 보류 상태(빌드 무관)

---

## 11. 공통 추가 기능 후보 (정리 — 미구현)

현재 webviewlib 구현(설정/라우팅/에러/파일/권한/진행률/전체화면/쿠키/뒤로가기) 위에,
실무 WebView 앱이 공통으로 더 얹는 기능을 우선순위별로 정리한다. **아직 구현하지 않음.**

### A. 현재 구현의 갭 — ✅ 보강 완료 (2026-05-26, 빌드 검증됨)
| 항목 | 보강 내용 | 구현 위치 |
|---|---|---|
| 카메라/마이크 권한 연동 | 웹 리소스(VIDEO/AUDIO_CAPTURE)를 Android 런타임 권한(CAMERA/RECORD_AUDIO)으로 매핑 → 보유 시 즉시 grant, 미보유 시 런타임 권한 요청 후 결과로 grant/deny | `MainActivity.handleWebPermission` + `permissionLauncher` |
| JS 다이얼로그 | `onJsAlert/onJsConfirm/onJsPrompt` 를 AppCompat `AlertDialog` 로 대체(출처 URL 노출 제거). prompt 는 EditText 입력 | `BaseWebChromeClient` (라이브러리 기본 제공) |
| 새 창(`onCreateWindow`) | 콜백 미등록 시: 임시 WebView 로 타겟 URL 만 추출해 메인 WebView 에 로드(팝업 미생성). 콜백 등록 시 앱에 위임 | `BaseWebChromeClient.onCreateWindow` |

### B. 공통 추가 — 대부분의 앱이 필요(우선순위 높음)
| 기능 | 설명 | 비고 |
|---|---|---|
| JS Bridge (`@JavascriptInterface`) | 웹↔네이티브 통신(토큰/공유/토스트/화면이동/디바이스 정보) | 수요 최다. **ProGuard keep 필수** |
| 당겨서 새로고침 | `SwipeRefreshLayout` + WebView | 공통 UX |
| 커스텀 에러/오프라인 페이지 | `onReceivedError` 시 로컬 에러 HTML + 재시도 | 네트워크 끊김 대응 |
| User-Agent 커스터마이징 | UA 에 `AppName/1.0` 추가 | 서버에서 앱/웹 분기 |
| 쿠키/세션 주입 | 네이티브 로그인 → WebView 쿠키/헤더 주입, 로그아웃 시 클리어 | 로그인 연동 필수 |
| 타이틀 동기화 | `onReceivedTitle` → 툴바 타이틀 | |
| WebView 디버깅 토글 | `setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` | Chrome inspect |

### C. 자주 필요 — 상황에 따라(우선순위 중간)
| 기능 | 설명 |
|---|---|
| 다크모드 대응 | `WebSettingsCompat.setAlgorithmicDarkeningAllowed` (androidx.webkit) |
| 외부 링크 정책 + Custom Tabs | 내 도메인은 WebView, 외부는 Chrome Custom Tabs(allowlist) |
| 다운로드 완료 알림/열기 | `DownloadManager` 완료 `BroadcastReceiver` → 파일 열기 |
| 캐시/데이터 관리 | 로그아웃·오류 시 캐시·쿠키·스토리지 클리어 API |
| 네트워크 상태 모니터링 | 오프라인→온라인 전환 시 자동 리로드 |
| 접근성(폰트 배율) | 시스템 폰트 크기를 `textZoom` 에 반영 |

### D. 도메인 특화 — 필요 시만(금융/기업 등)
| 기능 | 설명 |
|---|---|
| HTTP Basic 인증 | `onReceivedHttpAuthRequest` |
| 클라이언트 인증서 / SSL 핀닝 | `onReceivedClientCertRequest` |
| Safe Browsing | `setSafeBrowsingEnabled` — 악성 URL 차단 |
| 파일 선택 시 카메라 직접 실행 | `capture` 속성 처리(사진 촬영 업로드) |

---

## 12. 오프라인 퍼스트 + 서버 폴백 동작 설계 (예정 — 미구현)

> 목표: **사용자는 빈 화면을 절대 보지 않는다.** 로컬로 즉시 시작하고, 네트워크가 되면 서버로 자연스럽게 전환하며, 끊기면 로컬로 복귀한다.

### 12.1 핵심 원칙
1. 사용자는 빈 화면을 절대 보지 않음 (최초 진입·네트워크 끊김 모두 로컬 즉시)
2. 서버 교체 후에는 완전한 서버 모드 (페이지 이동·데이터 갱신 모두 서버 기반)
3. 네트워크 끊김 시 로컬로 자연스럽게 복귀, 복구되면 다시 서버로
4. 로컬/서버 레이아웃 동일 → 전환 깜빡임 최소화

### 12.2 상태 정의
| 상태 | 설명 | 데이터 |
|---|---|---|
| **A. 로컬 HTML(오프라인)** | 앱 최초 진입 시 항상 이 상태로 시작 | SQLite 값 |
| **B. 서버 HTML(온라인)** | 서버 로드 성공 후 진입. WebView 내 이동 모두 서버 기반 | 서버 |

### 12.3 전환 규칙
| 전환 | 조건 |
|---|---|
| A → B | 서버 HTML 로드 성공 |
| B → A | 네트워크 끊김 또는 서버 오류 |
| A → A | 네트워크 없거나 서버 실패 (변화 없음) |
| B → B | 정상 페이지 이동 (변화 없음) |

### 12.4 전체 흐름
```
앱 진입(최초)
 ├─ 1. 로컬 HTML + SQLite 값 즉시 표시 (0.1s, 상태 A)
 ├─ 2. 네트워크 확인
 │     ├─ 없음 → 로컬 유지 (A)
 │     └─ 있음 → [네이티브 프리체크] → 도달 가능하면 서버 loadUrl
 │               ├─ 성공 → 서버 HTML 교체 (A→B)
 │               └─ 실패 → 로컬 유지 (사용자 인지 못함)
 └─ 3. 서버 모드(B)
       ├─ 페이지 이동 → 서버 기반
       ├─ onReceivedError(mainFrame) 또는 NetworkCallback.onLost → 로컬 전환 (B→A)
       └─ NetworkCallback.onAvailable → 프리체크 → 서버 재로드 (A→B)
```

### 12.5 기술 설계 (결정 사항)

**① SQLite ↔ HTML = JS Bridge (확정)**
- WebView 의 JS 는 Android SQLite 에 직접 접근 불가 → 네이티브가 SQLite 를 읽어 `@JavascriptInterface` 로 주입.
- 0.1초 즉시 표시: HTML `onload` → `window.NativeBridge.getInitialData()` **동기 호출** → JSON 파싱 후 렌더.
- **ProGuard keep 필수**: `-keepclassmembers class <bridge클래스> { @android.webkit.JavascriptInterface <methods>; }`
- ⚠️ 보안: `addJavascriptInterface` 는 노출 범위가 WebView 전역이라 **신뢰 가능한 출처(로컬 HTML + 자사 서버)** 에서만 사용. 서버가 신뢰 불가하면 bridge 노출 정책을 분리해야 함.

**② 서버 전환 = 네이티브 프리체크 후 로드 (확정)**
- 백그라운드 스레드에서 서버 URL 도달성 확인(HEAD 또는 짧은 타임아웃 GET, 2~3s).
- 2xx 성공 → `webView.loadUrl(서버)`. 실패/타임아웃 → 로컬 유지.
- loadUrl 이후에도 `onReceivedError(isMainFrame)` 발생 시 → 로컬 복귀(B→A).
- 흰 플래시 방지: `webView.setBackgroundColor(테마 배경색)`.

**③ 네트워크 감지 = onReceivedError + NetworkCallback (병행 필수)**
- `onReceivedError(isMainFrame)` : 네비게이션 실패 감지(이미 라이브러리 콜백 제공).
- `ConnectivityManager.NetworkCallback` : SPA 내부 fetch 끊김 등 **능동 감지**(onLost→A, onAvailable→B).
- 상태 플래그로 중복 전환 방지.

**④ 깜빡임 최소화**
- `setBackgroundColor` + 로컬/서버 **레이아웃 구조 동일**(앱·서버 협의) + 필요 시 `onPageCommitVisible` 타이밍 활용.

### 12.6 컴포넌트 분담
| 영역 | 위치 | 비고 |
|---|---|---|
| 상태머신(A/B), 전환 오케스트레이션 | **app** | 앱 정책 |
| SQLite 접근 + JS Bridge 구현 | **app** | 앱별 스키마 |
| 서버 URL / 로컬 HTML 경로 | **app** | |
| `NetworkMonitor`(NetworkCallback 래퍼) | **webviewlib** | ✅ 구현 완료 |
| JS Bridge 등록/호출 헬퍼 + 보안·ProGuard 가이드 | **webviewlib** | ✅ 구현 완료(스키마는 앱) |
| 서버 reachability 체크 유틸 | **webviewlib** | ✅ 구현 완료 |
| `onReceivedError` 훅 / AssetLoader | **webviewlib** | 기존 제공 ✓ |

### 12.7 상태머신 의사코드
```
state = LOCAL_A
onCreate:
    loadLocal()                        // 즉시 A
    if (online) tryServer()
tryServer:                             // 백그라운드
    if (reachable(serverUrl)) { loadServer(); state = SERVER_B }
onReceivedError(mainFrame) when state == SERVER_B:
    loadLocal(); state = LOCAL_A
NetworkCallback.onAvailable when state == LOCAL_A:
    tryServer()
NetworkCallback.onLost when state == SERVER_B:
    loadLocal(); state = LOCAL_A
```

### 12.8 데이터 모델 (확정)

**앱이 데이터의 단일 작성자(single writer).** 앱 기능 수행 시 데이터를 생성하여 로컬 SQLite 와 서버에 이중 기록한다.
```
앱 기능 수행
  └─ 데이터 생성
       ├─ 로컬 SQLite 저장 (항상)
       └─ 서버 전송 (온라인 시)
표시:
  상태 A(로컬) → SQLite 값을 JS Bridge 로 HTML 에 주입
  상태 B(서버) → 앱이 전송한 데이터를 서버 HTML 이 표시
```
- A·B 는 본질적으로 동일 데이터 → "로컬/서버 레이아웃 동일" 원칙과 정합.
- 오프라인이어도 앱이 SQLite 에 쌓으므로 로컬 표시는 항상 가능.

**파생 쟁점 (앱 영역 비즈니스 로직):**
1. **미전송 큐 + 재전송** — 오프라인에서 생성한 데이터는 서버 전송 실패. SQLite 에 "미전송" 플래그로 남기고 `NetworkCallback.onAvailable`(네트워크 복구) 시 재전송. → WebView 상태머신의 `onAvailable` 훅과 공유 권장.
2. **A↔B 일시적 불일치** — 방금 만든 데이터가 서버 전송 전이면 서버 HTML(B)에 아직 없을 수 있음. 전송 완료 후 서버 HTML 재로드 시 일치.

### 12.9 남은 확인 사항
- 서버 HTML 이 정적/SPA 중 무엇인지 (프리체크 판정 정밀도에 영향).
- B→A 전환 시 `NetworkCallback.onLost` 를 즉시 적용할지(페이지 동작 중 끊김과의 충돌 처리).
- 미전송 데이터 재전송 정책(순서 보장/충돌 해결)은 앱 도메인에서 별도 정의.

### 12.10 webviewlib 공통 도구 — 구현 완료 & 추후 프로젝트 사용법

이 프로젝트에서는 **웹뷰/네트워크 차원의 공통 도구만** 구현했다. (상태머신·SQLite·동기화는 추후 앱 프로젝트 몫)
추후 프로젝트는 라이브러리를 **수정 없이 아래처럼 호출**하면 된다.

| 컴포넌트 | 파일 | 역할 |
|---|---|---|
| `NetworkMonitor` | `webviewlib/.../NetworkMonitor.kt` | 끊김/복구 감지(메인스레드 콜백), `isOnline()` |
| `Reachability` | `webviewlib/.../Reachability.kt` | 서버 도달성 프리체크(백그라운드→메인 콜백) |
| `registerBridge` / `callJsFunction` | `webviewlib/.../WebViewBridge.kt` | JS Bridge 등록 + 네이티브→JS 호출(스키마는 앱) |

```kotlin
// 1) 네트워크 감지 — A↔B 전환·재전송 트리거
val monitor = NetworkMonitor(this).apply {
    onAvailable = { /* 상태 A면 서버 재로드 + 미전송 재전송 */ }
    onLost      = { /* 상태 B면 로컬 전환 */ }
}
monitor.register()    // onResume
monitor.unregister()  // onPause

// 2) 서버 프리체크 후 로드 — 빈 화면 회피
Reachability.check(SERVER_URL) { ok ->
    if (ok) webView.loadUrl(SERVER_URL)   // 실패면 로컬 유지
}

// 3) JS Bridge — AppBridge(@JavascriptInterface)는 앱이 정의
webView.registerBridge("NativeBridge", AppBridge(this))
webView.callJsFunction("onDataUpdated", jsonString)   // 네이티브→JS
```
