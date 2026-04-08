package com.example.smsreceiverapp



data class CSPhoneSettingResponse(
    val id: Int,
    val csphone_number: String,
    val checkphone_number: String,
    val alias: String?,
    val is_save_to_db: Boolean,
    val is_notify_pc: Boolean,
    val is_notify_telegram: Boolean,
    val created_at: String,
    val is_admin: Boolean,  // ✅ 여기에 추가해줘야 서버값을 받음
    val payment_date: String   // ✅ 추가 (서버와 동일한 필드명)
)

fun CSPhoneSettingResponse.toRequest(): CSPhoneSettingRequest {
    return CSPhoneSettingRequest(
        csphone_number = this.csphone_number,
        checkphone_number = this.checkphone_number,
        alias = this.alias,
        is_save_to_db = this.is_save_to_db,
        is_notify_pc = this.is_notify_pc,
        is_notify_telegram = this.is_notify_telegram,
        is_admin = this.is_admin,
        payment_date = this.payment_date
    )
}