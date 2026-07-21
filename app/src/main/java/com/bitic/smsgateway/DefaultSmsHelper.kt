package com.bitic.smsgateway

import android.content.Context
import android.provider.Telephony

/**
 * 기본 문자앱 여부 판별.
 *
 * 기본 문자앱이면 문자를 SmsDeliverReceiver(SMS_DELIVER)로 직접·정확히 수신하므로,
 * 겹치는 옛 수신 경로(NotificationListener 알림파싱, ContentObserver DB감시)는
 * 처리를 건너뛴다. → 중복 저장/발신자 오파싱(내폰끼리 문자 시 CS=CK) 방지.
 *
 * 기본 문자앱이 아니면(백업 폰 등) 기존 경로로 수신을 계속한다.
 */
object DefaultSmsHelper {
    fun isDefaultSmsApp(context: Context): Boolean = try {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    } catch (e: Exception) {
        false
    }
}
