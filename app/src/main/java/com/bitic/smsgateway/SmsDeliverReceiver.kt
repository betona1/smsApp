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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 기본 문자앱 필수 리시버 — 문자를 시스템에서 직접 수신(SMS_DELIVER).
 * NotificationListener 방식과 달리 알림을 거치지 않아 OTP('메시지 보기'로
 * 가려진 문자)도 원문 그대로 받는다. 기본 문자앱일 때만 이 브로드캐스트가 온다.
 *
 * ★ ANR 방지: BroadcastReceiver는 10초 넘기면 강제종료("중단됨")된다.
 * onReceive에서는 절대 오래 끌지 않고, 전역 스코프에 서버전송을 던지고
 * goAsync는 짧은 상한(8초) 내에 반드시 finish 한다.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsDeliverReceiver"
        // 리시버 인스턴스마다 새로 만들지 않고 앱 전역 스코프 재사용 (누수 방지)
        private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val msgs = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "메시지 파싱 실패: ${e.message}"); return
        } ?: return
        if (msgs.isEmpty()) return

        val sender = msgs[0].displayOriginatingAddress ?: msgs[0].originatingAddress ?: "unknown"
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }

        val myPhone = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("my_phone_number", "unknown") ?: "unknown"

        Log.d(TAG, "SMS_DELIVER 수신: $sender (${body.take(30)})")

        if (ProcessedMessages.isDuplicate(sender, body)) {
            Log.d(TAG, "중복 문자 → 스킵")
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        appScope.launch {
            try {
                // goAsync는 8초 내 반드시 종료 (ANR 마지노선 10초보다 짧게).
                // 첫 시도만 여기서 대기, 실패 시 백그라운드로 재시도 후 finish와 무관하게 진행.
                withTimeoutOrNull(8000L) { sendOnce(appContext, myPhone, sender, body) }
            } catch (e: Exception) {
                Log.e(TAG, "수신 처리 오류: ${e.message}")
            } finally {
                try { pending.finish() } catch (_: Exception) {}
            }
        }
    }

    /** 서버로 1~3회 전송 (짧은 백오프). 실패해도 예외를 던지지 않음. */
    private suspend fun sendOnce(context: Context, myPhone: String, sender: String, message: String) {
        val request = ReceivedSMSRequest(
            csphone_number = sender,
            checkphone_number = myPhone,
            message = message,
            receive_time = System.currentTimeMillis()
        )
        val backoffs = longArrayOf(0L, 2000L, 3000L)
        for ((attempt, wait) in backoffs.withIndex()) {
            if (wait > 0) delay(wait)
            try {
                val response = RetrofitClient.getApi(context).sendReceivedSMS(request)
                if (response.isSuccessful) {
                    Log.d(TAG, "서버 전송 성공 (${attempt + 1}/3)")
                    try { AppLog.info("수신 [$sender] ${message.take(30)}") } catch (_: Exception) {}
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
