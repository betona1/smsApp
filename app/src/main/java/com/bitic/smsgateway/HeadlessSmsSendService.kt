package com.bitic.smsgateway

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 기본 문자앱 필수 요건 — 통화 중 "메시지로 답장"(RESPOND_VIA_MESSAGE) 처리 서비스.
 * 본 앱은 게이트웨이 용도라 실제 quick-response는 사용하지 않으므로 최소 구현.
 * (컴포넌트가 존재해야 기본 문자앱 역할을 부여받을 수 있음)
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // RESPOND_VIA_MESSAGE는 게이트웨이 용도상 미사용 — 요건 충족용 스텁
        return START_NOT_STICKY
    }
}
