package com.example.smsreceiverapp

data class ReceivedSMSRequest(
    val csphone_number: String,
    val checkphone_number: String,
    val message: String,
    val receive_time: Long
)