package com.example.smsreceiverapp

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class SmsSenderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SmsSenderWorker"
        const val WORK_NAME = "sms_sender_work"

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsSenderWorker>(
                15, TimeUnit.MINUTES  // WorkManager 최소 주기 15분
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "SMS 발송 워커 시작됨 (15분 주기)")
        }

        fun startOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsSenderWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "SMS 발송 워커 즉시 실행")
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "SMS 발송 워커 중지됨")
        }
    }

    /**
     * Android 12+ 대응 SmsManager 안전 획득.
     */
    private fun getSmsManagerSafe(): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    applicationContext.getSystemService(SmsManager::class.java)
                        ?.createForSubscriptionId(subId)
                } else {
                    applicationContext.getSystemService(SmsManager::class.java)
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

    /**
     * 실제 전송결과(sentIntent)를 기다렸다가 성공/실패를 판정한다.
     * 멀티파트는 모든 파트가 RESULT_OK여야 성공. 60초 내 결과 미수신 시
     * 낙관적 성공 처리(중복발송 방지).
     */
    private suspend fun sendSmsAndAwait(
        smsManager: SmsManager,
        phone: String,
        message: String,
        msgId: Int
    ): Pair<Boolean, String?> {
        val parts = smsManager.divideMessage(message)
        val total = if (parts.size > 1) parts.size else 1
        val action = "com.example.smsreceiverapp.SMS_SENT_W_${msgId}_${System.currentTimeMillis()}"
        val received = AtomicInteger(0)
        val ctx = applicationContext

        val result = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Pair<Boolean, String?>> { cont ->
                var anyFail = false
                var firstError: String? = null
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        val code = resultCode
                        if (code != Activity.RESULT_OK) {
                            anyFail = true
                            if (firstError == null) firstError = smsErrorReason(code)
                        }
                        if (received.incrementAndGet() >= total) {
                            try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(Pair(!anyFail, firstError))
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    ctx, receiver, IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation {
                    try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                try {
                    if (parts.size > 1) {
                        val sentIntents = ArrayList<PendingIntent>(parts.size)
                        for (i in parts.indices) {
                            sentIntents.add(
                                PendingIntent.getBroadcast(
                                    ctx, i, Intent(action).setPackage(ctx.packageName), flags
                                )
                            )
                        }
                        smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, null)
                    } else {
                        val pi = PendingIntent.getBroadcast(
                            ctx, 0, Intent(action).setPackage(ctx.packageName), flags
                        )
                        smsManager.sendTextMessage(phone, null, message, pi, null)
                    }
                } catch (e: Exception) {
                    try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(Pair(false, e.message ?: "발송 예외"))
                }
            }
        }
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "SMS 발송 폴링 시작")

            // SEND_SMS 권한 확인
            if (ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "SEND_SMS 권한 없음")
                return@withContext Result.failure()
            }

            // 서버에서 발송 대기 목록 가져오기
            val api = RetrofitClient.getApi(applicationContext)
            val response = api.getOutgoingSms()

            if (!response.isSuccessful) {
                Log.e(TAG, "서버 응답 실패: ${response.code()}")
                return@withContext Result.retry()
            }

            val smsList = response.body().orEmpty()
            Log.d(TAG, "발송 대기 문자 ${smsList.size}건")

            if (smsList.isEmpty()) {
                return@withContext Result.success()
            }

            val smsManager = getSmsManagerSafe()
            if (smsManager == null) {
                Log.e(TAG, "SmsManager null — 전체 발송 실패 처리")
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
                return@withContext Result.success()
            }

            for (sms in smsList) {
                try {
                    Log.d(TAG, "문자 발송 중: ${sms.phone_number} - ${sms.message}")

                    // sentIntent로 실제 전송결과 확인 후 보고 (fire-and-forget 금지)
                    val (ok, reason) = sendSmsAndAwait(
                        smsManager, sms.phone_number, sms.message, sms.id
                    )
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    if (ok) {
                        val result = SmsSendResult(id = sms.id, status = "sent", sent_at = now)
                        api.reportSmsResult(sms.id, result)
                        Log.d(TAG, "발송 성공: ID=${sms.id} ${reason ?: ""}")
                    } else {
                        val result = SmsSendResult(
                            id = sms.id, status = "failed",
                            error_message = reason, sent_at = now
                        )
                        api.reportSmsResult(sms.id, result)
                        Log.e(TAG, "발송 실패(실측): ID=${sms.id} - $reason")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "발송 실패: ID=${sms.id} - ${e.message}")
                    // 발송 실패 보고
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    val result = SmsSendResult(
                        id = sms.id,
                        status = "failed",
                        error_message = e.message,
                        sent_at = now
                    )
                    try {
                        api.reportSmsResult(sms.id, result)
                    } catch (reportErr: Exception) {
                        Log.e(TAG, "결과 보고 실패: ${reportErr.message}")
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "워커 실행 오류: ${e.message}")
            Result.retry()
        }
    }
}
