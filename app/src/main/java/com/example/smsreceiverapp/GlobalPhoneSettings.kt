package com.example.smsreceiverapp

// GlobalPhoneSettings.kt
object GlobalPhoneSettings {
    var settingsList: List<PhoneSetting> = emptyList()

    fun updateSettings(dummyList: List<CSPhoneSettingResponse>) {
        settingsList = dummyList.map {
            PhoneSetting(
                csphone_number = it.csphone_number,
                checkphone_number = it.checkphone_number,
                alias = it.alias ?:"",
                is_save_to_db = it.is_save_to_db,
                is_notify_pc = it.is_notify_pc,
                is_notify_telegram = it.is_notify_telegram,
                created_at = it.created_at
            )
        }
    }
}