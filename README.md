# SMS Receiver App

Android 휴대폰을 SMS/MMS 게이트웨이로 사용하는 앱입니다.
수신한 문자를 서버로 자동 전송하고, 서버의 요청을 받아 문자를 발송합니다.

## 주요 기능

| 기능 | 설명 |
|------|------|
| SMS/LMS 수신 → 서버 전송 | 수신한 문자를 자동으로 서버 API에 전송 |
| MMS 수신 → 서버 전송 | 이미지 포함 멀티미디어 메시지를 multipart로 서버에 전송 |
| RCS(채팅+) 수신 지원 | NotificationListener로 RCS 문자도 감지 |
| 서버 → SMS 발송 | 서버 outbox의 발송 요청을 5초마다 폴링하여 실제 SMS 발송 |
| 발송 결과 보고 | 발송 성공/실패를 서버에 자동 보고 |
| 백그라운드 상시 실행 | Foreground Service + WakeLock + 부팅 시 자동 시작 |

## 아키텍처

```
┌─────────────────────────────────────────────────┐
│                  Android App                     │
│                                                  │
│  ┌──────────────────────┐  ┌──────────────────┐ │
│  │ SmsNotificationListener│  │  SmsSenderService │ │
│  │ (알림 리스너)          │  │  (Foreground)     │ │
│  │                        │  │                   │ │
│  │ - RCS/SMS/MMS 수신감지 │  │ - WakeLock 유지   │ │
│  │ - 5초 폴링 (발송)     │  │ - WiFiLock 유지   │ │
│  │ - 서버 전송           │  │                   │ │
│  └──────────┬─────────────┘  └───────────────────┘ │
│             │                                     │
│  ┌──────────┴─────────────┐                       │
│  │    RetrofitClient       │                       │
│  │  (API 통신 싱글톤)      │                       │
│  └──────────┬─────────────┘                       │
└─────────────┼─────────────────────────────────────┘
              │ HTTP
              ▼
┌─────────────────────────────────────────────────┐
│              Django Server                       │
│         http://192.168.219.100:8010              │
│                                                  │
│  - 수신 문자 저장 (received_sms_message)         │
│  - 발송 대기 관리 (sms_outbox)                   │
│  - MMS 이미지 저장 (sms_message_image)           │
│  - 문자 템플릿 관리 (sms_template)               │
│  - 텔레그램/PC 알림 (서버에서 처리)              │
└─────────────────────────────────────────────────┘
```

## 서버 API 명세

### 1. SMS 수신 전송

수신한 SMS/LMS를 서버에 저장합니다.

```
POST /api/cpc/sms/receive/
Content-Type: application/json
```

**요청:**
```json
{
    "csphone_number": "01075502753",
    "checkphone_number": "01027533200",
    "message": "수신된 문자 내용",
    "receive_time": 1775731234567
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| csphone_number | string | 수신 핸드폰 번호 (내 번호) |
| checkphone_number | string | 발신자 번호 |
| message | string | 문자 내용 |
| receive_time | long | 수신 시각 (Unix timestamp ms) |

**응답:** `201 Created`

---

### 2. MMS 수신 전송 (이미지 포함)

수신한 MMS를 이미지 파일과 함께 서버에 전송합니다.

```
POST /api/cpc/mms/receive/
Content-Type: multipart/form-data
```

**요청:**
| 필드 | 타입 | 설명 |
|------|------|------|
| csphone_number | text | 수신 핸드폰 번호 |
| checkphone_number | text | 발신자 번호 |
| message | text | 텍스트 내용 |
| receive_time | text | 수신 시각 (ms) |
| images | file[] | 이미지 파일 (여러 장 가능) |

**응답:** `201 Created`

**이미지 저장 경로 (서버):**
```
media/sms/received/YYYY-MM-DD/{uuid}.jpg
```

---

### 3. 발송 대기 조회 (앱 → 서버 폴링)

앱이 5초마다 호출하여 발송할 문자가 있는지 확인합니다.

```
GET /api/cpc/sms/outbox/
```

**응답:**
```json
{
    "items": [
        {
            "id": 1,
            "phone_number": "01027533200",
            "message": "발송할 메시지",
            "sender_phone": "01075502753",
            "template_id": null,
            "status": "pending",
            "error_message": null,
            "created_at": "2026-04-09 18:07:53.745507",
            "sent_at": null
        }
    ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | int | 발송 요청 ID |
| phone_number | string | 수신자 전화번호 (반드시 번호로 입력) |
| message | string | 발송 메시지 (빈 문자열 불가) |
| sender_phone | string | 발신자 번호 |
| template_id | int? | 템플릿 ID |
| status | string | pending / sent / failed |

---

### 4. 발송 결과 보고

문자 발송 후 결과를 서버에 보고합니다.

```
POST /api/cpc/sms/outbox/{id}/result/
Content-Type: application/json
```

**요청:**
```json
{
    "id": 1,
    "status": "sent",
    "error_message": null,
    "sent_at": "2026-04-09T18:08:00"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| id | int | 발송 요청 ID |
| status | string | `sent` 또는 `failed` |
| error_message | string? | 실패 시 에러 메시지 |
| sent_at | string | 발송 시각 (ISO 8601) |

---

### 5. 설정 조회

등록된 폰 설정 목록을 조회합니다.

```
GET /api/settings/
```

**응답:**
```json
[
    {
        "id": 1,
        "csphone_number": "01075502753",
        "checkphone_number": "15990110",
        "alias": "11번가(7550)",
        "is_save_to_db": 1,
        "is_notify_pc": 1,
        "is_notify_telegram": 1,
        "created_at": "2025-07-06T19:16:31.350606",
        "is_admin": 1,
        "payment_date": null
    }
]
```

> 참고: `is_save_to_db`, `is_notify_pc` 등 Boolean 필드가 서버에서 `0`/`1`(숫자)로 반환됩니다.
> 앱에서 Gson 커스텀 디시리얼라이저로 자동 변환합니다.

---

## DB 테이블 구조

### received_sms_message (수신 문자)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | bigint PK | 자동증가 |
| csphone_number | varchar(20) | 수신 핸드폰 번호 |
| checkphone_number | varchar(20) | 발신자 번호 |
| message | longtext | 문자 내용 |
| receive_time | datetime | 수신 시각 |
| received_at | datetime | 서버 저장 시각 |
| setting_id | bigint FK | 설정 ID |

### sms_outbox (발송 대기)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | bigint PK | 자동증가 |
| phone_number | varchar(20) | 수신자 번호 |
| message | text | 발송 메시지 |
| sender_phone | varchar(20) | 발신자 번호 |
| template_id | bigint | 템플릿 ID |
| status | varchar(10) | pending/sent/failed |
| error_message | varchar(500) | 에러 메시지 |
| created_at | datetime | 생성 시각 |
| sent_at | datetime | 발송 시각 |

### sms_message_image (MMS 이미지)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | bigint PK | 자동증가 |
| message_id | bigint FK | 수신 메시지 ID |
| filename | varchar | 원본 파일명 |
| filepath | varchar | 서버 저장 경로 |
| content_type | varchar | MIME 타입 (image/jpeg 등) |
| size | int | 파일 크기 (bytes) |
| created_at | datetime | 저장 시각 |

### sms_template (문자 템플릿)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | bigint PK | 자동증가 |
| category | varchar(50) | 카테고리 |
| title | varchar(100) | 제목 |
| message | text | 메시지 내용 |
| display_order | int | 정렬 순서 |
| is_active | tinyint | 활성 여부 |

### csphonesetting (폰 설정)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | bigint PK | 자동증가 |
| csphone_number | varchar(20) | 내 번호 |
| checkphone_number | varchar(20) | 감시 대상 번호 |
| alias | varchar | 이름/별칭 |
| is_save_to_db | tinyint | DB 저장 여부 |
| is_notify_pc | tinyint | PC 알림 |
| is_notify_telegram | tinyint | 텔레그램 알림 |
| is_admin | tinyint | 관리자 여부 |
| payment_date | varchar | 결제일 |

---

## 문자 분류 기준

| 분류 | 조건 |
|------|------|
| SMS | 텍스트 80바이트 이하 |
| LMS | 텍스트 80바이트 초과 |
| MMS | 이미지 첨부 포함 |

---

## 설치 및 설정

### 1. 빌드
```bash
./gradlew assembleDebug
```

### 2. 설치
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 초기 설정

앱 실행 시 순서대로 설정합니다:

1. **SMS/MMS 권한 허용** - 자동 팝업
2. **알림 접근 권한** - 자동으로 설정 화면 이동 → SmsReceiverApp 켜기
3. **배터리 최적화 무시** - 자동 팝업 → 허용
4. **삼성 기기 추가 설정** - 설정 → 배터리 → SmsReceiverApp → "제한 없음"
5. **전화번호 입력** - 앱 첫 화면에서 내 번호 입력

### 4. 서버 주소 변경

앱 메인 화면 → 설정(톱니바퀴) → API 서버 IP/포트 입력 → 저장

---

## 앱 화면 구성

| 화면 | 설명 |
|------|------|
| 메인 화면 | 서버 연결 상태 (초록 ON / 빨강 OFF), 서버 주소, 내 번호 표시 |
| 설정 화면 | API 서버 IP 주소 / 포트 변경 |
| 번호 입력 | 최초 1회 내 핸드폰 번호 입력 |

---

## 주요 기술

| 기술 | 용도 |
|------|------|
| Kotlin + Jetpack Compose | UI |
| Retrofit2 + Gson | REST API 통신 |
| Room Database | 로컬 DB |
| NotificationListenerService | RCS/SMS/MMS 알림 감지 |
| Foreground Service | 백그라운드 유지 |
| WakeLock / WifiLock | CPU/WiFi 유지 |
| BroadcastReceiver | SMS/MMS 수신, 부팅 감지 |

---

## 제한사항

- **MMS 발송 불가**: Android 정책상 시스템 기본 문자앱만 MMS 발송 가능
- **RCS 이미지**: 알림에서 썸네일 추출 (원본 아님)
- **삼성 배터리 최적화**: 수동 설정 필요 (앱에서 안내)
- **서버 outbox 주의**: `phone_number`는 반드시 전화번호 (이름 불가), `message`는 빈 문자열 불가

---

## 서버 환경

| 항목 | 값 |
|------|-----|
| 서버 IP | 192.168.219.100 |
| API 포트 | 8010 |
| DB (MySQL) | 192.168.219.100:3307 |
| DB명 | sms2 |
| 프레임워크 | Django |
