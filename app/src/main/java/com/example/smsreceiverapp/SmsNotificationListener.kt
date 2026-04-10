package com.example.smsreceiverapp

import android.Manifest
import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SmsNotifListener"
        private val SMS_PACKAGES = setOf(
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.google.android.apps.messaging"
        )
        private var lastProcessedKey: String? = null
        private var lastProcessedTime: Long = 0
        private var lastMmsId: Long = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener 연결됨 - 폴링 + Heartbeat 시작")
        startPolling()
        HeartbeatManager.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener 연결 해제")
        pollingJob?.cancel()
        HeartbeatManager.stop()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollAndSend()
                } catch (e: Exception) {
                    Log.e(TAG, "폴링 오류: ${e.message}")
                }
                delay(5000) // 5초
            }
        }
    }

    private suspend fun pollAndSend() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val api = RetrofitClient.getApi(applicationContext)
        val response = api.getOutgoingSms()
        if (!response.isSuccessful) return

        val smsList = response.body().orEmpty()
        if (smsList.isEmpty()) return

        Log.d(TAG, "발송 대기 ${smsList.size}건")
        val smsManager = applicationContext.getSystemService(SmsManager::class.java)

        for (sms in smsList) {
            try {
                if (sms.message.isBlank()) {
                    val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                    api.reportSmsResult(sms.id, SmsSendResult(id = sms.id, status = "failed", error_message = "빈 메시지", sent_at = now))
                    continue
                }
                Log.d(TAG, "발송: ${sms.phone_number} - ${sms.message.take(30)}")

                val parts = smsManager.divideMessage(sms.message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(sms.phone_number, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(sms.phone_number, null, sms.message, null, null)
                }

                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                api.reportSmsResult(sms.id, SmsSendResult(id = sms.id, status = "sent", sent_at = now))
                Log.d(TAG, "발송 성공: ID=${sms.id}")
            } catch (e: Exception) {
                Log.e(TAG, "발송 실패: ID=${sms.id} - ${e.message}")
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                try {
                    api.reportSmsResult(sms.id, SmsSendResult(id = sms.id, status = "failed", error_message = e.message, sent_at = now))
                } catch (_: Exception) {}
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in SMS_PACKAGES) return

        val key = sbn.key
        val now = System.currentTimeMillis()
        if (key == lastProcessedKey && now - lastProcessedTime < 1000) return
        lastProcessedKey = key
        lastProcessedTime = now

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val message = bigText ?: text
        if (message.isBlank()) return

        // 시스템 요약 알림 무시
        if (title == "메시지" || title.isBlank() || message == "메시지 보기") return

        // MessagingStyle에서 실제 발신자/메시지 추출 시도
        var realSender = title
        var realMessage = message
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val lastMsg = msgs.last() as? Bundle
            if (lastMsg != null) {
                val senderObj = lastMsg.getCharSequence("sender")
                val msgText = lastMsg.getCharSequence("text")
                if (senderObj != null) realSender = senderObj.toString()
                if (msgText != null) realMessage = msgText.toString()
            }
        }

        // 알림에서 이미지 추출 시도
        val notifBitmap = extractNotificationImage(extras)
        val isMms = notifBitmap != null ||
                realMessage == "이미지" || realMessage == "사진" ||
                realMessage.contains("동영상")

        Log.d(TAG, "문자 알림: sender=$realSender, msg=${realMessage.take(50)}, isMms=$isMms, hasNotifImage=${notifBitmap != null}")

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"
        val sender = normalizePhoneNumber(realSender)

        if (isMms) {
            scope.launch {
                if (notifBitmap != null) {
                    Log.d(TAG, "알림에서 이미지 추출 성공")
                    val imageData = bitmapToJpeg(notifBitmap)
                    sendMmsMultipart(myPhone, sender, realMessage, listOf(Pair(imageData, "image/jpeg")))
                } else {
                    delay(5000)
                    sendMmsFromContentProvider(myPhone, sender, realMessage)
                }
            }
        } else {
            val msgBytes = realMessage.toByteArray(Charsets.UTF_8).size
            val msgType = if (msgBytes > 80) "LMS" else "SMS"

            scope.launch {
                sendSmsToServer(myPhone, sender, realMessage, msgType)
            }
        }
    }

    private suspend fun sendSmsToServer(myPhone: String, sender: String, message: String, msgType: String) {
        try {
            val request = ReceivedSMSRequest(
                csphone_number = myPhone,
                checkphone_number = sender,
                message = message,
                receive_time = System.currentTimeMillis()
            )
            val response = RetrofitClient.getApi(applicationContext).sendReceivedSMS(request)
            if (response.isSuccessful) {
                AppLog.server("$msgType 서버 전송 OK [$sender]")
                Log.d(TAG, "서버 전송 성공: ${response.code()}")
            } else {
                AppLog.error("$msgType 서버 전송 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            AppLog.error("서버 전송 오류: ${e.message}")
            Log.e(TAG, "서버 전송 오류: ${e.message}")
        }
    }

    private fun extractNotificationImage(extras: Bundle): Bitmap? {
        // EXTRA_PICTURE (BigPictureStyle)
        val pic = extras.get(Notification.EXTRA_PICTURE) as? Bitmap
        if (pic != null) return pic

        // EXTRA_LARGE_ICON
        val icon = extras.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
        if (icon != null && icon.width > 100) return icon // 작은 아이콘은 무시

        // MessagingStyle의 이미지
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        messages?.forEach { msg ->
            val bundle = msg as? Bundle
            val uri = bundle?.get("uri")
            if (uri != null) {
                Log.d(TAG, "MessagingStyle 이미지 URI: $uri")
                try {
                    val stream = contentResolver.openInputStream(Uri.parse(uri.toString()))
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bitmap != null) return bitmap
                } catch (e: Exception) {
                    Log.e(TAG, "MessagingStyle 이미지 읽기 실패: ${e.message}")
                }
            }
        }

        return null
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    private suspend fun sendMmsMultipart(
        myPhone: String, sender: String, message: String,
        images: List<Pair<ByteArray, String>>
    ) {
        try {
            val api = RetrofitClient.getApi(applicationContext)
            val csPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderBody = sender.toRequestBody("text/plain".toMediaTypeOrNull())
            val messageBody = message.toRequestBody("text/plain".toMediaTypeOrNull())
            val timeBody = System.currentTimeMillis().toString()
                .toRequestBody("text/plain".toMediaTypeOrNull())

            val imageBodies = images.mapIndexed { index, (data, mimeType) ->
                val requestBody = data.toRequestBody(mimeType.toMediaTypeOrNull())
                MultipartBody.Part.createFormData("images", "mms_image_${index}.jpg", requestBody)
            }

            val response = api.sendMmsToServer(csPhoneBody, senderBody, messageBody, timeBody, imageBodies)
            if (response.isSuccessful) {
                AppLog.server("MMS 서버 전송 OK [$sender] 이미지 ${images.size}장")
                Log.d(TAG, "MMS multipart 전송 성공: ${response.code()}")
            } else {
                AppLog.error("MMS 서버 전송 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            AppLog.error("MMS 전송 오류: ${e.message}")
            Log.e(TAG, "MMS 전송 오류: ${e.message}")
        }
    }

    private suspend fun sendMmsFromContentProvider(myPhone: String, sender: String, notifMessage: String) {
        try {
            val resolver = contentResolver

            // 최근 MMS에서 이미지 추출
            val cutoff = (System.currentTimeMillis() / 1000) - 120 // 최근 2분
            val cursor = resolver.query(
                Uri.parse("content://mms"),
                arrayOf("_id", "date", "msg_box"),
                "date > ? AND msg_box = 1",
                arrayOf(cutoff.toString()),
                "_id DESC"
            )

            var foundImages = false

            cursor?.use {
                while (it.moveToNext()) {
                    val mmsId = it.getString(it.getColumnIndexOrThrow("_id"))
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))

                    // 이미 처리한 MMS 건너뛰기
                    if (id <= lastMmsId) continue

                    val mmsSender = getMmsSender(resolver, mmsId)
                    val textParts = mutableListOf<String>()
                    val imageParts = mutableListOf<Pair<ByteArray, String>>()

                    extractMmsParts(resolver, mmsId, textParts, imageParts)

                    Log.d(TAG, "MMS ID=$mmsId, sender=$mmsSender, text=${textParts.size}, images=${imageParts.size}")

                    if (imageParts.isNotEmpty()) {
                        val messageText = textParts.joinToString("\n").ifEmpty { notifMessage }

                        // multipart 전송
                        val api = RetrofitClient.getApi(applicationContext)
                        val csPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
                        val senderBody = mmsSender.toRequestBody("text/plain".toMediaTypeOrNull())
                        val messageBody = messageText.toRequestBody("text/plain".toMediaTypeOrNull())
                        val timeBody = System.currentTimeMillis().toString()
                            .toRequestBody("text/plain".toMediaTypeOrNull())

                        val imageBodies = imageParts.mapIndexed { index, (data, mimeType) ->
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
                        if (response.isSuccessful) {
                            AppLog.server("MMS 서버 전송 OK [$mmsSender] 이미지 ${imageParts.size}장")
                            Log.d(TAG, "MMS 서버 전송 성공: ${response.code()}")
                        } else {
                            AppLog.error("MMS 서버 전송 실패: ${response.code()}")
                        }

                        foundImages = true
                        lastMmsId = id
                        break // 최신 1건만 처리
                    }

                    lastMmsId = id
                }
            }

            // 이미지를 못 찾았으면 텍스트만 SMS로 전송
            if (!foundImages) {
                Log.d(TAG, "MMS 이미지 추출 실패, 텍스트만 전송")
                AppLog.info("MMS 이미지 추출 못함, 텍스트만 전송")
                sendSmsToServer(myPhone, sender, notifMessage, "MMS")
            }

        } catch (e: Exception) {
            Log.e(TAG, "MMS 처리 오류: ${e.message}", e)
            AppLog.error("MMS 처리 오류: ${e.message}")
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
                        if (data != null) {
                            imageParts.add(Pair(data, contentType))
                            Log.d(TAG, "이미지 추출: partId=$partId, type=$contentType, size=${data.size}")
                        }
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
            Log.e(TAG, "이미지 읽기 실패: partId=$partId, ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun normalizePhoneNumber(number: String): String {
        return number.replace("+82", "0").replace("-", "").replace(" ", "").trim()
    }
}
