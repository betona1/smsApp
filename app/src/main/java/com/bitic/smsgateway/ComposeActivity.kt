package com.bitic.smsgateway

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 기본 문자앱 필수 요건 — 문자 작성(ACTION_SENDTO sms:/smsto:) 진입 액티비티.
 * 본 앱은 서버 연동 게이트웨이라 일반 문자 작성 UI는 제공하지 않음.
 * (컴포넌트가 존재해야 기본 문자앱 역할을 부여받을 수 있음)
 */
class ComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(
            this,
            "SMS Gate는 서버 연동 게이트웨이 앱입니다. 문자 작성은 지원하지 않습니다.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
