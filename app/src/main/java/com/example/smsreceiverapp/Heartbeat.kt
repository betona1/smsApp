package com.example.smsreceiverapp

data class HeartbeatRequest(
    val phone_number: String,
    val app_version: String? = null
)

data class HeartbeatResponse(
    val ok: Boolean,
    val connected: Boolean,
    val is_notify_telegram: Boolean = true,
    val server_time: String? = null
)

data class TelegramToggleRequest(
    val phone_number: String,
    val enabled: Boolean
)

data class TelegramToggleResponse(
    val ok: Boolean,
    val is_notify_telegram: Boolean
)

data class ChangePhoneNumberRequest(
    val old_phone: String,
    val new_phone: String
)

data class ChangePhoneNumberResponse(
    val ok: Boolean,
    val action: String? = null,  // renamed / merged_to_new / activated / created / noop
    val message: String? = null
)
