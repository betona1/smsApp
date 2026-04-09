package com.example.smsreceiverapp

data class OutgoingSmsResponse(
    val items: List<OutgoingSms>
)

data class OutgoingSms(
    val id: Int,
    val phone_number: String,       // 발송 대상 번호
    val message: String,            // 발송할 메시지
    val sender_phone: String,       // 발신자 번호
    val template_id: Int? = null,   // 템플릿 ID
    val status: String = "pending", // pending, sent, failed
    val error_message: String? = null,
    val created_at: String? = null,
    val sent_at: String? = null
)

data class SmsSendResult(
    val id: Int,
    val status: String,             // "sent" or "failed"
    val error_message: String? = null,
    val sent_at: String? = null
)
