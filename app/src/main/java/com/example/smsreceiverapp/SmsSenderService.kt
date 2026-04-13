package com.example.smsreceiverapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsSenderService : Service() {

    companion object {
        private const val TAG = "SmsSenderService"
        private const val CHANNEL_ID = "sms_sender_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL = 5000L // 5초

        fun start(context: Context) {
            val intent = Intent(context, SmsSenderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsSenderService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var smsObserver: SmsContentObserver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("문자 수신/발송 대기 중..."))
        acquireLocks()
        // ContentObserver 백업 활성화 (NotificationListener가 못 잡는 RCS/그룹알림 보완)
        registerContentObserver()
        Log.d(TAG, "서비스 생성됨 (WakeLock 활성)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        unregisterContentObserver()
        releaseLocks()
        Log.d(TAG, "서비스 종료됨")
        super.onDestroy()
    }

    private fun acquireLocks() {
        // CPU 깨운 상태 유지
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsReceiverApp::SmsSenderWakeLock"
        ).apply { acquire() }

        // WiFi 연결 유지
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "SmsReceiverApp::SmsSenderWifiLock"
        ).apply { acquire() }

        Log.d(TAG, "WakeLock + WifiLock 획득")
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        Log.d(TAG, "WakeLock + WifiLock 해제")
    }

    private fun registerContentObserver() {
        smsObserver = SmsContentObserver(this).also { observer ->
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://sms"), true, observer
            )
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://sms/inbox"), true, observer
            )
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms"), true, observer
            )
            contentResolver.registerContentObserver(
                android.net.Uri.parse("content://mms/inbox"), true, observer
            )
            Log.d(TAG, "SMS/MMS ContentObserver 등록 완료")
        }
    }

    private fun unregisterContentObserver() {
        smsObserver?.let {
            contentResolver.unregisterContentObserver(it)
            it.destroy()
        }
        smsObserver = null
        Log.d(TAG, "SMS/MMS ContentObserver 해제")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollAndSend()
                } catch (e: Exception) {
                    Log.e(TAG, "폴링 오류: ${e.message}")
                }
                delay(POLL_INTERVAL)
            }
        }
        Log.d(TAG, "5초 폴링 시작")
    }

    private suspend fun pollAndSend() {
        // SEND_SMS 권한 확인
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SEND_SMS 권한 없음")
            return
        }

        val api = RetrofitClient.getApi(this)
        val response = api.getOutgoingSms()

        if (!response.isSuccessful) {
            Log.e(TAG, "서버 응답 실패: ${response.code()}")
            return
        }

        val smsList = response.body().orEmpty()
        if (smsList.isEmpty()) return

        Log.d(TAG, "발송 대기 문자 ${smsList.size}건")
        AppLog.send("발송 대기 ${smsList.size}건 수신")
        updateNotification("발송 중... ${smsList.size}건")

        val smsManager = getSmsManagerSafe()
        if (smsManager == null) {
            Log.e(TAG, "SmsManager null — 전체 발송 실패 처리")
            AppLog.error("SmsManager 초기화 실패")
            for (sms in smsList) {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                try {
                    api.reportSmsResult(
                        sms.id,
                        SmsSendResult(
                            id = sms.id,
                            status = "failed",
                            error_message = "SmsManager 초기화 실패 (기본 SMS 앱 설정 및 SEND_SMS 권한 확인)",
                            sent_at = now
                        )
                    )
                } catch (_: Exception) {}
            }
            updateNotification("문자 수신/발송 대기 중...")
            return
        }

        for (sms in smsList) {
            try {
                // 빈 메시지 건너뛰기
                if (sms.message.isBlank()) {
                    Log.d(TAG, "빈 메시지 건너뛰기: ID=${sms.id}")
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    val result = SmsSendResult(id = sms.id, status = "failed", error_message = "빈 메시지", sent_at = now)
                    api.reportSmsResult(sms.id, result)
                    AppLog.error("빈 메시지 건너뜀: ID=${sms.id}")
                    continue
                }
                Log.d(TAG, "문자 발송: ${sms.phone_number} - ${sms.message}")

                val parts = smsManager.divideMessage(sms.message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(
                        sms.phone_number, null, parts, null, null
                    )
                } else {
                    smsManager.sendTextMessage(
                        sms.phone_number, null, sms.message, null, null
                    )
                }

                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val result = SmsSendResult(id = sms.id, status = "sent", sent_at = now)
                api.reportSmsResult(sms.id, result)
                Log.d(TAG, "발송 성공: ID=${sms.id}")
                AppLog.send("발송 완료 [${sms.phone_number}] ${sms.message.take(30)}")

            } catch (e: Exception) {
                Log.e(TAG, "발송 실패: ID=${sms.id} - ${e.message}")
                AppLog.error("발송 실패 [${sms.phone_number}] ${e.message}")
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                val result = SmsSendResult(
                    id = sms.id, status = "failed",
                    error_message = e.message, sent_at = now
                )
                try {
                    api.reportSmsResult(sms.id, result)
                } catch (reportErr: Exception) {
                    Log.e(TAG, "결과 보고 실패: ${reportErr.message}")
                }
            }
        }

        updateNotification("문자 수신/발송 대기 중...")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "문자 발송 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "문자 발송 서비스가 실행 중입니다"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS 발송 서비스")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Android 12+ 대응 SmsManager 안전 획득.
     */
    private fun getSmsManagerSafe(): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    getSystemService(SmsManager::class.java)
                        ?.createForSubscriptionId(subId)
                } else {
                    getSystemService(SmsManager::class.java)
                }
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsManager 획득 실패: ${e.message}")
            null
        }
    }
}
