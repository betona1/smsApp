package com.bitic.smsgateway

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

object HeartbeatManager {
    private const val TAG = "HeartbeatManager"
    private const val INTERVAL = 30000L // 30초
    private const val FAILOVER_AFTER = 2  // 연속 2회(=1분) 실패하면 주소 재판정

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var consecutiveFailures = 0

    fun start(context: Context) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    sendHeartbeat(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat 오류: ${e.message}")
                }
                // 워치독: 발송 서비스가 삼성 Doze로 죽었으면 되살림.
                // NotificationListener(시스템이 유지)가 이 루프를 살려두므로,
                // 여기서 매 30초 재시작을 보장하면 발송서비스도 안 죽는다.
                // (이미 살아있으면 startForegroundService는 무해 — onStartCommand 재호출뿐)
                try {
                    SmsSenderService.start(context.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "발송서비스 워치독 재시작 실패: ${e.message}")
                }
                delay(INTERVAL)
            }
        }
        Log.d(TAG, "Heartbeat 시작 (30초 간격, 발송서비스 워치독 포함)")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sendHeartbeat(context: Context) {
        val phone = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("my_phone_number", null) ?: return

        if (phone.isBlank()) return

        val version = getAppVersion(context)
        val request = HeartbeatRequest(
            phone_number = phone,
            app_version = version,
            local_ip = getLocalIp(),
            active_url = RetrofitClient.activeBaseUrl ?: Prefs.getBaseUrl(context),
            ext_configured = Prefs.getExtBaseUrl(context).isNotBlank()
        )

        try {
            val response = RetrofitClient.getApi(context).sendHeartbeat(request)
            if (response.isSuccessful) {
                consecutiveFailures = 0
                Log.d(TAG, "Heartbeat OK: ${response.body()?.connected}")
                applyExtUrlFromServer(context, response.body()?.ext_url)
                return
            }
            Log.e(TAG, "Heartbeat 실패: ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat 전송 오류: ${e.message}")
        }

        // ★ 여기까지 왔으면 서버에 못 닿은 것 → 내부/외부 주소를 다시 판정한다.
        //
        // checkAndSwitch 가 MainActivity 에서만 불리던 시절엔, 앱을 켤 때 정한 주소를
        // 그 뒤로 영영 다시 보지 않았다. 그래서 폰이 사내망을 벗어나면(예: 퇴근길)
        // 죽은 내부주소만 계속 두드리다 중계가 통째로 멈췄다 — 2026-07-26 밤 CEO폰이
        // 10시간 공백. 화면을 열어야만 복구되는 구조였다.
        //
        // heartbeat 루프는 NotificationListener 가 살려두므로 백그라운드에서도 돈다.
        // 여기서 재판정하면 폰이 어디에 있든 알아서 외부주소로 넘어간다.
        consecutiveFailures++
        if (consecutiveFailures >= FAILOVER_AFTER) {
            Log.w(TAG, "연속 실패 ${consecutiveFailures}회 → 내부/외부 주소 재판정")
            try {
                val ok = RetrofitClient.checkAndSwitch(context)
                Log.w(TAG, if (ok) "재판정 성공: ${RetrofitClient.activeBaseUrl}"
                           else "재판정 실패 — 내부·외부 둘 다 불통")
                if (ok) consecutiveFailures = 0
            } catch (e: Exception) {
                Log.e(TAG, "재판정 오류: ${e.message}")
            }
        }
    }

    /**
     * 서버가 내려준 외부 접속 주소를 저장한다.
     *
     * 폰마다 설정화면에서 손으로 넣지 않으려는 것 — 2026-07-20 제품화에서 외부주소
     * 기본값을 공란화한 뒤 어느 폰에도 안 들어가 있었고, 그래서 CEO폰이 사내망을 벗어나자
     * 넘어갈 곳이 없어 10시간 중계가 멈췄다(2026-07-26).
     *
     * 사용자가 설정화면에서 직접 넣은 값이 있으면 건드리지 않는다 — 서버 값은 '비어있을 때
     * 채워주는' 용도다. 공란이 내려오면 무시한다.
     */
    private fun applyExtUrlFromServer(context: Context, extUrl: String?) {
        val url = extUrl?.trim().orEmpty()
        if (url.isBlank()) return
        if (Prefs.getExtBaseUrl(context).isNotBlank()) return  // 이미 설정됨 → 존중
        try {
            val stripped = url.removePrefix("http://").removePrefix("https://").trimEnd('/')
            val host = stripped.substringBefore(':')
            val port = stripped.substringAfter(':', "8379")
            if (host.isBlank()) return
            Prefs.setExtServer(context, host, port)
            Log.w(TAG, "서버가 알려준 외부주소 저장: $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "외부주소 저장 실패: ${e.message}")
        }
    }

    /**
     * 사내망(WiFi) IPv4 주소. 서버가 무선 ADB 로 이 폰을 되살릴 때 쓴다.
     * WifiManager 대신 NetworkInterface 를 쓰는 이유 — 권한이 필요 없고 API 변화에 안 흔들린다.
     */
    private fun getLocalIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}
