package com.example.smsreceiverapp

data class ReceivedSMSMessageRequest(
    val csphone_number: String,        // 내 핸드폰 번호
    val checkphone_number: String,     // 발신자 번호
    val message: String,
    val receive_time: String           // "2025-07-07T13:15:00" 형식 (ISO 8601)
)