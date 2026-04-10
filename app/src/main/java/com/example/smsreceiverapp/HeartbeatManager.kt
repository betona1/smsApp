package com.example.smsreceiverapp

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
                delay(INTERVAL)
            }
        }
        Log.d(TAG, "Heartbeat 시작 (30초 간격)")
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
        val request = HeartbeatRequest(phone_number = phone, app_version = version)

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
