package com.bitic.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 기본 문자앱 필수 리시버 — 문자를 시스템에서 직접 수신(SMS_DELIVER).
 * NotificationListener 방식과 달리 알림을 거치지 않아 OTP('메시지 보기'로
 * 가려진 문자)도 원문 그대로 받는다. 기본 문자앱일 때만 이 브로드캐스트가 온다.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsDeliverReceiver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return

        // 멀티파트는 발신자별로 본문 합치기
        val sender = msgs[0].displayOriginatingAddress ?: msgs[0].originatingAddress ?: "unknown"
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }

        val myPhone = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("my_phone_number", "unknown") ?: "unknown"

        Log.d(TAG, "SMS_DELIVER 수신: $sender (${body.take(30)})")

        // 중복 방지 (NotificationListener 경로와 동일 dedup 사용)
        if (ProcessedMessages.isDuplicate(sender, body)) {
            Log.d(TAG, "중복 문자 → 스킵")
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                sendToServer(context, myPhone, sender, body)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun sendToServer(context: Context, myPhone: String, sender: String, message: String) {
        val request = ReceivedSMSRequest(
            csphone_number = sender,
            checkphone_number = myPhone,
            message = message,
            receive_time = System.currentTimeMillis()
        )
        val backoffs = longArrayOf(0L, 5000L, 10000L)
        for ((attempt, wait) in backoffs.withIndex()) {
            if (wait > 0) delay(wait)
            try {
                val response = RetrofitClient.getApi(context.applicationContext).sendReceivedSMS(request)
                if (response.isSuccessful) {
                    Log.d(TAG, "서버 전송 성공 (${attempt + 1}/3)")
                    AppLog.info("수신 [$sender] ${message.take(30)}")
                    return
                }
                Log.e(TAG, "서버 전송 실패 (${attempt + 1}/3): ${response.code()}")
            } catch (e: Exception) {
                Log.e(TAG, "서버 전송 오류 (${attempt + 1}/3): ${e.message}")
            }
        }
        Log.e(TAG, "SMS 서버전송 3회 실패 → drop")
    }
}
