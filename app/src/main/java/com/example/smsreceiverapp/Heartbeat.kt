package com.example.smsreceiverapp

data class HeartbeatRequest(
    val phone_number: String,
    val app_version: String? = null
)

data class HeartbeatResponse(
    val ok: Boolean,
    val connected: Boolean,
    val server_time: String? = null
)
