package com.example.smsreceiverapp

// AppLog 비활성화 - 로그 찍지 않음
object AppLog {
    fun add(type: LogType, message: String) {}
    fun sms(message: String) {}
    fun lms(message: String) {}
    fun mms(message: String) {}
    fun send(message: String) {}
    fun server(message: String) {}
    fun error(message: String) {}
    fun info(message: String) {}
}

data class LogEntry(
    val time: String,
    val type: LogType,
    val message: String
)

enum class LogType(val label: String, val colorHex: Long) {
    SMS("SMS", 0xFF4CAF50),
    LMS("LMS", 0xFF2196F3),
    MMS("MMS", 0xFF9C27B0),
    SEND("발송", 0xFFFF9800),
    SERVER("서버", 0xFF607D8B),
    ERROR("오류", 0xFFF44336),
    INFO("정보", 0xFF9E9E9E)
}
