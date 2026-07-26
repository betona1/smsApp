package com.bitic.smsgateway

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class SmsSenderService : Service() {

    companion object {
        private const val TAG = "SmsSenderService"
        private const val CHANNEL_ID = "sms_sender_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL = 5000L // 5초

        /**
         * ★ Android 12+ 크래시 방지: 백그라운드에서 startForegroundService를 호출하면
         * ForegroundServiceStartNotAllowedException으로 앱 전체가 죽는다(밤사이 화면꺼짐 +
         * keepalive/워치독/알림리스너가 호출 → 크래시 루프 → 앱 사망). 반드시 감싼다.
         * 시작 실패해도 서비스는 START_STICKY라 시스템이 나중에 복구하고,
         * 다음 keepalive/heartbeat 때 재시도된다.
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, SmsSenderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // 백그라운드 FGS 시작 제한 등 — 크래시시키지 않고 조용히 무시
                Log.w(TAG, "서비스 시작 보류(백그라운드 제한 가능): ${e.message}")
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
        // Android 14+ 는 startForeground 시 서비스 타입을 명시해야 한다.
        // specialUse = Android 15 의 dataSync 6시간 제한을 받지 않는 상주 게이트웨이 용도.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("문자 수신/발송 대기 중..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("문자 수신/발송 대기 중..."))
        }
        acquireLocks()
        // ContentObserver 백업 활성화 (NotificationListener가 못 잡는 RCS/그룹알림 보완)
        registerContentObserver()
        // 절전 중에도 되살아나도록 keepalive 알람 예약
        KeepAliveReceiver.schedule(applicationContext)
        Log.d(TAG, "서비스 생성됨 (WakeLock 활성)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        return START_STICKY
    }

    /**
     * ★ 시스템이 서비스 실행시간 상한을 통보할 때 호출된다(Android 14 shortService / 15 dataSync 등).
     *
     * specialUse 로 바꿨으므로 정상적으로는 호출되지 않아야 하지만, 제조사가 자체 상한을
     * 걸었을 때 몇 초 안에 stopSelf() 하지 않으면
     * ForegroundServiceDidNotStopInTimeException 으로 앱이 강제종료된다(v2.1.4 까지의 사망 원인).
     * → 즉시 정리하고, keepalive 알람으로 재기동을 예약해 게이트웨이를 살려둔다.
     */
    private fun handleTimeout(reason: String) {
        Log.w(TAG, "시스템 실행시간 상한 통보($reason) → 정리 후 재기동 예약")
        try {
            KeepAliveReceiver.schedule(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "재기동 예약 실패: ${e.message}")
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        handleTimeout("startId=$startId")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout("startId=$startId type=$fgsType")
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
            "SMSGate::SmsSenderWakeLock"
        ).apply { acquire() }

        // WiFi 연결 유지
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "SMSGate::SmsSenderWifiLock"
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
        // ★ 내 번호를 실어야 서버가 발신폰 지정을 지킨다(안 실으면 아무 폰이나 픽업).
        val myPhone = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("my_phone_number", null)?.takeIf { it.isNotBlank() }
        val response = api.getOutgoingSms(myPhone, BuildConfig.VERSION_NAME)

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

                // sentIntent로 실제 전송결과 확인 후 보고 (fire-and-forget 금지)
                val (ok, reason) = sendSmsAndAwait(
                    smsManager, sms.phone_number, sms.message, sms.id
                )
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                if (ok) {
                    val result = SmsSendResult(id = sms.id, status = "sent", sent_at = now)
                    api.reportSmsResult(sms.id, result)
                    Log.d(TAG, "발송 성공: ID=${sms.id} ${reason ?: ""}")
                    AppLog.send("발송 완료 [${sms.phone_number}] ${sms.message.take(30)}")
                } else {
                    val result = SmsSendResult(
                        id = sms.id, status = "failed",
                        error_message = reason, sent_at = now
                    )
                    api.reportSmsResult(sms.id, result)
                    Log.e(TAG, "발송 실패(실측): ID=${sms.id} - $reason")
                    AppLog.error("발송 실패 [${sms.phone_number}] $reason")
                }

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

    /**
     * 실제 전송결과(sentIntent)를 기다렸다가 성공/실패를 판정한다.
     * @return Pair(성공여부, 실패사유 or null)
     * 멀티파트는 모든 파트가 RESULT_OK여야 성공. 60초 내 결과 미수신 시
     * 낙관적 성공 처리(예외 없이 발송은 된 상태 — 중복발송 방지).
     */
    private suspend fun sendSmsAndAwait(
        smsManager: SmsManager,
        phone: String,
        message: String,
        msgId: Int
    ): Pair<Boolean, String?> {
        val parts = smsManager.divideMessage(message)
        val total = if (parts.size > 1) parts.size else 1
        val action = "com.bitic.smsgateway.SMS_SENT_${msgId}_${System.currentTimeMillis()}"
        val received = AtomicInteger(0)

        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Pair<Boolean, String?>> { cont ->
                var anyFail = false
                var firstError: String? = null
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val code = resultCode
                        if (code != Activity.RESULT_OK) {
                            anyFail = true
                            if (firstError == null) firstError = smsErrorReason(code)
                        }
                        if (received.incrementAndGet() >= total) {
                            try { unregisterReceiver(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(Pair(!anyFail, firstError))
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    this@SmsSenderService, receiver, IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation {
                    try { unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                try {
                    if (parts.size > 1) {
                        val sentIntents = ArrayList<PendingIntent>(parts.size)
                        for (i in parts.indices) {
                            sentIntents.add(
                                PendingIntent.getBroadcast(
                                    this@SmsSenderService, i,
                                    Intent(action).setPackage(packageName), flags
                                )
                            )
                        }
                        smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                    } else {
                        val pi = PendingIntent.getBroadcast(
                            this@SmsSenderService, 0,
                            Intent(action).setPackage(packageName), flags
                        )
                        smsManager.sendTextMessage(phone, null, message, pi, null)
                    }
                } catch (e: Exception) {
                    try { unregisterReceiver(receiver) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(Pair(false, e.message ?: "발송 예외"))
                }
            }
        }
        // 타임아웃(null): 예외 없이 발송은 됐으나 결과 미확인 → 중복발송 방지 위해 성공 처리
        return result ?: Pair(true, null)
    }

    private fun smsErrorReason(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "일반 발송실패(통신사 차단 가능)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "무선(라디오) 꺼짐"
        SmsManager.RESULT_ERROR_NULL_PDU -> "PDU 없음"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "서비스 불가(신호 없음)"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "발송한도 초과(통신사 스팸제한)"
        else -> "발송실패(코드 $code)"
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
