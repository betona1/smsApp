package com.example.smsreceiverapp

/**
 * NotificationListener와 ContentObserver 간 중복 전송 방지용 싱글톤.
 * 동일 메시지(발신자+본문 앞 30자)가 60초 내 재전송되면 스킵.
 */
object ProcessedMessages {
    private val recentKeys = LinkedHashMap<String, Long>(200, 0.75f, true)

    @Synchronized
    fun isDuplicate(sender: String, messagePrefix: String): Boolean {
        val key = "$sender:${messagePrefix.take(30)}"
        val now = System.currentTimeMillis()
        // 60초 이상 지난 항목 정리
        recentKeys.entries.removeIf { now - it.value > 60_000 }
        return if (recentKeys.containsKey(key)) {
            true
        } else {
            recentKeys[key] = now
            false
        }
    }
}
