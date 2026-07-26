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
 *
 * ★ 반드시 '정확 알람'(setExactAndAllowWhileIdle)이어야 한다.
 * Android 12+ 는 백그라운드에서 포그라운드 서비스를 시작하는 것을 막는데, 그 면제 목록에
 * 들어가는 건 정확 알람뿐이다. 부정확 알람(setAndAllowWhileIdle)으로 깨어나면 알람은 와도
 * SmsSenderService.start() 가 ForegroundServiceStartNotAllowedException 으로 막혀
 * 죽은 서비스를 되살리지 못한다(v2.1.4 까지 그랬음).
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
                // Android 12+ 는 정확 알람 권한이 사용자에 의해 회수될 수 있다 → 확인 후 폴백.
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.canScheduleExactAlarms()
                } else true

                when {
                    canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                    else ->
                        am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                Log.d("KeepAlive", "다음 keepalive 예약 (+9분, 정확알람=$canExact)")
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
