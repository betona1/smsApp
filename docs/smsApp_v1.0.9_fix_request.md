# smsApp v1.0.9 버그 수정 요청서

> 저장소: https://github.com/betona1/smsApp
> 현재 버전: v1.0.8 → 목표 v1.0.9
> 작성일: 2026-04-12
> 요청 주체: 서버팀 (Django 8001 + Docker 8010, MariaDB sms2)

---

## 🎯 개요

현재 배포된 v1.0.8에서 **수신 문자 저장 실패** 및 **발송 NPE** 2종의 치명적 버그가 발견되었습니다. 서버 DB(`received_sms_message`, `sms_outbox`)와 앱 코드의 컬럼 매핑이 정반대이고, Android 12+ 에서 `SmsManager` 획득 방식이 잘못되어 발송이 불가능합니다.

## 📊 현재 상태 (서버팀 확인 결과)

| 항목 | 값 |
|------|-----|
| 등록 디바이스 | 3대 (CEO폰 01027533200 / 7550폰 01075502753 / 4373폰 01043732753) |
| 앱 버전 | v1.0.8 (3대 모두) |
| Heartbeat | 정상 (last_poll 1~12초 전) |
| 최근 수신 메시지 | id=1434 (2026-04-12 05:32:39) — **이후 새 메시지 0건** |
| id=1434 저장 상태 | `csphone=01027533200`, `checkphone=⁨김준용⁩` — **컬럼 반대 + 이름 저장** |
| 발송 실패 로그 | id=38 `NullPointerException: SmsManager.divideMessage on null` |

---

## 🗄️ 서버 DB 스키마 (변경 불가 — 앱이 맞춰야 함)

### `received_sms_message` (수신함)

| 컬럼 | 의미 |
|------|------|
| `csphone_number` | **발신자** (보낸 사람 전화번호) |
| `checkphone_number` | **수신자** (받은 사람 = 내 폰 번호) |
| `message` | 메시지 본문 |
| `received_at` | 수신 시각 |

> ⚠️ 두 컬럼의 의미를 혼동하지 마세요. 이름이 `cs*`, `check*` 여서 직관적이지 않지만 **cs=발신자, check=수신자**가 확정된 스키마입니다 (CLAUDE.md §25-2).

### `sms_outbox` (발송 큐)

| 컬럼 | 의미 |
|------|------|
| `id` | outbox 고유 ID |
| `phone_number` | 수신자 (발송 대상) |
| `sender_phone` | **어느 폰으로 보낼지** (내 폰 번호, v2 폰별 발송용) |
| `message` | 메시지 본문 |
| `status` | `pending` / `sent` / `failed` |

앱은 `GET /api/cpc/sms/outbox/?status=pending` 으로 대기열을 받을 때 **자기 폰 번호와 `sender_phone`이 일치하는 것만** 처리해야 합니다 (이미 구현되어 있다면 패스).

---

## 🐛 버그 #1 [P0] — 수신 문자 컬럼 반대 매핑

### 증상

7550폰에서 CEO폰으로 문자 발송 → 핸드폰은 수신 → **서버 DB에는 발신자/수신자가 뒤집혀 저장** 또는 아예 캡처 안 됨.

### 원인

**파일**: `app/src/main/java/com/example/smsreceiverapp/SmsNotificationListener.kt`
**함수**: `sendSmsToServer()`

현재 코드 (v1.0.8, 커밋 094ee2b 기준):

```kotlin
private suspend fun sendSmsToServer(myPhone: String, sender: String, message: String, msgType: String) {
    val request = ReceivedSMSRequest(
        csphone_number = myPhone,      // ❌ 내 폰(수신자)을 발신자 자리에
        checkphone_number = sender,    // ❌ 발신자를 수신자 자리에
        message = message,
        receive_time = System.currentTimeMillis()
    )
    // ...
}
```

### 수정

```kotlin
private suspend fun sendSmsToServer(myPhone: String, sender: String, message: String, msgType: String) {
    val request = ReceivedSMSRequest(
        csphone_number = sender,        // ✅ 발신자
        checkphone_number = myPhone,    // ✅ 내 폰 (수신자)
        message = message,
        receive_time = System.currentTimeMillis()
    )
    // ...
}
```

### MMS 경로도 동일 수정 필요

같은 파일 내 다음 함수들도 확인/수정:
- `sendMmsMultipart(myPhone, sender, ...)`
- `sendMmsFromContentProvider(myPhone, sender, ...)`

두 함수 내부에서 `ReceivedSMSRequest` 또는 multipart form-data 를 만드는 부분의 `csphone_number` / `checkphone_number` 매핑을 동일하게 뒤집어 주세요.

---

## 🐛 버그 #2 [P0] — 발신자 번호 추출 실패 (연락처 이름만 저장됨)

### 증상

DB `csphone_number` (버그 #1 수정 후 기준) 에 전화번호 대신 **연락처 이름**이 저장됨. 유니코드 bidi isolate 문자(U+2068 ⁨, U+2069 ⁩) 까지 포함:

```
csphone_number = "⁨김준용⁩"   ← 전화번호가 아님!
```

### 원인

**파일**: `SmsNotificationListener.kt`
**함수**: `onNotificationPosted()`

```kotlin
val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
// ...
var realSender = title  // ❌ Samsung 메시지 앱은 title에 연락처 이름을 넣음
```

`MessagingStyle` 폴백 로직이 있지만 **Samsung 기본 메시지 앱(`com.samsung.android.messaging`)은 `EXTRA_MESSAGES`를 설정하지 않는 경우가 대부분**. 결과적으로 `realSender`는 연락처 이름("김준용")으로 유지되고, `normalizePhoneNumber("⁨김준용⁩")`는 숫자가 없어서 원본 문자열이 그대로 반환됩니다.

### 수정 (폴백 체인 4단계)

```kotlin
/**
 * 알림에서 발신자 전화번호를 추출합니다.
 * 1순위: MessagingStyle.sender (Google Messages 등 일부)
 * 2순위: EXTRA_PEOPLE tel: URI
 * 3순위: content://sms/inbox 최근 1건 매칭 (Samsung Messages 대응)
 * 4순위: 알림 title (fallback — 연락처 이름)
 */
private fun extractRealPhoneNumber(
    extras: Bundle,
    titleFallback: String,
    messageText: String
): String {
    // ── 1순위: MessagingStyle sender ──
    val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
    if (msgs != null && msgs.isNotEmpty()) {
        val lastMsg = msgs.last() as? Bundle
        val senderObj = lastMsg?.getCharSequence("sender")?.toString()
        if (!senderObj.isNullOrBlank() && senderObj.any { it.isDigit() }) {
            return normalizePhoneNumber(senderObj)
        }
    }

    // ── 2순위: EXTRA_PEOPLE (tel: URI) ──
    val people = extras.getStringArray(Notification.EXTRA_PEOPLE)
    people?.forEach { uri ->
        if (uri.startsWith("tel:")) {
            return normalizePhoneNumber(uri.removePrefix("tel:"))
        }
    }

    // ── 3순위: Content Provider (content://sms/inbox) 최근 1건 조회 ──
    // Samsung 메시지 앱이 알림에 번호를 안 담는 경우 SMS DB에서 직접 읽음
    try {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val uri = Uri.parse("content://sms/inbox")
            val cursor = contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                null, null,
                "date DESC LIMIT 3"   // 최근 3건 (타이밍 고려)
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val addr = it.getString(0) ?: continue
                    val body = it.getString(1) ?: ""
                    // 같은 메시지인지 body 첫 30자로 판단
                    if (body.take(30) == messageText.take(30)) {
                        return normalizePhoneNumber(addr)
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "content://sms/inbox 조회 실패: ${e.message}")
    }

    // ── 4순위: title (연락처 이름이라도 반환 — 최후 수단) ──
    Log.w(TAG, "전화번호 추출 실패, title 사용: $titleFallback")
    return normalizePhoneNumber(titleFallback).ifBlank { titleFallback }
}
```

### `onNotificationPosted()` 호출부 수정

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
    // ...기존 중복 체크 생략

    val extras = sbn.notification.extras
    val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val message = bigText ?: text
    if (message.isBlank()) return
    if (title == "메시지" || title.isBlank() || message == "메시지 보기") return

    // ✅ 신규 폴백 체인 사용
    val sender = extractRealPhoneNumber(extras, title, message)
    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
    val myPhone = prefs.getString("my_phone_number", "unknown") ?: "unknown"

    Log.d(TAG, "문자 알림: sender=$sender, msg=${message.take(50)}")

    // ... MMS/SMS 분기 → sendSmsToServer(myPhone, sender, ...) 호출
    // (버그 #1 수정 후 내부에서 csphone=sender, checkphone=myPhone 으로 전송)
}
```

### 권한 추가

**`AndroidManifest.xml`**

```xml
<uses-permission android:name="android.permission.READ_SMS" />
```

**런타임 권한 요청** — MainActivity 또는 설정 화면에서:

```kotlin
private val SMS_PERMISSION_REQ = 1001

private fun checkReadSmsPermission() {
    if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_SMS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_SMS),
            SMS_PERMISSION_REQ
        )
    }
}
```

---

## 🐛 버그 #3 [P0] — 발송 NullPointerException (Android 12+)

### 증상

`sms_outbox` id=38 발송 시도 실패:
```
java.lang.NullPointerException: Attempt to invoke virtual method
'java.util.ArrayList android.telephony.SmsManager.divideMessage(java.lang.String)'
on a null object reference
```

### 원인

**파일**: `SmsNotificationListener.kt` (`pollAndSend()`) 및 `SmsSenderWorker.kt` (있다면)

```kotlin
// ❌ Android 12+ 에서 기본 SMS 구독이 없으면 null 반환
val smsManager = applicationContext.getSystemService(SmsManager::class.java)

for (sms in smsList) {
    val parts = smsManager.divideMessage(sms.message)  // ← NPE
    // ...
}
```

Android 12 (API 31) 부터 `Context.getSystemService(SmsManager::class.java)`는 **기본 SMS 구독이 설정되어 있지 않거나, 앱이 기본 SMS 앱이 아닌 경우 null을 반환**할 수 있음. `SubscriptionManager`를 경유해야 안전하게 획득 가능.

### 수정

```kotlin
/**
 * Android 12+ 대응 SmsManager 안전 획득
 */
private fun getSmsManagerSafe(): SmsManager? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ : SubscriptionManager 경유
            val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                applicationContext.getSystemService(SmsManager::class.java)
                    ?.createForSubscriptionId(subId)
            } else {
                // 구독 ID 없으면 기본 SmsManager 시도
                applicationContext.getSystemService(SmsManager::class.java)
            }
        } else {
            // Android 11 이하
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (e: Exception) {
        Log.e(TAG, "SmsManager 획득 실패: ${e.message}")
        null
    }
}
```

### `pollAndSend()` 수정

```kotlin
private suspend fun pollAndSend() {
    if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) != PackageManager.PERMISSION_GRANTED
    ) return

    val api = RetrofitClient.getApi(applicationContext)
    val response = api.getOutgoingSms()
    if (!response.isSuccessful) return

    val smsList = response.body().orEmpty()
    if (smsList.isEmpty()) return

    Log.d(TAG, "발송 대기 ${smsList.size}건")

    // ✅ 안전 획득
    val smsManager = getSmsManagerSafe()
    if (smsManager == null) {
        Log.e(TAG, "SmsManager null — 전체 발송 실패 처리")
        for (sms in smsList) {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            try {
                api.reportSmsResult(sms.id, SmsSendResult(
                    id = sms.id,
                    status = "failed",
                    error_message = "SmsManager 초기화 실패 (기본 SMS 앱 설정 및 SEND_SMS 권한 확인)",
                    sent_at = now
                ))
            } catch (_: Exception) {}
        }
        return
    }

    for (sms in smsList) {
        try {
            if (sms.message.isBlank()) {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
                api.reportSmsResult(sms.id, SmsSendResult(
                    id = sms.id, status = "failed",
                    error_message = "빈 메시지", sent_at = now
                ))
                continue
            }
            Log.d(TAG, "발송: ${sms.phone_number} - ${sms.message.take(30)}")

            val parts = smsManager.divideMessage(sms.message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(sms.phone_number, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(sms.phone_number, null, sms.message, null, null)
            }

            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            api.reportSmsResult(sms.id, SmsSendResult(id = sms.id, status = "sent", sent_at = now))
            Log.d(TAG, "발송 성공: ID=${sms.id}")
        } catch (e: Exception) {
            Log.e(TAG, "발송 실패: ID=${sms.id} - ${e.message}")
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            try {
                api.reportSmsResult(sms.id, SmsSendResult(
                    id = sms.id, status = "failed",
                    error_message = e.message, sent_at = now
                ))
            } catch (_: Exception) {}
        }
    }
}
```

### `SmsSenderWorker.kt` 확인

저장소에 `SmsSenderWorker.kt` 가 존재한다면 동일한 `getSystemService(SmsManager::class.java)` 패턴이 있을 가능성이 높으니 동일 방식으로 수정 필요.

### import 추가

```kotlin
import android.os.Build
import android.telephony.SubscriptionManager
```

---

## 🧪 테스트 시나리오

### 사전 준비
- 3대 핸드폰(CEO폰/7550폰/4373폰)에 v1.0.9 APK 설치
- 각 폰에서 `READ_SMS` 권한 허용 확인
- 서버: `mysql -h 127.0.0.1 -P 3307 -u joachamsms -p sms2` 접속해서 쿼리 준비

### 테스트 1: 수신 컬럼 매핑 (버그 #1)

1. 7550폰에서 CEO폰으로 "테스트1" 문자 발송
2. 서버 DB 조회:
   ```sql
   SELECT id, csphone_number, checkphone_number, message, received_at
   FROM received_sms_message
   ORDER BY id DESC LIMIT 1;
   ```
3. **기대값:**
   - `csphone_number` = `01075502753` (7550폰 = 발신자)
   - `checkphone_number` = `01027533200` (CEO폰 = 수신자)
   - `message` = `테스트1`

### 테스트 2: 연락처 이름 대신 번호 (버그 #2)

1. CEO폰에 7550폰 번호를 "테스트발신자" 이름으로 연락처 저장
2. 7550폰 → CEO폰으로 "테스트2" 발송
3. 서버 DB 조회 (위와 동일)
4. **기대값:**
   - `csphone_number` = `01075502753` (숫자 11자리, "테스트발신자" 가 아님)
   - bidi 문자(U+2068/U+2069) 없음

### 테스트 3: 발송 성공 (버그 #3)

1. 웹 UI (`http://localhost:5173/#sms`) → 발송 탭 → 7550폰 선택
2. 수신자: `01027533200` (CEO폰), 메시지 "테스트3"
3. 발송 클릭
4. 서버 DB:
   ```sql
   SELECT id, phone_number, sender_phone, message, status, error_message, created_at
   FROM sms_outbox
   ORDER BY id DESC LIMIT 1;
   ```
5. **기대값:** `status = 'sent'` (최대 5초 내), `error_message = NULL`
6. CEO폰에서 "테스트3" 실제 수신 확인

### 테스트 4: 수신함 전체 흐름 (종합)

1. 폰별 테스트 매트릭스:
   | 발신 | 수신 | 기대 csphone | 기대 checkphone |
   |------|------|-------------|-----------------|
   | 7550폰 | CEO폰 | 01075502753 | 01027533200 |
   | CEO폰 | 4373폰 | 01027533200 | 01043732753 |
   | 4373폰 | 7550폰 | 01043732753 | 01075502753 |
2. 각 조합으로 문자 발송 → 서버 `received_sms_message` 확인
3. 웹 수신함 탭에서 폰별 서브탭 필터링 시 정확히 표시되는지 확인

---

## 📋 작업 체크리스트

- [ ] 저장소 `betona1/smsApp` clone 후 브랜치 생성 (`fix/v1.0.9-csphone-mapping`)
- [ ] `SmsNotificationListener.kt` — 버그 #1 (sendSmsToServer 컬럼 매핑)
- [ ] `SmsNotificationListener.kt` — 버그 #1 (sendMmsMultipart MMS 경로)
- [ ] `SmsNotificationListener.kt` — 버그 #1 (sendMmsFromContentProvider MMS 경로)
- [ ] `SmsNotificationListener.kt` — 버그 #2 (extractRealPhoneNumber 신규 함수)
- [ ] `SmsNotificationListener.kt` — 버그 #2 (onNotificationPosted 호출부 수정)
- [ ] `AndroidManifest.xml` — `READ_SMS` 권한 추가
- [ ] MainActivity 또는 설정 — `READ_SMS` 런타임 권한 요청 UI
- [ ] `SmsNotificationListener.kt` — 버그 #3 (`getSmsManagerSafe` + `pollAndSend` 수정)
- [ ] `SmsSenderWorker.kt` — 버그 #3 (있다면 동일 수정)
- [ ] `build.gradle` — `versionName "1.0.9"`, `versionCode` +1
- [ ] 로컬 빌드 + Lint 통과 확인
- [ ] APK 빌드 (release)
- [ ] 3대 디바이스 배포 + 앱 재시작
- [ ] 테스트 시나리오 1~4 실행 + 결과 기록
- [ ] 커밋 + 태그 `v1.0.9`
- [ ] 서버팀에 배포 완료 보고

---

## 🔧 서버팀 협조 사항

앱 배포 후 서버팀에서 다음 작업 예정 (앱 개발자는 관여 불필요):

1. **기존 잘못 저장된 레코드 교정**
   - `received_sms_message` 중 앱 v1.0.8 로 저장된 레코드(id ≤ 1434 중 컬럼 반대인 것)의 csphone/checkphone 을 SWAP 하는 UPDATE 스크립트 실행
2. **Django API 방어 로직 (선택)**
   - `/api/cpc/sms/receive/` 엔드포인트에서 `csphone_number` 가 내 디바이스 번호와 일치하면 자동 SWAP (구버전 앱과의 호환성 방어)

---

## 📞 문의

수정 중 서버 API 스펙(`ReceivedSMSRequest` 필드, `/api/cpc/sms/receive/` 응답 등)이 모호하면 서버팀에 문의 바랍니다. 서버 참고 파일:

- `viewer/gmarket_cpc/CLAUDE.md` §25 (SMS 문자 관리 시스템)
- `viewer/gmarket_cpc/backend/cpc/views.py` — `SmsReceiveView`
- `viewer/gmarket_cpc/docs/SMS_MODULE.md` (상세 기술문서)

---

**작성:** 2026-04-12
**우선순위:** P0 (수신/발송 전면 장애)
**목표 배포:** ASAP
