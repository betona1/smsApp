package com.example.smsreceiverapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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

            val smsList = response.body()?.items.orEmpty()
            Log.d(TAG, "발송 대기 문자 ${smsList.size}건")

            if (smsList.isEmpty()) {
                return@withContext Result.success()
            }

            val smsManager = applicationContext.getSystemService(SmsManager::class.java)

            for (sms in smsList) {
                try {
                    Log.d(TAG, "문자 발송 중: ${sms.phone_number} - ${sms.message}")

                    // 긴 메시지 분할 발송
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

                    // 발송 성공 보고
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    val result = SmsSendResult(id = sms.id, status = "sent", sent_at = now)
                    api.reportSmsResult(sms.id, result)
                    Log.d(TAG, "발송 성공: ID=${sms.id}")

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
