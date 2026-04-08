package com.example.smsreceiverapp

data class CSPhoneSettingRequest(
    val csphone_number: String,
    val checkphone_number: String,
    val alias: String?,
    val is_save_to_db: Boolean,
    val is_notify_pc: Boolean,
    val is_notify_telegram: Boolean,
    val is_admin: Boolean = true, // ✅ 선택사항: 등록/수정할 때도 필요하면 추가
    val payment_date: String? = null // ✅ 추가 (서버와 동일한 필드명)
)