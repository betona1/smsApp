package com.example.smsreceiverapp.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.smsreceiverapp.CSPhoneSettingResponse

@Entity(tableName = "cs_phone")
data class CSPhoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val csphone_number: String,        // 내 핸드폰 번호
    val checkphone_number: String,     // 문자 발신 번호
    val alias: String? = null,         // 별칭
    val is_save_to_db: Boolean = true,
    val is_notify_pc: Boolean = false,
    val is_admin: Boolean = false,
    val is_notify_telegram: Boolean = false,
    val created_at: String? = null,  // 또는 "" 형식으로 추가
    val payment_date: String? = null   // 날짜형은 문자열로 받되, 형식은 "YYYY-MM-DD"

)
fun CSPhoneEntity.toResponse(): CSPhoneSettingResponse {
    return CSPhoneSettingResponse(
        id = this.id,
        csphone_number = this.csphone_number,
        checkphone_number = this.checkphone_number,
        alias = this.alias,
        is_save_to_db = this.is_save_to_db,
        is_notify_pc = this.is_notify_pc,
        is_notify_telegram = this.is_notify_telegram,
        is_admin = this.is_admin,
        payment_date = this.payment_date ?: "",
        created_at = this.created_at ?: ""
    )
}