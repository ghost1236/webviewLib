package kr.smiling.smwebview

import net.common.webviewlib.BaseWebViewActivity

/**
 * BaseWebViewActivity 상속만으로 WebView 화면을 구성하는 예시.
 * 파일/권한/다운로드/전체화면/뒤로가기/쿠키 등은 베이스가 모두 처리한다.
 *
 * 로컬 assets 를 쓰려면:
 *   override fun useAssetLoader() = true
 *   override fun getUrl() = "https://appassets.androidplatform.net/assets/index.html"
 */
class MainActivity : BaseWebViewActivity() {
    override fun getUrl() = "https://m.naver.com"
}
