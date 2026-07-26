package com.bitic.smsgateway

data class HeartbeatRequest(
    val phone_number: String,
    val app_version: String? = null,
    /**
     * 폰의 사내망 IP. 서버가 무선 ADB 로 이 폰을 되살릴 때 쓴다.
     *
     * 서버는 REMOTE_ADDR 로 이걸 알 수 없다 — 앱이 외부주소로 페일오버하면 공유기를 거쳐
     * 들어와 서버엔 게이트웨이 IP(192.168.219.1)만 찍히기 때문. 그래서 폰이 직접 알려준다.
     * 매번 실어보내므로 DHCP 로 IP 가 바뀌어도 서버는 늘 최신 주소를 안다.
     */
    val local_ip: String? = null
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
