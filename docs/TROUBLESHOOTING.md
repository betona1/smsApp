# 트러블슈팅 (SMS Gateway)

실전에서 겪은 문제와 해결법 모음.

---

## 1. 폰이 자꾸 죽는다 (특히 월요일/주말 후)

**증상:** 서버에서 폰이 오프라인, 문자 발송/수신 안 됨. 앱 열면 다시 살아남.

**원인:** 삼성 자체 배터리 절전(미사용 앱 절전)이 유휴 시 앱을 강제종료. 앱이 완전히 죽으면 heartbeat도 멈춰서 자동복구(워치독)도 무력화됨. 주말=이틀 방치=가장 잘 죽음.

**해결 (근본):** [INSTALL_APP.md 5단계](INSTALL_APP.md) — 배터리 "제한 없음" + 미사용앱절전 예외 + 자동실행 허용. 3곳 모두 설정.

**해결 (임시):** 폰에서 앱 한번 열기 → 즉시 폴링 재개.

**감시:** 서버 크론 `monitor_sms_devices`가 5분마다 폴링 체크 → 끊긴 폰을 텔레그램 알림.
```bash
*/5 * * * * cd <server>/backend && DJANGO_SETTINGS_MODULE=config.settings python3 manage.py monitor_sms_devices
python3 manage.py monitor_sms_devices --force-test   # 현재 상태 강제 발송(테스트)
```

---

## 2. 문자가 서버엔 "발송성공"인데 실제로 안 감

**원인 (구버전):** `sendTextMessage(...,null,null)` fire-and-forget — 발송 API 호출 즉시 무조건 "sent" 보고. 통신사 차단/무선꺼짐/한도초과로 실제 안 가도 성공으로 찍힘.

**해결:** v1.0.17에서 **sentIntent로 실제 전송결과 확인** 후 sent/failed 보고하도록 수정. 실패는 사유(통신사 차단/무선꺼짐 등) 포함.

**"폰 보낸함에 안 보인다"는 정상:** 이 앱은 기본 문자앱이 아니라서 `sendTextMessage`가 폰 메시지앱 "보낸함"에 안 남김. 상대방은 정상 수신함.

---

## 3. 특정 번호로만 문자가 안 감

**증상:** 한 번호로만 계속 `RESULT_ERROR_GENERIC_FAILURE`, 다른 번호는 정상.

**원인:** 그 번호 자체 문제 — 결번(번호 오타), 수신 차단, 문자 불가 회선.

**해결:** 수신자 번호 재확인 (주문서 등). 짧은 문자로도 실패하면 번호 문제 확정.

---

## 4. 특정 폰이 발송 자체가 안 됨 (회선 문제)

**증상:** 한 폰은 모든 발송이 `GENERIC_FAILURE`, 다른 폰은 정상.

**원인:** 그 폰의 SIM/요금제가 SMS 발송 불가 (알뜰폰 문자 미지원 등).

**해결:** 서버 `sms_phone_device.config_json`에 `{"can_send": false}` → 발신폰 목록에서 제외 (수신은 계속). 회선 정상화 후 `true`로 복구.

---

## 5. 인증번호(OTP)만 수신 안 됨

**원인:** 삼성 One UI가 인증번호 문자를 보안상 "메시지 보기"로 가린 알림으로 띄우면, NotificationListener가 내용을 못 읽고 버림.

**해결:** v1.0.15에서 가려진 알림 감지 시 **content://sms/inbox 최신문자를 직접 읽어** 전송하도록 수정. (폰 설정에서 알림 민감내용 숨김 해제해도 됨)

**확인:** OTP는 SIM이 등록된 폰으로 옴. 그 폰이 살아있는지 먼저 확인.

---

## 6. Play Protect가 앱을 삭제함

**증상:** 설치했던 앱이 며칠 뒤 사라짐 (폰 폴링 며칠째 끊김 + 앱 없음).

**원인:** `com.example` 계열 패키지 + SMS/연락처 권한 = 구글 스파이웨어 시그니처 오탐 → 자동삭제.

**해결:**
- 토글 OFF (INSTALL_APP.md 1단계) — 단, 구글이 재활성화함
- **ADB 시스템레벨 영구차단** (관리자, 개발용폰):
  ```
  adb shell settings put global package_verifier_enable 0
  adb shell settings put global package_verifier_user_consent -1
  adb shell settings put global verifier_verify_adb_installs 0
  adb shell settings put global upload_apk_enable 0
  adb shell settings put global package_verifier_setting 0
  ```
- **개인폰엔 설치 금지** (근본). 전용 공기계만.

---

## 7. 자동 업데이트가 "악성코드 의심"으로 막힘

**원인:** 앱 자동업데이트(APK 다운→설치)도 Play스토어 Play Protect 검사에 막힘. ADB 시스템 verifier를 꺼도 이 경로는 별도.

**해결:** ADB 직접 설치가 확실 (Play Protect 안 거침):
```
adb install -r -g -d app-release.apk
```

---

## 8. ADB로 폰이 안 잡힘

**증상:** `adb devices`에 아무것도 안 뜸.

**체크:**
- **데이터 케이블인지** (충전전용 케이블 많음)
- 폰 USB 모드: 알림 내려 "USB 제어" → **"파일 전송"** 또는 디버깅 (충전만 X)
- 개발자옵션 → **USB 디버깅 ON**
- `unauthorized`면 폰에서 **"USB 디버깅 허용"** 팝업 → 허용 (항상 허용 체크)
- 저사양폰은 절전으로 USB가 자꾸 끊김 → 화면 켜둔 채 케이블 교체
- `adb kill-server && adb start-server`로 재인식
