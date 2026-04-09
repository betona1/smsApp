package com.example.smsreceiverapp

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class SmsContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "SmsContentObserver"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSmsId: Long = getMaxSmsId() // 전체 SMS 중 최대 ID (발신 포함)
    private var lastMmsId: Long = getLastMmsId()

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        Log.d(TAG, "ContentObserver onChange (no uri)")
        scope.launch { checkNewSms() }
        scope.launch {
            delay(5000)
            checkNewMms()
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "ContentObserver onChange: $uri")
        // URI 구분 없이 둘 다 체크
        scope.launch { checkNewSms() }
        scope.launch {
            delay(5000)
            checkNewMms()
        }
    }

    private suspend fun checkNewSms() {
        try {
            // 먼저 전체 SMS 최대 ID 업데이트 (발신 문자 ID 건너뛰기 위해)
            val maxId = getMaxSmsId()

            val resolver = context.contentResolver
            val cursor = resolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                "_id > ?",
                arrayOf(lastSmsId.toString()),
                "_id ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val sender = normalizePhoneNumber(it.getString(it.getColumnIndexOrThrow("address")) ?: "")
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))

                    // SMS/LMS 분류 (80바이트 = 한글 40자 기준)
                    val msgType = if (body.toByteArray(Charsets.UTF_8).size > 80) "LMS" else "SMS"
                    Log.d(TAG, "새 $msgType 감지: ID=$id, sender=$sender")

                    if (msgType == "LMS") {
                        AppLog.lms("수신 [$sender] ${body.take(50)}")
                    } else {
                        AppLog.sms("수신 [$sender] ${body.take(50)}")
                    }

                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"
                    val sendAll = prefs.getBoolean("send_all_sms", false)

                    if (sendAll || shouldSaveSms(sender)) {
                        sendSmsToServer(myPhone, sender, body, date)
                        AppLog.server("$msgType 서버 전송 [$sender]")
                    }

                    if (id > lastSmsId) lastSmsId = id
                }
            }
            // 발신 문자 ID도 반영하여 다음 체크 시 건너뛰기
            if (maxId > lastSmsId) lastSmsId = maxId
        } catch (e: Exception) {
            Log.e(TAG, "SMS 확인 오류: ${e.message}", e)
        }
    }

    private suspend fun checkNewMms() {
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "msg_box"),
                "_id > ? AND msg_box = 1",
                arrayOf(lastMmsId.toString()),
                "_id ASC"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val mmsId = id.toString()

                    Log.d(TAG, "새 MMS 감지: ID=$mmsId")

                    val sender = getMmsSender(resolver, mmsId)
                    val textParts = mutableListOf<String>()
                    val imageParts = mutableListOf<Pair<ByteArray, String>>()

                    extractMmsParts(resolver, mmsId, textParts, imageParts)

                    val messageText = textParts.joinToString("\n").ifEmpty { "(이미지)" }
                    Log.d(TAG, "MMS 발신: $sender, 텍스트: ${messageText.take(50)}, 이미지: ${imageParts.size}장")
                    AppLog.mms("수신 [$sender] 이미지 ${imageParts.size}장 ${messageText.take(30)}")

                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"

                    if (imageParts.isNotEmpty()) {
                        sendMmsToServer(myPhone, sender, messageText, imageParts)
                        AppLog.server("MMS 서버 전송 [$sender] 이미지 ${imageParts.size}장")
                    } else {
                        sendSmsToServer(myPhone, sender, messageText, System.currentTimeMillis())
                        AppLog.server("SMS 서버 전송 [$sender]")
                    }

                    lastMmsId = id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 확인 오류: ${e.message}", e)
        }
    }

    private suspend fun shouldSaveSms(sender: String): Boolean {
        return try {
            val db = com.example.smsreceiverapp.ui.theme.db.AppDatabase.getInstance(context)
            val settings = db.csPhoneDao().getAll()
            settings.any { it.checkphone_number == sender && (it.is_save_to_db || it.is_admin) }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendSmsToServer(myPhone: String, sender: String, body: String, date: Long) {
        try {
            val request = ReceivedSMSRequest(
                csphone_number = myPhone,
                checkphone_number = sender,
                message = body,
                receive_time = date
            )
            val response = RetrofitClient.getApi(context).sendReceivedSMS(request)
            Log.d(TAG, "SMS 서버 전송: ${response.code()}")
            if (!response.isSuccessful) {
                AppLog.error("SMS 서버 전송 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS 서버 전송 실패: ${e.message}")
            AppLog.error("SMS 서버 전송 오류: ${e.message}")
        }
    }

    private suspend fun sendMmsToServer(
        myPhone: String,
        sender: String,
        message: String,
        images: List<Pair<ByteArray, String>>
    ) {
        try {
            val api = RetrofitClient.getApi(context)
            val csPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderBody = sender.toRequestBody("text/plain".toMediaTypeOrNull())
            val messageBody = message.toRequestBody("text/plain".toMediaTypeOrNull())
            val timeBody = System.currentTimeMillis().toString()
                .toRequestBody("text/plain".toMediaTypeOrNull())

            val imageBodies = images.mapIndexed { index, (data, mimeType) ->
                val ext = when {
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    else -> "jpg"
                }
                val requestBody = data.toRequestBody(mimeType.toMediaTypeOrNull())
                MultipartBody.Part.createFormData("images", "mms_image_${index}.$ext", requestBody)
            }

            val response = api.sendMmsToServer(csPhoneBody, senderBody, messageBody, timeBody, imageBodies)
            Log.d(TAG, "MMS 서버 전송: ${response.code()}")
            if (!response.isSuccessful) {
                AppLog.error("MMS 서버 전송 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 서버 전송 실패: ${e.message}")
            AppLog.error("MMS 서버 전송 오류: ${e.message}")
        }
    }

    private fun getMmsSender(resolver: ContentResolver, mmsId: String): String {
        val cursor = resolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type=137",
            null, null
        )
        var sender = "unknown"
        cursor?.use {
            if (it.moveToFirst()) {
                sender = it.getString(it.getColumnIndexOrThrow("address")) ?: "unknown"
            }
        }
        return normalizePhoneNumber(sender)
    }

    private fun extractMmsParts(
        resolver: ContentResolver,
        mmsId: String,
        textParts: MutableList<String>,
        imageParts: MutableList<Pair<ByteArray, String>>
    ) {
        val cursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text", "_data"),
            "mid=$mmsId",
            null, null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val partId = it.getString(it.getColumnIndexOrThrow("_id"))
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: ""
                when {
                    contentType == "text/plain" -> {
                        val text = it.getString(it.getColumnIndexOrThrow("text"))
                        if (!text.isNullOrBlank()) textParts.add(text)
                    }
                    contentType.startsWith("image/") -> {
                        val data = readPartData(resolver, partId)
                        if (data != null) imageParts.add(Pair(data, contentType))
                    }
                }
            }
        }
    }

    private fun readPartData(resolver: ContentResolver, partId: String): ByteArray? {
        return try {
            resolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use { stream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(4096)
                var bytesRead: Int
                while (stream.read(data).also { bytesRead = it } != -1) {
                    buffer.write(data, 0, bytesRead)
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 읽기 실패: $partId, ${e.message}")
            null
        }
    }

    // 전체 SMS (발신+수신) 중 최대 ID를 가져와야 발신 후 ID 밀림 방지
    private fun getMaxSmsId(): Long {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id"),
                null, null, "_id DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun getLastMmsId(): Long {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id"),
                "msg_box = 1",
                null, "_id DESC LIMIT 1"
            )
            cursor?.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            } ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun normalizePhoneNumber(number: String): String {
        return number.replace("+82", "0").replace("-", "").replace(" ", "").trim()
    }

    fun destroy() {
        scope.cancel()
    }
}
