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
    val local_ip: String? = null,
    /**
     * 지금 실제로 쓰고 있는 서버 주소, 외부주소가 설정돼 있는지 여부.
     * 폰이 사내망을 벗어났을 때 외부주소로 넘어갈 수 있는 상태인지 서버에서 미리 보려는 것 —
     * 외부주소가 공란인 폰은 밖에 나가는 순간 중계가 멈춘다.
     */
    val active_url: String? = null,
    val ext_configured: Boolean? = null
)

data class HeartbeatResponse(
    val ok: Boolean,
    val connected: Boolean,
    val is_notify_telegram: Boolean = true,
    val server_time: String? = null,
    /**
     * 서버가 알려주는 외부 접속 주소(예: http://1.2.3.4:8379/).
     * 사내망을 벗어났을 때 넘어갈 곳 — 폰마다 손으로 설정하지 않으려고 서버가 내려준다.
     * 공란이면 앱은 무시한다(기존 설정 유지).
     */
    val ext_url: String? = null
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
