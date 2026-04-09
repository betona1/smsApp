package com.example.smsreceiverapp
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.smsreceiverapp.ReceivedSMSRequest
import com.example.smsreceiverapp.RetrofitClient
import com.example.smsreceiverapp.ui.theme.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SmsReceiver", "\uD83D\uDD25 onReceive triggered")

        val messages = extractMessages(intent)
        if (messages.isEmpty()) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"
        val sendAll = prefs.getBoolean("send_all_sms", false)

        Log.d("SmsReceiver", "\uD83D\uDCF2 전체문자 전송 설정: $sendAll")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val dao = db.csPhoneDao()
                val settings = dao.getAll()

                for (msg in messages) {
                    val sender = normalizePhoneNumber(msg.displayOriginatingAddress ?: "")
                    Log.d("SmsReceiver", "수신한 번호 sender = $sender")  // ✅ 이 위치!
                    val body = msg.messageBody ?: ""

                    val msgType = if (body.toByteArray(Charsets.UTF_8).size > 80) "LMS" else "SMS"
                    Log.d("SmsReceiver", "$msgType sender: $sender, body: ${body.take(50)}")

                    if (msgType == "LMS") {
                        AppLog.lms("BR수신 [$sender] ${body.take(50)}")
                    } else {
                        AppLog.sms("BR수신 [$sender] ${body.take(50)}")
                    }

                    val shouldSave = settings.any { setting ->
                        setting.checkphone_number == sender &&
                                (setting.is_save_to_db || setting.is_admin)
                    }

                    if (sendAll || shouldSave) {
                        sendSMSRequest(context, myPhone, sender, body)
                        AppLog.server("$msgType 서버 전송 [$sender]")
                    } else {
                        Log.d("SmsReceiver", "❌ DB 저장 조건 불충족 → 저장 안함")
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "❌ DB 확인 오류: ${e.localizedMessage}")
            }
        }
    }

    private fun sendSMSRequest(context: Context, csPhone: String, sender: String, body: String) {
        val request = ReceivedSMSRequest(
            csphone_number = csPhone,
            checkphone_number = sender,
            message = body,
            receive_time = System.currentTimeMillis()
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = RetrofitClient.getApi(context).sendReceivedSMS(request)
                Log.d("SmsReceiver", "✅ 문자 DB 전송됨: ${response.code()}")
            } catch (e: Exception) {
                Log.e("SmsReceiver", "❌ 문자 전송 실패: ${e.localizedMessage}")
            }
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        return number
            .replace("+82", "0")
            .replace("-", "")
            .replace(" ", "")
            .trim()
    }

    private fun extractMessages(intent: Intent): List<SmsMessage> {
        val bundle = intent.extras ?: return emptyList()
        val pdus = bundle["pdus"] as? Array<*> ?: return emptyList()
        val format = bundle.getString("format")

        return pdus.mapNotNull { pdu ->
            SmsMessage.createFromPdu(pdu as? ByteArray, format)
        }
    }

    private fun showNotification(context: Context, title: String, message: String) {
        val channelId = "sms_channel"
        val channelName = "SMS 알림 채널"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "문자 수신 시 알림"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        with(NotificationManagerCompat.from(context)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }
}
