package net.common.webviewlib

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 도달성(reachability)을 가볍게 확인하는 유틸.
 *
 * 서버 HTML 로 교체하기 전, 빈 화면을 피하기 위해 "먼저 도달 가능한지" 확인하는 용도.
 * 백그라운드 스레드에서 확인하고 결과는 메인 스레드 콜백으로 전달한다.
 *
 * 라이브러리 수정 없이 그대로 호출하는 것을 전제로 설계되었다.
 */
object Reachability {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 서버 URL 도달 가능 여부를 확인한다.
     *
     * @param url 확인할 서버 URL
     * @param timeoutMs 연결/응답 타임아웃(기본 3초)
     * @param onResult 메인 스레드에서 호출되는 결과 콜백. HTTP 응답 2xx~3xx 면 true
     */
    fun check(url: String, timeoutMs: Int = 3000, onResult: (Boolean) -> Unit) {
        Thread {
            val reachable = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"            // 본문 없이 헤더만 — 가볍게 확인
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                }
                try {
                    connection.connect()
                    connection.responseCode in 200..399
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            mainHandler.post { onResult(reachable) }
        }.start()
    }
}
