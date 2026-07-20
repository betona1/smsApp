# SMS Gateway 제품화 로드맵

> 목적: 내부용 SMS 게이트웨이(핸드폰→서버 문자 중계) 앱을 **직접 배포 가능한 제품**으로 정리.
> 구독 사용자에게 유지보수 제공하는 서버형 서비스로 발전.
> 작성: 2026-07-20 / 상태: **계획 수립 (실행 대기)**

---

## 0. 핵심 전제 — 왜 플레이스토어가 아닌가

구글 플레이 정책: **SMS 권한(READ/SEND/RECEIVE_SMS) 앱은 "기본 문자앱(default handler)"으로 등록돼야만** 출시 허용.
문자 포워딩/게이트웨이는 기본 문자앱이 아님 → **정책상 스토어 출시 불가 (반려 확정)**.

- 동일 분야 성숙 오픈소스 전부 **직접 APK 배포**만 함:
  - [SMSGate](https://github.com/capcom6/android-sms-gateway) (Apache-2.0, 깃헙 릴리스 APK)
  - [textbee](https://github.com/vernu/textbee), [httpSMS](https://github.com/NdoleStudio/httpsms), [SMSsync](http://smssync.ushahidi.com/)
- **결론: 직접 APK 배포 + Play Protect 예외설정 매뉴얼**이 유일한 현실적 경로이자 업계 표준.

---

## 1. 현재 앱 진단 (출시 차단 요소)

| # | 문제 | 위치 | 조치 |
|---|------|------|------|
| 1 | 패키지명 `com.example.smsreceiverapp` (예약어, 비전문적) | 35개 .kt 파일 | → `com.betona.smsgateway`로 리네임 |
| 2 | 서버주소 하드코딩 (192.168.219.100 / 106.247.220.118) | `Prefs.kt` | → 기본값 비우고 설정화면 입력 필수화 |
| 3 | **버그**: 기본 URL이 폐기된 `8010` 포트 | `Prefs.kt:9` | → 제거 (8379 통일) |
| 4 | 자동업데이트 깃헙 저장소 하드코딩 (`betona1/smsApp`) | `AppUpdater.kt:63` | → 설정화면에서 업데이트 소스 지정 가능하게 |
| 5 | 수신 3중 구현 (SmsReceiver 비활성/ContentObserver/NotificationListener) | 다수 | → 역할 정리, 죽은 코드 제거 |
| 6 | 발송 2중 (SmsSenderService + SmsSenderWorker) | 2파일 | → 주 경로(Service) 확정, Worker는 백업 명시 |
| 7 | 크래시 방지 미흡 (fire-and-forget 등) | 일부 | → v1.0.17에서 발송검증 수정 완료, 추가 점검 |

---

## 2. 목표 레포 구조 (ai100에서 분리)

```
smsgateway/                    ← 독립 레포 (신규)
├── android/                   ← 현 smsApp 이식 + 패키지 리네임
│   └── (설정화면에서 서버주소/업데이트소스 입력, 하드코딩 0)
├── server/                    ← Django 백엔드 (현 gmarket_cpc의 SMS 부분 추출)
│   ├── docker-compose.yml     ← 원클릭 배포 (Django + MariaDB + Redis)
│   ├── Dockerfile
│   ├── .env.example
│   └── install.sh             ← 비-Docker 사용자용
├── docs/
│   ├── INSTALL_APP.md         ← 앱 설치 + Play Protect 예외 + 배터리 설정
│   ├── INSTALL_SERVER.md      ← 서버 구축 (Docker/스크립트)
│   ├── API.md                 ← 엔드포인트 명세 (바이브코딩용)
│   └── TROUBLESHOOTING.md     ← 폰 죽음/OTP/발송실패 대응
├── LICENSE
└── README.md
```

---

## 3. 배포 전략 (확정 기본값)

| 항목 | 결정 |
|------|------|
| 앱 배포 | **직접 APK** (깃헙 릴리스 + 자체 다운로드 페이지). F-Droid는 추후 옵션 |
| 패키지명 | **com.betona.smsgateway** (변경 원하면 재지정) |
| 서버 배포 | **Docker 원클릭** (docker-compose) + install.sh 병행 |
| 라이선스/구독 | **v2로 유예** — 지금은 "동작하는 제품" 완성 우선. 이후 서버 라이선스키 검증 추가 |
| 서버 버전 | 사용자가 docker-compose.yml / .env 수정해 커스텀(바이브코딩) |

---

## 4. 단계별 실행 계획

### Phase 1 — 앱 정리 (내부 사용 가능하게) ★최우선
- [ ] `com.example.smsreceiverapp` → `com.betona.smsgateway` 리네임 (35파일 + manifest + gradle)
- [ ] `Prefs.kt` 하드코딩 제거, 기본값 공란화, 8010 버그 제거
- [ ] `AppUpdater.kt` 업데이트 소스 설정화
- [ ] 죽은/중복 코드 정리 (SmsReceiver 등)
- [ ] 크래시 재점검 + 로깅 정비
- [ ] 재빌드 → 3대 폰 배포 → 실동작 검증

### Phase 2 — 레포 분리 + 서버 패키징
- [ ] `smsgateway` 신규 레포 생성, android/ 이식
- [ ] gmarket_cpc에서 SMS 서버코드 추출 → server/
- [ ] docker-compose + Dockerfile + install.sh 작성
- [ ] .env.example (서버주소/DB/텔레그램/포트 설정)

### Phase 3 — 문서화
- [ ] 앱 설치 매뉴얼 (Play Protect 예외 스크린샷 포함)
- [ ] 서버 구축 매뉴얼 (Docker/스크립트)
- [ ] API 명세 (수신/발송/heartbeat 엔드포인트 — 바이브코딩용)
- [ ] 트러블슈팅 (폰 죽음감지 알림, OTP, 발송실패 진단)

### Phase 4 (추후) — 구독/라이선스
- [ ] 서버 라이선스키 발급/검증
- [ ] 만료 시 기능제한 로직
- [ ] 고객별 격리 배포

---

## 5. 되돌리기 어려운 작업 (사용자 승인 후 실행)

- 패키지 리네임 (브랜드명 확정 필요)
- 레포 분리 (신규 깃헙 레포 생성)

→ 이 두 가지는 **브랜드명 확정 시 즉시 실행 가능**. 나머지(문서/버그수정)는 선진행.
