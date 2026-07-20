package com.bitic.smsgateway

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 깊은 절전(Doze)에서도 발송 폴링/heartbeat가 죽지 않게 하는 핵심 장치.
 *
 * 기본 문자앱(프로세스 강제종료 안 됨) + 배터리 면제 + 이 알람 3박자로,
 * 폰이 깊이 자도 AlarmManager가 앱을 깨워 SmsSenderService를 되살린다.
 * setAndAllowWhileIdle는 Doze 중에도 발사되며(화이트리스트 시 더 자주),
 * 매번 다음 알람을 재예약해 무한 반복한다.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.bitic.smsgateway.KEEPALIVE"
        private const val INTERVAL = 9 * 60 * 1000L  // 9분 (Doze 스로틀 존중)
        private const val REQ = 7001

        fun schedule(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    context, REQ,
                    Intent(context, KeepAliveReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val trigger = System.currentTimeMillis() + INTERVAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                Log.d("KeepAlive", "다음 keepalive 예약 (+9분)")
            } catch (e: Exception) {
                Log.e("KeepAlive", "알람 예약 실패: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("KeepAlive", "keepalive 발사 → 발송서비스 재시작 + 재예약")
        try {
            SmsSenderService.start(context.applicationContext)
        } catch (e: Exception) {
            Log.e("KeepAlive", "서비스 재시작 실패: ${e.message}")
        }
        schedule(context)  // 다음 알람 재예약 (무한 반복)
    }
}
