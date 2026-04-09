package com.example.smsreceiverapp

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "MMS 수신 감지")

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"

        // MMS는 수신 후 DB에 저장되기까지 시간이 걸림 → 지연 후 읽기
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000) // 5초 대기 (MMS 다운로드 완료 대기)
            try {
                readLatestMms(context, myPhone)
            } catch (e: Exception) {
                Log.e(TAG, "MMS 처리 오류: ${e.message}", e)
            }
        }
    }

    private suspend fun readLatestMms(context: Context, myPhone: String) {
        val resolver = context.contentResolver

        // 최근 MMS 조회 (최근 60초 이내)
        val cutoff = (System.currentTimeMillis() / 1000) - 60
        val cursor: Cursor? = resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            "date > ? AND msg_box = 1", // msg_box=1: 수신함
            arrayOf(cutoff.toString()),
            "date DESC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val mmsId = it.getString(it.getColumnIndexOrThrow("_id"))
                Log.d(TAG, "MMS ID: $mmsId 처리 중")

                val sender = getMmsSender(resolver, mmsId)
                val textParts = mutableListOf<String>()
                val imageParts = mutableListOf<Pair<ByteArray, String>>() // data, mimeType

                extractMmsParts(resolver, mmsId, textParts, imageParts)

                val messageText = textParts.joinToString("\n").ifEmpty { "(이미지)" }
                Log.d(TAG, "발신: $sender, 텍스트: $messageText, 이미지: ${imageParts.size}장")

                // 서버에 전송
                sendMmsToServer(context, myPhone, sender, messageText, imageParts)
            }
        }
    }

    private fun getMmsSender(resolver: ContentResolver, mmsId: String): String {
        val addrCursor = resolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address", "type"),
            "type=137", // 137 = FROM
            null, null
        )
        var sender = "unknown"
        addrCursor?.use {
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
        val partCursor = resolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text", "_data"),
            "mid=$mmsId",
            null, null
        )

        partCursor?.use {
            while (it.moveToNext()) {
                val partId = it.getString(it.getColumnIndexOrThrow("_id"))
                val contentType = it.getString(it.getColumnIndexOrThrow("ct")) ?: ""

                when {
                    contentType == "text/plain" -> {
                        val text = it.getString(it.getColumnIndexOrThrow("text"))
                        if (!text.isNullOrBlank()) {
                            textParts.add(text)
                        }
                    }
                    contentType.startsWith("image/") -> {
                        val imageData = readPartData(resolver, partId)
                        if (imageData != null) {
                            imageParts.add(Pair(imageData, contentType))
                        }
                    }
                }
            }
        }
    }

    private fun readPartData(resolver: ContentResolver, partId: String): ByteArray? {
        return try {
            val uri = Uri.parse("content://mms/part/$partId")
            resolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(data).also { bytesRead = it } != -1) {
                    buffer.write(data, 0, bytesRead)
                }
                buffer.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 읽기 실패: partId=$partId, ${e.message}")
            null
        }
    }

    private suspend fun sendMmsToServer(
        context: Context,
        myPhone: String,
        sender: String,
        message: String,
        images: List<Pair<ByteArray, String>>
    ) {
        try {
            val api = RetrofitClient.getApi(context)

            // 텍스트 파트
            val csPhoneBody = myPhone.toRequestBody("text/plain".toMediaTypeOrNull())
            val senderBody = sender.toRequestBody("text/plain".toMediaTypeOrNull())
            val messageBody = message.toRequestBody("text/plain".toMediaTypeOrNull())
            val timeBody = System.currentTimeMillis().toString()
                .toRequestBody("text/plain".toMediaTypeOrNull())

            // 이미지 파트
            val imageBodies = images.mapIndexed { index, (data, mimeType) ->
                val ext = when {
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                    mimeType.contains("png") -> "png"
                    mimeType.contains("gif") -> "gif"
                    else -> "jpg"
                }
                val requestBody = data.toRequestBody(mimeType.toMediaTypeOrNull())
                MultipartBody.Part.createFormData(
                    "images", "mms_image_${index}.$ext", requestBody
                )
            }

            val response = api.sendMmsToServer(
                csPhoneNumber = csPhoneBody,
                senderNumber = senderBody,
                message = messageBody,
                receiveTime = timeBody,
                images = imageBodies
            )

            if (response.isSuccessful) {
                Log.d(TAG, "MMS 서버 전송 성공")
            } else {
                Log.e(TAG, "MMS 서버 전송 실패: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MMS 서버 전송 오류: ${e.message}", e)
        }
    }

    private fun normalizePhoneNumber(number: String): String {
        return number
            .replace("+82", "0")
            .replace("-", "")
            .replace(" ", "")
            .trim()
    }
}
