package com.example.smsreceiverapp.ui.theme

import com.example.smsreceiverapp.CSPhoneSettingResponse
import com.example.smsreceiverapp.db.CSPhoneEntity

fun CSPhoneSettingResponse.toEntity(): CSPhoneEntity {
    return CSPhoneEntity(
        id = this.id,
        csphone_number = this.csphone_number,
        checkphone_number = this.checkphone_number.replace("-",""),
        alias = this.alias,
        is_save_to_db = this.is_save_to_db,
        is_notify_pc = this.is_notify_pc,
        is_admin = this.is_admin,
        is_notify_telegram = this.is_notify_telegram,
        payment_date = this.payment_date,
        created_at = this.created_at
    )
}