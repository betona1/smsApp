# SMS Receiver App - 기술 문서

> **앱 이름**: SMS Receiver App (`com.example.smsreceiverapp`)
> **최신 버전**: v1.0.12 (versionCode 13)
> **GitHub**: https://github.com/betona1/smsApp
> **최종 업데이트**: 2026-04-14

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [아키텍처](#2-아키텍처)
3. [SMS 수신 경로](#3-sms-수신-경로)
4. [SMS 발송 (Outbox)](#4-sms-발송-outbox)
5. [서버 통신 (API)](#5-서버-통신-api)
6. [핵심 컴포넌트 상세](#6-핵심-컴포넌트-상세)
7. [설정 및 SharedPreferences](#7-설정-및-sharedpreferences)
8. [자동 업데이트 시스템](#8-자동-업데이트-시스템)
9. [APK 빌드 및 배포](#9-apk-빌드-및-배포)
10. [버전 이력 및 변경사항](#10-버전-이력-및-변경사항)
11. [파일 구조](#11-파일-구조)
12. [트러블슈팅](#12-트러블슈팅)

---

## 1. 시스템 개요

### 목적
Android 핸드폰에 수신되는 모든 SMS/MMS/RCS 문자를 중앙 서버로 실시간 전송하고, 서버에서 지시하는 문자를 핸드폰을 통해 실제 발송하는 **양방향 SMS 게이트웨이** 앱.

### 운영 환경

| 항목 | 값 |
|------|------|
| 운영 디바이스 | CEO폰(01027533200), 7550폰(01075502753), 4373폰(01043732753) |
| 서버 | 192.168.219.100:8010 (Docker `owpro8010`) |
| DB | MySQL sms2 (127.0.0.1:3307) |
| 웹 대시보드 | gmarket_cpc viewer (port 8001) |
| minSdk | 24 (Android 7.0) |
| targetSdk | 35 (Android 15) |

### 핵심 기능
- **수신 전송**: 핸드폰으로 받은 SMS/LMS/MMS를 서버 DB에 저장
- **문자 발송**: 서버에서 등록한 대기 문자를 핸드폰으로 실제 발송 (5초 폴링)
- **하트비트**: 30초마다 디바이스 상태(버전, IP) 서버에 보고
- **자동 업데이트**: GitHub Releases에서 1시간마다 새 버전 확인 → 자동 설치
- **부팅 자동 시작**: 폰 재부팅 시 서비스 자동 재시작

---

## 2. 아키텍처

```
┌──────────────────────────────────────────────────┐
│                Android Phone                      │
│                                                    │
│  ┌─────────────────────┐  ┌─────────────────────┐ │
│  │ SmsNotificationListener │  │  SmsSenderService    │ │
│  │ (1순위 - RCS/SMS/MMS)   │  │  (Foreground)        │ │
│  │ → 알림 기반 캡처        │  │  ├─ 5초 폴링 발송    │ │
│  └──────┬──────────────┘  │  ├─ ContentObserver   │ │
│         │                  │  │   (2순위 백업)      │ │
│  ┌──────┴──────────────┐  │  └─────────┬─────────┘ │
│  │ SmsReceiver          │  │            │            │
│  │ (비활성화 - 3순위)    │  │            │            │
│  └─────────────────────┘  │            │            │
│         │                  │            │            │
│         ▼                  │            ▼            │
│  ┌─────────────────────────────────────────────┐   │
│  │         ProcessedMessages (중복 방지)         │   │
│  └──────────────────┬──────────────────────────┘   │
│                     │                               │
│                     ▼                               │
│  ┌─────────────────────────────────────────────┐   │
│  │           RetrofitClient (HTTP)               │   │
│  └──────────────────┬──────────────────────────┘   │
└─────────────────────┼───────────────────────────────┘
                      │ HTTP
                      ▼
┌─────────────────────────────────────────────────────┐
│              Server (192.168.219.100:8010)            │
│  ┌───────────┐  ┌───────────┐  ┌────────────────┐   │
│  │ /sms/     │  │ /mms/     │  │ /outbox/       │   │
│  │ receive/  │  │ receive/  │  │ (대기 문자)    │   │
│  └─────┬─────┘  └─────┬─────┘  └────────┬───────┘   │
│        └───────┬───────┘               │             │
│                ▼                       │             │
│  ┌─────────────────────┐               │             │
│  │   MySQL sms2 DB      │◄──────────────┘             │
│  │  received_sms_message │                            │
│  │  sms_outbox           │                            │
│  │  sms_phone_device     │                            │
│  └───────────────────────┘                            │
└───────────────────────────────────────────────────────┘
```

---

## 3. SMS 수신 경로

수신된 문자는 3가지 경로로 캡처됩니다. **중복 전송 방지**를 위해 `ProcessedMessages` 싱글턴이 60초 TTL로 관리합니다.

### 3-1. SmsNotificationListener (1순위 - 활성)

**파일**: `SmsNotificationListener.kt`
**방식**: `NotificationListenerService` — Android 알림 시스템에서 SMS 앱의 알림을 가로챔

**장점**: RCS, Samsung Messages 등 모든 메시징 앱 알림 캡처 가능
**단점**: 알림 접근 권한 필요 (사용자가 설정에서 수동 활성화)

**동작 흐름**:
1. 알림 수신 → 패키지 필터링 (`SMS_PACKAGES`)
2. 중복 알림 제거 (key + 1초 이내)
3. 시스템 요약 알림 무시 ("메시지", 빈 title, "메시지 보기")
4. 발신자 번호 추출 (5단계 폴백 체인)
5. MMS 판별 → 이미지 추출 → 서버 전송
6. SMS/LMS → 서버 전송 (30초 내 3회 재시도)

**SMS_PACKAGES** (인식하는 메시징 앱):
```kotlin
setOf(
    "com.samsung.android.messaging",   // Samsung Messages
    "com.android.mms",                 // AOSP 기본 메시지
    "com.google.android.apps.messaging" // Google Messages
)
```

**발신자 번호 추출 폴백 체인**:
| 순위 | 방법 | 설명 |
|------|------|------|
| 1순위 | MessagingStyle sender | 알림의 MessagingStyle에서 sender 추출 (숫자 포함 시) |
| 2순위 | EXTRA_PEOPLE tel: URI | 알림의 people 필드에서 tel: URI 추출 |
| 3순위 | content://sms/inbox | SMS DB에서 body 첫 30자 매칭으로 address 추출 |
| 4순위 | content://mms addr | MMS DB에서 최근 2분 내 addr(type=137) 조회 |
| 5순위 | 알림 title | 최후 수단 (연락처 이름일 수 있음) |

### 3-2. SmsContentObserver (2순위 - v1.0.11부터 활성)

**파일**: `SmsContentObserver.kt`
**방식**: `ContentObserver` — Android SMS/MMS DB 변경 감시

**감시 URI**:
- `content://sms` — 전체 SMS (발신 ID 건너뛰기용)
- `content://sms/inbox` — 수신 SMS
- `content://mms` — MMS
- `content://mms/inbox` — 수신 MMS

**동작 흐름**:
1. DB 변경 감지 → `checkNewSms()` + `checkNewMms()` (MMS는 5초 딜레이)
2. `lastSmsId` / `lastMmsId` 이후 새 레코드만 처리
3. `ProcessedMessages.isDuplicate()` → NotificationListener가 이미 처리했으면 스킵
4. `sendAll` 설정 OR `shouldSaveSms()` → 서버 전송

**SMS/LMS 분류 기준**: `body.toByteArray(Charsets.UTF_8).size > 80` → LMS, 아니면 SMS

### 3-3. SmsReceiver (3순위 - 비활성)

**파일**: `SmsReceiver.kt`
**방식**: `BroadcastReceiver` — `SMS_RECEIVED` 인텐트 수신
**상태**: AndroidManifest에서 `android:enabled="false"`

기본 SMS 앱이 아니면 최신 Android에서 수신이 제한되므로 비활성화 상태 유지.

### 전화번호 정규화

모든 수신 경로에서 동일한 정규화 적용:
```kotlin
fun normalizePhoneNumber(number: String): String {
    return number
        .replace("\u2068", "")  // bidi isolate 제거
        .replace("\u2069", "")
        .replace("+82", "0")
        .replace("-", "")
        .replace(" ", "")
        .trim()
}
```

### 중복 방지 (ProcessedMessages)

**파일**: `ProcessedMessages.kt`

```kotlin
object ProcessedMessages {
    private val recentKeys = LinkedHashMap<String, Long>(200, 0.75f, true)

    @Synchronized
    fun isDuplicate(sender: String, messagePrefix: String): Boolean {
        val key = "$sender:${messagePrefix.take(30)}"
        val now = System.currentTimeMillis()
        recentKeys.entries.removeIf { now - it.value > 60_000 }  // 60초 TTL
        return if (recentKeys.containsKey(key)) true
        else { recentKeys[key] = now; false }
    }
}
```

- **키**: `"발신자번호:메시지앞30자"`
- **TTL**: 60초
- **용도**: NotificationListener가 먼저 처리 → ContentObserver가 같은 메시지 감지 시 스킵

### csphone / checkphone 매핑 규칙

**서버 DB 스키마**:
- `csphone_number` = **발신자** (문자를 보낸 사람/번호)
- `checkphone_number` = **수신자** (내 핸드폰 번호)

```kotlin
// 올바른 매핑 (v1.0.10+ 에서 수정됨)
val request = ReceivedSMSRequest(
    csphone_number = sender,      // 발신자
    checkphone_number = myPhone,   // 내 폰 (수신자)
    message = body,
    receive_time = System.currentTimeMillis()
)
```

> **주의**: v1.0.8 이전에는 csphone/checkphone이 뒤바뀌어 있었음 (버그). v1.0.10에서 NotificationListener 수정, v1.0.11에서 ContentObserver/SmsReceiver 수정 완료.

---

## 4. SMS 발송 (Outbox)

**파일**: `SmsSenderService.kt`

### Foreground Service
- `START_STICKY` — 시스템에 의해 종료되어도 자동 재시작
- WakeLock + WifiLock 획득 → 절전 모드에서도 동작
- Notification 채널: `sms_sender_channel` (IMPORTANCE_LOW)

### 폴링 주기
```kotlin
private const val POLL_INTERVAL = 5000L  // 5초
```

### 발송 흐름
1. `RetrofitClient.getApi().getOutgoingSms()` → 대기 문자 목록 조회
2. 빈 메시지 → 즉시 `failed` 보고, 건너뜀
3. `SmsManager.divideMessage()` → 장문 분할
4. 단문: `sendTextMessage()` / 장문: `sendMultipartTextMessage()`
5. 성공/실패 → `reportSmsResult()` → 서버에 상태 보고

### SmsManager 안전 획득 (Android 12+)
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
    if (subId != INVALID_SUBSCRIPTION_ID)
        getSystemService(SmsManager::class.java)?.createForSubscriptionId(subId)
    else
        getSystemService(SmsManager::class.java)
} else {
    SmsManager.getDefault()
}
```

---

## 5. 서버 통신 (API)

### 기본 설정

| 항목 | 값 |
|------|------|
| 기본 호스트 | 192.168.219.100 |
| 기본 포트 | 8010 |
| 기본 URL | http://192.168.219.100:8010/ |
| HTTP 클라이언트 | OkHttp 4.12.0 + Retrofit 2.9.0 |

### API 엔드포인트

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/cpc/sms/receive/` | 수신 SMS/LMS 서버 전송 |
| POST | `/api/cpc/mms/receive/` | 수신 MMS 서버 전송 (multipart: images) |
| GET | `/api/cpc/sms/outbox/` | 발송 대기 문자 조회 |
| POST | `/api/cpc/sms/outbox/{id}/result/` | 발송 결과 보고 |
| POST | `/api/cpc/sms/devices/heartbeat/` | 디바이스 하트비트 |
| POST | `/api/cpc/sms/devices/change-number/` | 전화번호 변경 |
| GET | `/api/settings/` | CS 전화번호 설정 조회 |
| POST | `/api/settings/add/` | 설정 추가 |
| PUT | `/api/settings/{id}/` | 설정 수정 |
| DELETE | `/api/settings/{id}/delete/` | 설정 삭제 |

### 요청/응답 모델

**ReceivedSMSRequest** (SMS 수신 전송):
```json
{
    "csphone_number": "01012345678",      // 발신자
    "checkphone_number": "01075502753",   // 수신자 (내 폰)
    "message": "문자 내용",
    "receive_time": 1713081600000         // Unix timestamp (ms)
}
```

**MMS 수신 전송** (Multipart):
```
csphone_number: "01012345678"        (text/plain)
checkphone_number: "01075502753"     (text/plain)
message: "MMS 텍스트"               (text/plain)
receive_time: "1713081600000"        (text/plain)
images: [binary file data]          (image/jpeg, image/png, ...)
```

**HeartbeatRequest**:
```json
{
    "phone_number": "01075502753",
    "app_version": "1.0.12"
}
```

**SmsSendResult** (발송 결과):
```json
{
    "id": 123,
    "status": "sent",           // "sent" | "failed"
    "error_message": null,
    "sent_at": "2026-04-14T13:00:00"
}
```

---

## 6. 핵심 컴포넌트 상세

### 6-1. HeartbeatManager

**파일**: `HeartbeatManager.kt`

- 30초 간격으로 서버에 디바이스 상태 보고
- `SmsNotificationListener.onListenerConnected()`에서 시작
- 전화번호 + 앱 버전 전송
- 서버 응답: `{ ok: true, connected: true, server_time: "..." }`

### 6-2. BootReceiver

**파일**: `BootReceiver.kt`

- `BOOT_COMPLETED` 인텐트 수신
- `SmsSenderService.start(context)` 호출 → Foreground Service 재시작
- 폰 재부팅 후 자동으로 SMS 수신/발송 서비스 복구

### 6-3. MainActivity

**파일**: `MainActivity.kt`

**초기화 순서**:
1. 권한 요청 (SMS, 전화, 알림 등)
2. 알림 리스너 권한 확인 → 설정 화면으로 안내
3. 배터리 최적화 제외 요청
4. Samsung 배터리 설정 가이드 (최초 1회)
5. `SmsSenderService.start()` — Foreground Service 시작
6. `AppUpdater.startPeriodicCheck()` — 자동 업데이트 시작

**UI**:
- 서버 연결 상태 표시 (초록/빨강/주황 원형 인디케이터)
- 전화번호 / API URL 표시
- 설정 화면 진입 버튼

### 6-4. SettingsScreen

**파일**: `SettingsScreen.kt`

4개 섹션:
1. **내 전화번호** — 전화번호 입력/변경 (서버에 변경 알림)
2. **문자 전송 설정** — "모두 보내기" 토글 (v1.0.12 추가)
3. **API 서버** — IP 주소/포트 설정
4. **앱 버전** — 현재 버전 표시 + 수동 업데이트 확인/설치

---

## 7. 설정 및 SharedPreferences

### "settings" (Context.MODE_PRIVATE)

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `my_phone_number` | String | "unknown" | 내 핸드폰 번호 (정규화된 11자리) |
| `send_all_sms` | Boolean | false | 모든 수신 문자 서버 전송 여부 |
| `samsung_battery_guide_shown` | Boolean | false | Samsung 배터리 가이드 표시 여부 |

### "app_prefs" (Prefs 객체)

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `api_host` | String | "192.168.219.100" | API 서버 IP |
| `api_port` | String | "8010" | API 서버 포트 |
| `base_url` | String | (host:port에서 생성) | API 기본 URL |

---

## 8. 자동 업데이트 시스템

**파일**: `AppUpdater.kt`

### 동작 흐름

```
1시간 주기 → GitHub API 호출
  → https://api.github.com/repos/betona1/smsApp/releases/latest
  → 최신 버전 비교 (semantic versioning)
  → 새 버전 발견 시:
    → APK 에셋 URL 추출
    → DownloadManager로 다운로드
    → BroadcastReceiver로 완료 감지
    → FileProvider로 설치 Intent 실행
```

### 버전 비교

```kotlin
fun isNewerVersion(current: String, latest: String): Boolean {
    val c = current.split(".").map { it.toIntOrNull() ?: 0 }
    val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(c.size, l.size)) {
        val cv = c.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    return false
}
```

### GitHub Release 요구사항
- Release tag = 버전명 (예: `v1.0.12`)
- APK 파일을 Release Asset으로 첨부
- 파일명에 `.apk` 확장자 필수

### 서명 일관성

> **중요**: 자동 업데이트가 작동하려면 모든 릴리즈가 **같은 키스토어**로 서명되어야 합니다.
> v1.0.12부터 `app/release-keystore.jks` 사용 (alias: `smsapp`, password: `smsapp2026`)

---

## 9. APK 빌드 및 배포

### 빌드 환경 요구사항

| 항목 | 버전 |
|------|------|
| JDK | 17 |
| Android SDK | Platform 35, Build-Tools 35.0.0 |
| Gradle | 8.4 (wrapper 포함) |
| Kotlin | Android Plugin 내장 |

### 서버 빌드 환경 (이미 설정됨)

```
/home/joacham/android-build/
├── jdk/                          # OpenJDK 17
└── android-sdk/
    ├── cmdline-tools/latest/     # SDK Manager
    ├── platforms/android-35/     # Platform 35
    ├── build-tools/35.0.0/       # Build Tools
    └── platform-tools/           # ADB 등
```

### 빌드 명령어

```bash
# 프로젝트 디렉토리로 이동
cd /tmp/smsApp_src

# local.properties 설정 (최초 1회)
echo "sdk.dir=/home/joacham/android-build/android-sdk" > local.properties

# gradle.properties의 java.home을 로컬 경로로 변경
sed -i 's|org.gradle.java.home=.*|org.gradle.java.home=/home/joacham/android-build/jdk|' gradle.properties

# 릴리즈 APK 빌드
JAVA_HOME=/home/joacham/android-build/jdk \
ANDROID_HOME=/home/joacham/android-build/android-sdk \
./gradlew assembleRelease

# 결과물 위치
ls -la app/build/outputs/apk/release/app-release.apk
```

### GitHub Release 배포

```bash
# APK 복사
cp app/build/outputs/apk/release/app-release.apk app-v1.0.XX.apk

# 릴리즈 생성
gh release create v1.0.XX app-v1.0.XX.apk --title "v1.0.XX" --notes "릴리즈 노트"
```

### ADB 직접 설치 (자동 업데이트 불가 시)

```bash
ADB=/home/joacham/android-build/android-sdk/platform-tools/adb

# 연결된 디바이스 확인
$ADB devices

# 기존 앱 삭제 (서명 불일치 시 필수)
$ADB uninstall com.example.smsreceiverapp

# 설치
$ADB install app-v1.0.XX.apk
```

---

## 10. 버전 이력 및 변경사항

### v1.0.12 (2026-04-14) — 현재 버전

**새 기능**:
- **모두 보내기 설정 UI 추가** (`SettingsScreen.kt`)
  - 설정 화면에 토글 스위치 추가
  - ON: 수신된 모든 문자를 서버로 전송 (번호 제한 없음)
  - OFF: 등록된 번호(csphonesetting)의 문자만 전송
  - SharedPreferences 키: `send_all_sms`

**인프라**:
- 릴리즈 키스토어 생성 (`app/release-keystore.jks`)
- `build.gradle.kts`에 signingConfigs 추가
- 서버에 Android 빌드 환경 구축 (`/home/joacham/android-build/`)

**서명 변경 주의**: v1.0.12부터 새 키스토어로 서명. 기존 앱 삭제 후 재설치 필요.

---

### v1.0.11 (2026-04-13)

**SMS 수신 강화**:
- **ContentObserver 재활성화** (`SmsSenderService.kt`)
  - `registerContentObserver()` 주석 해제
  - SMS/MMS DB 직접 모니터링으로 NotificationListener 보완
  - RCS 등으로 알림이 안 잡히는 경우에도 DB에 기록된 메시지 캡처

- **csphone/checkphone 매핑 수정** (`SmsContentObserver.kt`, `SmsReceiver.kt`)
  - v1.0.8에서 잘못 매핑된 필드를 ContentObserver/BroadcastReceiver에서도 수정
  - `csphone_number = sender` (발신자), `checkphone_number = myPhone` (수신자)

- **중복 전송 방지 시스템 추가** (`ProcessedMessages.kt`)
  - 새 파일 생성 — 60초 TTL의 LinkedHashMap 기반 싱글턴
  - NotificationListener에서 처리한 메시지를 ContentObserver에서 다시 보내지 않음
  - 키: `"발신자:메시지앞30자"`, 동기화: `@Synchronized`

**수정 파일**:
| 파일 | 변경 |
|------|------|
| `ProcessedMessages.kt` | 신규 생성 |
| `SmsContentObserver.kt` | csphone/checkphone 수정 + 중복 체크 추가 |
| `SmsNotificationListener.kt` | ProcessedMessages 마킹 추가 |
| `SmsReceiver.kt` | csphone/checkphone 수정 |
| `SmsSenderService.kt` | ContentObserver 활성화 |

---

### v1.0.10 (2026-04-11)

**버그 수정**:
- **NotificationListener csphone/checkphone 매핑 수정**
  - `sendSmsToServer()`: csphone=sender, checkphone=myPhone (올바른 매핑)
  - `sendMmsMultipart()` / `sendMmsFromContentProvider()` 동일 수정

- **발신자 번호 추출 개선**
  - MessagingStyle → EXTRA_PEOPLE → content://sms/inbox → content://mms addr → title 5단계 폴백
  - Samsung Messages에서 연락처 이름 대신 번호 추출
  - bidi isolate 문자(U+2068/U+2069) 제거

- **SmsManager NPE 수정** (Android 12+)
  - `SubscriptionManager.getDefaultSmsSubscriptionId()` 사용
  - null 안전 처리 + 예외 캐치

- **중복 발송 방지**
  - SmsSenderService만 outbox 폴링 담당 (NotificationListener에서 제거)

---

### v1.0.9 이전

- v1.0.8: 초기 안정 버전 (csphone/checkphone 뒤바뀜 버그 포함)
- v1.0.7: MMS 이미지 전송 기능 추가
- v1.0.6: 긴급 패치
- 이전: 기본 SMS 수신/발송 기능

---

## 11. 파일 구조

```
smsApp/
├── app/
│   ├── release-keystore.jks              # 릴리즈 서명 키스토어
│   ├── build.gradle.kts                  # 빌드 설정 (signingConfigs 포함)
│   └── src/main/
│       ├── AndroidManifest.xml           # 권한, 서비스, 리시버 등록
│       ├── res/                          # 리소스 (아이콘, 레이아웃, 설정)
│       └── java/com/example/smsreceiverapp/
│           │
│           │── ── SMS 수신 ──
│           ├── SmsNotificationListener.kt  # [1순위] 알림 기반 SMS/MMS 캡처
│           ├── SmsContentObserver.kt       # [2순위] DB 변경 감시 (백업)
│           ├── SmsReceiver.kt              # [3순위] BroadcastReceiver (비활성)
│           ├── MmsReceiver.kt              # MMS WAP_PUSH 리시버
│           ├── ProcessedMessages.kt        # 중복 전송 방지 (60초 TTL)
│           │
│           │── ── SMS 발송 ──
│           ├── SmsSenderService.kt         # Foreground Service (5초 폴링)
│           ├── SmsSenderWorker.kt          # WorkManager 워커 (미사용)
│           │
│           │── ── 서버 통신 ──
│           ├── ApiService.kt               # Retrofit API 인터페이스
│           ├── RetrofitClient.kt           # HTTP 클라이언트 싱글턴
│           ├── ReceivedSMSRequest.kt       # 수신 SMS 요청 모델
│           ├── ReceivedSMSMessageRequest.kt # 수신 메시지 요청 (대체)
│           ├── OutgoingSms.kt              # 발송 SMS/결과 모델
│           │
│           │── ── 디바이스 관리 ──
│           ├── HeartbeatManager.kt         # 30초 하트비트 관리
│           ├── Heartbeat.kt               # 하트비트 데이터 모델
│           ├── BootReceiver.kt            # 부팅 시 자동 시작
│           ├── AppUpdater.kt              # GitHub 자동 업데이트
│           │
│           │── ── UI ──
│           ├── MainActivity.kt            # 메인 화면 + 권한 요청
│           ├── SettingsScreen.kt          # 설정 화면
│           ├── PhoneNumberScreen.kt       # 전화번호 입력 화면
│           ├── SplashScreen.kt            # 스플래시 화면
│           ├── SplashViewModel.kt         # 스플래시 ViewModel
│           │
│           │── ── 설정/유틸 ──
│           ├── Prefs.kt                   # SharedPreferences 래퍼
│           ├── AppLog.kt                  # 앱 로그 유틸
│           ├── GlobalPhoneSettings.kt     # 전역 전화 설정
│           ├── PhoneSetting.kt            # 전화 설정 모델
│           ├── CSPhoneSettingRequest.kt   # CS 설정 요청 모델
│           ├── CSPhoneSettingResponse.kt  # CS 설정 응답 모델
│           ├── CSPhoneSettingExtensions.kt # CS 설정 확장 함수
│           │
│           └── ui/theme/
│               ├── Color.kt              # 색상 테마
│               ├── Theme.kt              # Material3 테마
│               ├── Type.kt               # 타이포그래피
│               ├── PhoneNumberComposeTheme.kt
│               ├── extensions.kt         # UI 확장 함수
│               └── db/
│                   ├── AppDatabase.kt    # Room DB (로컬 캐시)
│                   ├── CSPhoneDao.kt     # DAO
│                   └── CSPhoneEntity.kt  # 엔티티
│
├── gradle/wrapper/
│   └── gradle-wrapper.properties         # Gradle 8.4
├── gradle.properties                     # JVM 옵션, AndroidX 설정
├── build.gradle.kts                      # 루트 빌드 설정
├── settings.gradle.kts                   # 프로젝트 설정
└── docs/
    └── TECHNICAL_GUIDE.md                # 이 문서
```

---

## 12. 트러블슈팅

### 문자가 서버에 안 올라오는 경우

1. **알림 접근 권한 확인**
   - 설정 → 알림 → 알림 접근 → "SMS Receiver App" 활성화 확인
   - NotificationListener가 비활성이면 1순위 수신 경로가 작동하지 않음

2. **"모두 보내기" 설정 확인**
   - 앱 설정 → "모두 보내기" 토글 ON
   - OFF 상태면 등록된 번호의 문자만 전송됨

3. **RCS/채팅+ 문제**
   - 일부 사업자(11번가, 쿠팡 등)가 RCS로 문자 발송
   - RCS는 `content://sms/inbox`에 기록되지 않을 수 있음
   - NotificationListener가 캡처하지만, SMS_PACKAGES에 포함된 앱이어야 함
   - 해결: 메시지 앱의 "채팅+" 또는 RCS 기능 끄기

4. **디바이스 하트비트 확인**
   - DB: `SELECT * FROM sms_phone_device ORDER BY last_poll DESC`
   - `last_poll`이 30초 이상 지난 경우 → 앱이 중단된 상태

### 자동 업데이트가 안 되는 경우

1. **서명 불일치**: 기존 앱과 새 APK의 서명이 다르면 설치 실패
   - 해결: ADB로 기존 앱 삭제 후 재설치
2. **GitHub API 접근 실패**: 네트워크 문제 또는 API rate limit
3. **설치 권한**: "알 수 없는 앱 설치" 허용 필요

### 문자 발송이 안 되는 경우

1. **SEND_SMS 권한 확인**
2. **기본 SMS 앱 설정**: SmsManager가 null 반환 → 기본 SMS 앱으로 설정 필요
3. **서버 outbox 확인**: `SELECT * FROM sms_outbox WHERE status='pending'`
4. **빈 메시지**: 빈 문자는 자동 `failed` 처리됨

### ADB 연결 문제

1. **unauthorized**: 폰 화면에서 "USB 디버깅 허용" 팝업 확인
2. **device not found**: USB 케이블 재연결, 개발자 옵션에서 USB 디버깅 ON
3. **INSTALL_FAILED_UPDATE_INCOMPATIBLE**: 서명 불일치 → `adb uninstall` 먼저 실행

---

## 서버 DB 테이블 참조

### sms_phone_device (디바이스 관리)
```sql
id, phone_number, alias, is_active, last_poll, last_ip,
app_version, server_url, api_receive_path, api_outbox_path,
api_key, poll_interval, config_json, memo, created_at, updated_at
```

### received_sms_message (수신 문자)
```sql
id, csphone_number, checkphone_number, message,
receive_time, received_at, setting_id
```

### sms_outbox (발송 대기)
```sql
id, phone_number, message, sender_phone, template_id,
status, error_message, created_at, sent_at
```
