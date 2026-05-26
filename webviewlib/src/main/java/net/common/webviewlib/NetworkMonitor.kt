package net.common.webviewlib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 네트워크 연결 끊김/복구를 감지하는 공통 모니터.
 *
 * 앱은 [onAvailable]/[onLost] 람다만 등록하고 [register]/[unregister] 만 호출하면 된다.
 * (오프라인↔온라인 전환, 서버 재로드/미전송 데이터 재전송 트리거 등에 공통 사용)
 *
 * 콜백은 항상 메인 스레드로 전달되므로, 콜백 안에서 바로 UI/WebView 를 다뤄도 된다.
 * 라이브러리 수정 없이 그대로 호출하는 것을 전제로 설계되었다.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 네트워크 사용 가능 시 호출(메인 스레드) */
    var onAvailable: (() -> Unit)? = null

    /** 네트워크 끊김 시 호출(메인 스레드) */
    var onLost: (() -> Unit)? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 현재 인터넷 사용 가능 여부를 즉시 조회 */
    fun isOnline(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isConnected == true
    }

    /** 네트워크 콜백 등록(중복 등록 방지). 보통 onResume 또는 onCreate 에서 호출 */
    fun register() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { onAvailable?.invoke() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { onLost?.invoke() }
            }
        }
        networkCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+ : 기본 네트워크만 추적
            connectivityManager.registerDefaultNetworkCallback(callback)
        } else {
            // API 21~23 : NetworkRequest 로 인터넷 가능 네트워크 추적
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
        }
    }

    /** 네트워크 콜백 해제. 보통 onPause 또는 onDestroy 에서 호출 */
    fun unregister() {
        networkCallback?.let { cb ->
            // 등록 안 된 콜백 해제 시 예외가 날 수 있어 방어
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }
}
