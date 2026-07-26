package com.bitic.smsgateway

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

object HeartbeatManager {
    private const val TAG = "HeartbeatManager"
    private const val INTERVAL = 30000L // 30초

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

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
            local_ip = getLocalIp()
        )

        try {
            val response = RetrofitClient.getApi(context).sendHeartbeat(request)
            if (response.isSuccessful) {
                Log.d(TAG, "Heartbeat OK: ${response.body()?.connected}")
            } else {
                Log.e(TAG, "Heartbeat 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat 전송 오류: ${e.message}")
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
