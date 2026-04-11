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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

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

    override fun onListenerConnected() {
        super.onListenerConnected()
        // outbox 폴링은 SmsSenderService가 단독 담당 (중복 발송 방지)
        Log.d(TAG, "NotificationListener 연결됨 - Heartbeat 시작")
        HeartbeatManager.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener 연결 해제")
        HeartbeatManager.stop()
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

        // MessagingStyle에서 실제 메시지 본문 우선 추출 (발신자는 아래 폴백 체인에서 처리)
        var realMessage = message
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val lastMsg = msgs.last() as? Bundle
            val msgText = lastMsg?.getCharSequence("text")
            if (msgText != null) realMessage = msgText.toString()
        }

        // 알림에서 이미지 추출 시도
        val notifBitmap = extractNotificationImage(extras)
        val isMms = notifBitmap != null ||
                realMessage == "이미지" || realMessage == "사진" ||
                realMessage.contains("동영상")

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"

        // 발신자 번호 폴백 체인 (연락처 이름이 title에 들어오는 경우 대응)
        val sender = extractRealPhoneNumber(extras, title, realMessage)

        Log.d(TAG, "문자 알림: sender=$sender, msg=${realMessage.take(50)}, isMms=$isMms, hasNotifImage=${notifBitmap != null}")

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
        // 정책: 30초 내 3회 시도, 초과 시 drop
        // 서버 스키마: csphone_number=발신자, checkphone_number=수신자(내 폰)
        val request = ReceivedSMSRequest(
            csphone_number = sender,        // 발신자
            checkphone_number = myPhone,    // 내 폰 (수신자)
            message = message,
            receive_time = System.currentTimeMillis()
        )

        val deadline = System.currentTimeMillis() + 30000L
        // 지수 백오프: 0초, 5초, 10초 (총 15초 + HTTP 시간 ≤ 30초)
        val backoffs = longArrayOf(0L, 5000L, 10000L)

        for ((attempt, wait) in backoffs.withIndex()) {
            if (wait > 0) delay(wait)
            if (System.currentTimeMillis() > deadline) {
                Log.e(TAG, "SMS 전송 deadline 초과 → drop")
                return
            }
            try {
                val response = RetrofitClient.getApi(applicationContext).sendReceivedSMS(request)
                if (response.isSuccessful) {
                    Log.d(TAG, "서버 전송 성공 (시도 ${attempt + 1}/3): ${response.code()}")
                    return
                } else {
                    Log.e(TAG, "서버 전송 실패 (시도 ${attempt + 1}/3): ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버 전송 오류 (시도 ${attempt + 1}/3): ${e.message}")
            }
        }
        Log.e(TAG, "SMS 전송 3회 모두 실패 → drop")
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
        // 정책: 30초 내 3회 시도, 초과 시 drop
        val deadline = System.currentTimeMillis() + 30000L
        val backoffs = longArrayOf(0L, 5000L, 10000L)
        val timestamp = System.currentTimeMillis().toString()

        for ((attempt, wait) in backoffs.withIndex()) {
            if (wait > 0) delay(wait)
            if (System.currentTimeMillis() > deadline) {
                Log.e(TAG, "MMS 전송 deadline 초과 → drop")
                return
            }
            try {
                val api = RetrofitClient.getApi(applicationContext)
                // 서버 스키마: csphone_number=발신자, checkphone_number=수신자(내 폰)
                val csPhoneBody = sender.toRequestBody("text/plain".toMediaTypeOrNull())
                val checkPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
                val messageBody = message.toRequestBody("text/plain".toMediaTypeOrNull())
                val timeBody = timestamp.toRequestBody("text/plain".toMediaTypeOrNull())

                val imageBodies = images.mapIndexed { index, (data, mimeType) ->
                    val requestBody = data.toRequestBody(mimeType.toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("images", "mms_image_${index}.jpg", requestBody)
                }

                val response = api.sendMmsToServer(csPhoneBody, checkPhoneBody, messageBody, timeBody, imageBodies)
                if (response.isSuccessful) {
                    Log.d(TAG, "MMS 전송 성공 (시도 ${attempt + 1}/3): ${response.code()}")
                    return
                } else {
                    Log.e(TAG, "MMS 전송 실패 (시도 ${attempt + 1}/3): ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MMS 전송 오류 (시도 ${attempt + 1}/3): ${e.message}")
            }
        }
        Log.e(TAG, "MMS 전송 3회 모두 실패 → drop")
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
                        // 서버 스키마: csphone_number=발신자(mmsSender), checkphone_number=수신자(내 폰)
                        val api = RetrofitClient.getApi(applicationContext)
                        val csPhoneBody = mmsSender.toRequestBody("text/plain".toMediaTypeOrNull())
                        val checkPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
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

                        val response = api.sendMmsToServer(csPhoneBody, checkPhoneBody, messageBody, timeBody, imageBodies)
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
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 알림에서 발신자 전화번호를 추출합니다.
     * 1순위: MessagingStyle.sender (숫자를 포함한 경우에만)
     * 2순위: EXTRA_PEOPLE tel: URI
     * 3순위: content://sms/inbox 최근 3건에서 body 매칭 (Samsung Messages SMS 대응)
     * 4순위: content://mms 최근 1건 + addr 테이블 조회 (Samsung Messages MMS 이미지 대응)
     * 5순위: 알림 title (fallback — 연락처 이름이라도 반환)
     */
    private fun extractRealPhoneNumber(
        extras: Bundle,
        titleFallback: String,
        messageText: String
    ): String {
        // ── 1순위: MessagingStyle sender ──
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val lastMsg = msgs.last() as? Bundle
            val senderObj = lastMsg?.getCharSequence("sender")?.toString()
            if (!senderObj.isNullOrBlank() && senderObj.any { it.isDigit() }) {
                return normalizePhoneNumber(senderObj)
            }
        }

        // ── 2순위: EXTRA_PEOPLE (tel: URI) ──
        val people = extras.getStringArray(Notification.EXTRA_PEOPLE)
        people?.forEach { uri ->
            if (uri.startsWith("tel:")) {
                return normalizePhoneNumber(uri.removePrefix("tel:"))
            }
        }

        val hasReadSms = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        // ── 3순위: Content Provider (content://sms/inbox) 최근 3건 조회 ──
        // Samsung 메시지 앱이 알림에 번호를 안 담는 경우 SMS DB에서 직접 읽음
        if (hasReadSms) {
            try {
                val uri = Uri.parse("content://sms/inbox")
                val cursor = contentResolver.query(
                    uri,
                    arrayOf("address", "body", "date"),
                    null, null,
                    "date DESC LIMIT 3"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        val addr = it.getString(0) ?: continue
                        val body = it.getString(1) ?: ""
                        // 같은 메시지인지 body 첫 30자로 판단
                        if (body.take(30) == messageText.take(30)) {
                            return normalizePhoneNumber(addr)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "content://sms/inbox 조회 실패: ${e.message}")
            }

            // ── 4순위: content://mms 최근 1건의 addr(type=137) 조회 ──
            // MMS 이미지의 경우 SMS inbox 에는 없고 알림 본문이 "이미지" 1글자라 3순위가 실패함
            try {
                val cutoff = (System.currentTimeMillis() / 1000) - 120 // 최근 2분
                val cursor = contentResolver.query(
                    Uri.parse("content://mms"),
                    arrayOf("_id", "date"),
                    "date > ? AND msg_box = 1",
                    arrayOf(cutoff.toString()),
                    "_id DESC LIMIT 1"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val mmsId = it.getString(0)
                        val addrCursor = contentResolver.query(
                            Uri.parse("content://mms/$mmsId/addr"),
                            arrayOf("address", "type"),
                            "type=137", // 137 = PduHeaders.FROM (발신자)
                            null, null
                        )
                        addrCursor?.use { ac ->
                            if (ac.moveToFirst()) {
                                val addr = ac.getString(0)
                                if (!addr.isNullOrBlank() && addr.any { c -> c.isDigit() }) {
                                    return normalizePhoneNumber(addr)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "content://mms 조회 실패: ${e.message}")
            }
        }

        // ── 5순위: title (연락처 이름이라도 반환 — 최후 수단) ──
        Log.w(TAG, "전화번호 추출 실패, title 사용: $titleFallback")
        return normalizePhoneNumber(titleFallback).ifBlank { titleFallback }
    }

    private fun normalizePhoneNumber(number: String): String {
        // bidi isolate 문자(U+2068, U+2069) 제거 + 일반 정규화
        return number
            .replace("\u2068", "")
            .replace("\u2069", "")
            .replace("+82", "0")
            .replace("-", "")
            .replace(" ", "")
            .trim()
    }
}
