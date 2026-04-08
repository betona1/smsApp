package com.example.smsreceiverapp

// PhoneSetting.kt
data class PhoneSetting(
    val csphone_number: String,
    val checkphone_number: String,
    val alias: String,
    val is_save_to_db: Boolean,
    val is_notify_pc: Boolean,
    val is_notify_telegram: Boolean,
    val created_at: String
)