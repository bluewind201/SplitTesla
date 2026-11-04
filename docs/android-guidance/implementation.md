# Android 안내 안전성 강화 구현 기록

기준 HEAD: `6f210508e367836611ac893145777a49a81182fc` (2026-11-04 작업).
인계 분석 `analysis.md`는 원본 그대로 보존했다. 저장소와 상위 경로에서 AGENTS.md/CLAUDE.md를 찾았으나 없었다.
이번 단계는 PM의 단계 분리 승인(msg_8ab57e1ac086)에 따라 안전 결함 수정과 회귀 검증에 집중한다. 단일 TTS 및 제한속도 프리셋은 후속 작업이며 전체 강화 목표에서 제외된 것이 아니다.

## 실제 재현과 분석 정정

기존 CameraTest/NaviTest 본문, SpeedCameraService → CameraAlerter, NaviService → NaviHub → RouteTracker, AppViewModel → CarFeed, Speaker/DriveAssistant 호출을 읽었다.
HEAD의 core를 별도 임시 경로(`/tmp/splittesla-baseline-ebtk3hq5/core`)에 복원하고 재현 테스트 세 개만 추가해 실행했다. 결과는 **3개 중 2개 실패**였다.

- `estimatedArrivalIsNeverConfirmed`: 기존 `advance()`는 실제 GPS 없이 남은 거리만으로 arrived=true. 실패로 재현.
- `unverifiedSectionEndpointsNeverClaimAnAverage`: C3 START/C4 END라는 순서만으로 평균을 확정. 실패로 재현.
- `anEmptyRouteFilterDoesNotFallBackToEveryCamera`: core의 emptySet 필터는 원래 정상. 전체 폴백은 app의 `takeIf { ... && it.isNotEmpty() }`가 빈 집합을 null로 바꾸는 문제였다.

분석의 “두 번 거절 후 점프 수용”, “START 다음 END 짝”, “25m 클러스터 bothWays 합성”, “DR 위치로 구간 거리 누적/통과 확정” 의사코드는 채택하지 않았다.

## 결함별 변경

| 결함 | 구현과 증거 |
|---|---|
| hasSpeed 누락 / NaN / 무한대 / 음수 속도 | `SpeedEvidence`는 유한한 0–250km/h 실측값만 선택한다. app은 hasSpeed를 확인한다. 실측값이 없으면 CameraAlerter가 승인된 실제 위치 사이 거리로 접근 여부를 판단한다. `measuredSpeedKmh`를 별도로 전달해 파생 속도로 overLimit 또는 “속도를 줄이세요”를 만들지 않는다. `missingOrNanSpeedStillWarnsUsingPositionWithoutClaimingSpeeding` 검증. |
| 오래된 BLE 속도 | 차량 속도 원본 샘플 시각 `CarFeed.speedAt` 추가(기본 at으로 기존 호출 호환). AppViewModel은 Commander의 실제 speed 수신 시각 또는 Tesla navUpdatedAt을 전달한다. UI 상태 갱신 시각으로 오래된 속도를 새롭게 만들지 않는다. 미래/3초 초과 샘플은 사용하지 않는다. |
| 단발 위치 튐 / 시각 역전 | 순수 Kotlin `FixGate` 공용 사용. 위경도 범위·유한성·정확도 60m 상한, 중복/역행 시각, 실제 이동량 검증. app은 Location.elapsedRealtimeNanos 기반 샘플 나이를 3초로 제한한다. CameraAlerter는 gate 승인 전 거리·카메라 통과를 변경하지 않는다. |
| 튐 이후 재획득 | 정상 궤적은 최대 75m/s + 정확도 여유(최대20m); 시간 예산은 최대5초이며 첫 의심 점프가 발생하면 당시 허용거리를 고정하므로 단순한 시간 경과로 같은300m 점프가 정상 전진으로 승격되지 않는다. 별도 후보는 샘플 간격 0.5–2.5초, 최대55m/s+15m, 10m 이상 움직인 인접 벡터의 방향 차이 60도 이내, 최소5초/4개 및 마지막 승인 이후10초 조건을 모두 만족해야 재획득한다. 서로 무관한 튐·40m 왕복 후보는 계속 거절한다. 재획득은 derived speed=null/continuous=false로 이전 거리 증거를 끊는다. 긴 무수신 후에는 정지한 일관된 후보도 허용하므로 회복에 5초 이상 걸릴 수 있다. 의심 점프 뒤 정상 고속 이동이 고정된 허용거리 밖에 있으면 보수적으로 후보 재획득을 기다리므로 안내 복구가 늦어질 수 있다. NaviHub와 RouteTracker가 각각 위치 증거를 검증하므로 안내 중 장기 무수신 후에는 후보 확인 지연이 추가될 수 있다. 모든 수치는 본 앱 설계값이다. |
| 가까운 좌표에서 먼 경로 세그먼트로 진행 점프 | timestamp를 받는 RouteTracker.update에서 경로상 전진량도 별도 검증한다(75m/s, 최대5초+40m). 먼 투영 후보도 첫 의심 시 허용거리를 고정하고 5초/10초의 일관된 시각·이동 조건 없이 수용하지 않는다. 터널 유예/재획득 시 전체 geometry로 실제 위치를 다시 탐색한다. 기존 timestamp 없는 호출은 호환용이며 app의 실제 호출은 timestamp를 전달한다. |
| 오래된 BLE로 무한 추정항법 | 마지막 실제 승인 fix 이후 3–30초 동안만, 3초 이내 실제 차량 속도만 적분한다. 과거 GPS 속도 hold는 사용하지 않는다. 루프 지연이 2초를 넘으면 그 간격을 적분하지 않는다. `RouteProgress.estimated`로 출처를 구분하며 추정 상태는 arrived=false다. BLE 단절·신선도 만료·30초 상한 후 진행이 멈춘다. |
| 거짓 도착/15초 뒤 무조건 종료 | 경로상 remaining<30m 외에 실제 목적지까지40m 이내, lateral≤30m, 정확도≤30m(제공 시)인 실제 fix 두 번 필요. timestamp가 있으면 적어도0.5초 간격. 불량/역전 fix·목적지 이탈·DR는 확정을 철회한다. 종료 Job을 취소하고 15초 뒤에도 동일 tracker/현재 arrived/최근3초 실제 fix를 다시 확인한다. |
| 빈 경로 카메라가 전체 경고로 폴백 | 안내 중 정상 경로에서는 emptySet 그대로 전달. 로딩 중/새 경로 전환 시에도 빈 목록은 전체 경고로 풀지 않는다. 새 route에 이전 카메라 목록을 남기지 않는다. 안내 안 함/실제 offRoute일 때 기존 주변 공공카메라 동작은 유지한다. |
| 구간 평균 오염 / 다른 END가 종료 | 카메라 소실/정지/필터 변경만으로 통과시키지 않고, 실제 이동 중 근접(직전40m 미만) 후 카메라가 뒤로 넘어갔으며 현재80m 미만인 경우만 처리한다. `verifiedSectionEnds`는 독립적으로 입증된 방향별 ID 짝만 받는다. 생산 데이터에는 그런 증거가 없어 기본 emptyMap이며 평균·안전속도·남은 구간거리 수치는 null이다. END 실제 통과 알림은 “평균 속도 미확인”; 기존 Double 필드는 호환 유지하고 `averageVerified=false`로 사용을 금지한다. 확실한 짝이 있는 경우라도 거절/무수신/재획득을 한 번 겪으면 해당 구간 평균을 무효로 유지한다. 다른 END는 검증된 active 구간을 종료하지 않는다. START로 시작한 미확인 상태는 불명 END로 종료 확정하지 않으며 거리 상한 또는20분에 정리한다. |
| 실제 거리 문구 | Final의 고정100m 제거. core `CameraAnnouncement.spokenText`가 실제 거리를 기존 distanceWords 규칙으로 반올림(42m→40미터)한다. 미확인 평균에는 숫자를 읽지 않는다. |
| 목적지/재탐색 중복 발화 | 목적지 guide와 turn follow-up에서 목적지 문구를 제외하고 확정 도착 문장을 한 번만 낸다. 자동 재탐색은 이탈 에피소드당 시작 문구 한 번, 성공 문구 없음. 실패는15/30/60초 간격, in-flight 중복 요청 차단, 오래된 route 응답 적용 방지. 수동 재탐색/경유지 요청의 기존 성공 문구는 유지한다. |
| GPS 표시 | provider enabled만으로 gpsOk=true로 만들지 않는다. 카메라 서비스는1초 주기로 승인 fix의3초 만료를 반영하고 평균 측정 신뢰도를 무효화한다. 내비 역시 stale/거절 fix를 gpsOk=false로 표시한다. |

`verifiedSectionEnds`가 테스트에서만 주입된 이유: 공공 데이터의 순서·제한속도·sectionLength·근접 좌표·경로 위 투영만으로 같은 구간이나 방향이라고 증명할 수 없다. 평균이 사라지는 것은 카메라 기능 전체 억제가 아니며 일반 공공카메라 접근/최종/시작/종료 지점 알림은 계속 작동한다. 실제 시스템 측정 평균과 GPS 평균이 같다고 보장하지 않는다.

## 보존 정책과 한계

- `CameraData.mergeSameSpot`, CSV/저장 포맷, RouteCameras 방향 필터는 수정하지 않았다. **왼쪽7m 초과 단방향 레코드 제외**라는 기존 휴리스틱과 기존 격자 병합 기본값 유지.
- 방향 코드1/2는 실제 방위각/차로를 알려주지 않는다. 7m 규칙은 넓은 도로·중앙 좌표에서 우리 방향 카메라도 놓칠 수 있으며, 격자 경계에서 동일 지점 병합도 불완전하다. “반대편”을 확정하는 정책으로 해석하면 안 된다. 25m bothWays 합성이나 공공 카메라 전체 비활성화는 하지 않았다.
- DR는 길안내 진행 표시/턴 예고만 지원한다. 카메라 서비스에 가상 위치를 전달하지 않으므로 **터널 무수신 중 새 카메라 경고는 여전히 나오지 않을 수 있다**. 가상 통과·구간 평균·목적지 도착을 확정하지 않는다. 30초가 지나면 낮은 신뢰도 추정을 고정하고 GPS 불량 표시를 유지한다.
- 연속적이지만 잘못된 GPS 편향, 목적지와 겹치는 다른 도로, GPS로 입증할 수 없는 구간 짝은 완전히 해결할 수 없다. FixGate 수치/재획득 지연은 실차 확인이 필요하다.
- 기존 BLE 기능/Compose UI/사용자 설정과 외부 공식 API는 유지. 의존성·유료 API·독점 DB·비공개 API·웹 코드 이식 없음. 커밋/푸시하지 않았다.

## 검증 환경과 결과

- 시스템 `java`/JAVA_HOME은 없었다. 기존 설치 `/home/orca/project_folder/SplitTesla/tools/jdk-21`(Temurin21.0.13+9)을 JAVA_HOME으로 사용했다. JDK/SDK를 새로 설치하지 않았다.
- 초기 `./gradlew :core:test --offline`는 Kotlin JVM 플러그인2.0.21 캐시가 없어 실패했다. 실제 core는 included build이므로 SDK와 분리한 `./gradlew -p core test`로 검증했다. Gradle wrapper8.14.3 및 선언된 기존 의존성만 받았다.
- HEAD 재현 명령: `JAVA_HOME=... ./gradlew -p /tmp/splittesla-baseline-ebtk3hq5/core test --tests '*NaviTest.estimatedArrivalIsNeverConfirmed' --tests '*CameraTest.unverifiedSectionEndpointsNeverClaimAnAverage' --tests '*CameraTest.anEmptyRouteFilterDoesNotFallBackToEveryCamera'` → 3개 중2개 실패.
- 수정본 전체 core JVM 테스트가 한 차례82개 통과했다. 추가 경계 보완 뒤 최종 `JAVA_HOME=... ./gradlew -p core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'` → **87개 테스트, 실패0/오류0/건너뜀0, BUILD SUCCESSFUL (1분35초)**. `core/build/reports/tests/test/index.html`, `core/build/test-results/test/TEST-*.xml`에 결과가 있으며 `/tmp/splittesla-core-final.log`에 실행 로그를 남겼다.
- `JAVA_HOME=... ./gradlew :app:assembleDebug -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'` → **실패**. 정확한 오류: `Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'` / `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file .../local.properties`. 기존 SDK/android.jar를 찾지 못했다. **app Kotlin 컴파일, APK 생성, TTS·서비스·Compose·실차 실행은 미검증**이다.
- 재현/수정 단위 테스트 외에 Android 서비스 타이머·Location 입력·자동 재탐색 API 흐름은 코드 검토와 실차 체크리스트만 작성했으며 런타임 통과로 주장하지 않는다.

## 후속 단계: 단일 TTS와 제한속도 프리셋

이번 단계에서 **미구현**. 목적지/재탐색의 같은 의미 발화 억제와 **세 독립 엔진 간 동시 발화 미해결**을 구분해야 한다.

1. Speaker를 직접 생성하는 SpeedCameraService/NaviService/DriveAssistant의 생명주기를 함께 설계한다. 현재 각 소유자가 shutdown하며 Speaker.shutdown은 내부적으로 stop을 호출한다. 저장소의 DriveAssistant는 typed command를 처리하고 Speaker.speak/shutdown을 호출하며, 별도 SpeechRecognizer/RecognizerIntent 또는 음성인식용 Speaker.stop 호출은 확인되지 않았다. 후속 음성인식 연동에서는 준비/취소 시 stop의 영향을 따로 검증해야 한다. 공유 엔진에서는 한 소유자의 stop/shutdown이 카메라/턴을 끊거나 다른 서비스가 쓰는 TTS를 닫으면 안 된다. acquisition/release 참조 관리, 소유자별 취소, 마지막 참조에서만 shutdown이 필요하다.
2. 단일 직렬 큐에서 카메라 최종/일반 카메라/턴/재탐색/어시스턴트 우선순위, 문장 만료, 중복 key, 초기화 전 여러 문장 보존을 검증한다. 무조건 QUEUE_FLUSH로 턴/카메라를 서로 자르지 않도록 정책을 먼저 정한다. 오디오 focus 회수와 stop callback의 pending count 경쟁도 가짜 엔진 테스트/기기 QA 대상이다.
3. 음성인식 시 자기 TTS가 마이크에 들어가는 문제와 카메라 긴급 발화가 인식을 취소해야 하는지 PM과 결정한다. 서비스 종료/재시작, Activity 재생성, 엔진 초기화 실패, 언어 데이터 없음, 블루투스 오디오 및 음악 ducking을 확인한다.
4. 거리 프리셋은 기존 속도기반12초(200–600m)+100m를 **기본값**으로 유지한다. 제한속도 기반 프리셋을 새 선택 설정/리소스/UI로 추가하고 기존 prefs를 마이그레이션 없이 읽는다. 80 이상1000/500m, 50 이상600/300m, 미만300m는 참고 후보이며 본 앱 정확도 검증이 필요하다. 다단계 창 건너뜀/중복/최초 근거리 감지/설정 변경/공공 좌표 오차 회귀 테스트를 추가한다. 과속 버퍼는 별도 정책이며 이번에 도입하지 않았다.

## 변경 파일

- app: `AppViewModel.kt`, `camera/SpeedCameraService.kt`, `navi/NaviHub.kt`
- core: `navi/FixGate.kt`(신규), `navi/RouteTracker.kt`, `navi/GuidanceAnnouncer.kt`, `camera/CameraAlerter.kt`, `camera/CameraAnnouncement.kt`(신규)
- 테스트: `navi/FixGateTest.kt`(신규), `navi/NaviTest.kt`, `camera/CameraTest.kt`
- 문서: 이 파일(신규), `docs/실차테스트.md` 추가. `analysis.md`는 작업 전부터 untracked였으며 수정하지 않았다.

## 독립 QA 반례 후 보완 (두 번째 구현 단계)

앞 절은 최초87개 테스트 단계의 기록이다. 아래 보완이 기존의5초 상한/허용거리 고정/전역 최근접 투영/미확인 active 구간/도착 종료 문구에 대한 **현재 구현**을 대체한다. QA 소유 `core/src/test/kotlin/com/splittesla/core/qa/*`, `qa.md`, `qa-evidence/*`는 수정하지 않았다.

### 재현과 변경

유효한 1차 근거는 QA 소유 `qa.md` 및 `qa-evidence/round1-results.txt`다. 이 보고서에 최초9개 기대 실패, D6의 정책 한계 L1 재분류, 현재8개 결함이 기록되어 있다. 개발자가 공유 트리에서 실행한 `dev-evidence/qa-before-fixes.log`도9개 실패였지만 고정 소스 해시가 없으므로 참고 로그로만 보관하고 최종 판정 근거로 쓰지 않는다.

| QA 항목 | 처리 |
|---|---|
| D1:6초 간격80km/h 영구 거절 | 정상 수신 간격을 최대8초까지 실제 이동량(75m/s+최대20m 여유)으로 검증한다. 의심 후보의 간격도0.5–8초까지 받아 낮은 빈도에서도 일관된 궤적을 재획득한다. 4/6/8초,90/248km/h와 장기 단절 후6초 간격 복구를 개발 테스트에 포함했다.8초 초과 무수신은 여전히 후보 확인을 거친다. |
| D9:튐 후 짧은 무수신 복귀 지연 | 직전 두 실제 승인 fix의 이동 벡터로 최대8초의 위치를 예측한다. 새 fix가 예측에서20m+3m/s×시간(상한45m) 이내면 거절 횟수나 누적 시간 예산 대신 기존 궤적과의 일치로 복구한다. 고정된 도달 범위 밖에서 예측 모델로 복구하면 reacquired=true/continuous=false/derivedKmh=null로 이전 거리 근거를 끊는다. 원래의 고정 도달 범위 안으로 돌아온 경우 기존 continuous 의미를 유지하되 카메라 평균은 앞선 거절 시 이미 무효화되어 복구하지 않는다. 방향이 실제로 바뀌면 예측과 맞지 않을 수 있어 일반 후보 재획득을 기다린다. |
| D4:터널 복귀 시 평행 복귀 경로로2km 점프 | 전역 최근접 투영의 전진량이 실제 좌표 이동으로 뒷받침되지 않으면 현재 along 주변(뒤400m/앞150m)의 타당한 투영을 우선한다. 정상적인 동일 경로 전진은 주변 탐색 창 끝에 가두지 않는다. 전역 최근접 경로가 몇m 더 가깝다는 이유로 먼 평행 세그먼트를 고르지 않는다. 전진량도 직전 실제 좌표에서 이동한 직선거리×1.5+40m와 대조하고, 같은 먼 투영을5초 반복했다는 이유로 승격하던 로직을 제거했다. 실제 경로를 돌아가지 않은 정지 좌표로 루프 도착을 확정하던 개발 테스트는 더 엄격한 미확정 기대값으로 수정했다. |
| D5:거절 fix가 estimated 해제 | raw fix 또는 투영 거절은 estimated를 유지한다. 실제로 허용된 경로 위치를 반영할 때만 estimated=false다. NaviHub도 estimated 진행을 gpsOk로 표시하지 않는다. Android는 자체 gate 승인 증거를 tracker에 전달해 장기 복구 후보를 두 번 기다리지 않는다. |
| D2:2초 간격 START 누락 | 직전40m 조건 대신 연속 승인된 실제 GPS 선분의 카메라 횡단을 판정한다. 선분까지의 횡거리≤기존 sideM(기본30m), 카메라 전후 통과 방향을 확인하며 실제 카메라에 정확히 닿은 샘플도 포함한다. 한 카메라는 한 번만 통과 처리한다. DR 좌표는 입력하지 않으며 미확인 짝에서 평균을 만들지 않는다. |
| D3:미확인 END 후 상태 잔존 | 독립적으로 검증된 짝이 없는 START는 지점 알림만 내고 측정 중인 section 상태를 생성하지 않는다. END 지점은 평균 미확인 알림을 유지한다. 다른 END로 검증된 active 구간을 끝내지 않는 정책은 그대로다. |
| D7:철회될 도착에서 종료 약속 발화 | 도착 알림은 “목적지 … 부근에 도착했습니다”만 말하고 “안내를 마칩니다”를 제거한다.15초 뒤 최근 실제 fix/현재 arrived 재확인 후 종료하는 로직과 이탈 시 Job 취소는 유지한다. 목적지 통과 후 안내가 남아 있는데 종료했다고 발화하지 않는다. |
| D8:나쁜 첫 fix | 승인 이력이 첫 한 점뿐이고 정확도가20m 이상인 약한 기준점이면, 정확도≤10m인 새 후보가0.5–8초 간격/이동량/방향 조건을 만족하는3점·최소2초 궤적을 보일 때 교체한다. QA의30m→5m 시나리오는3초에 복구한다. 단순히 세 번 거절됐다는 이유가 아니라 **독립적인 정확도 개선 + 일관된 시각/이동**을 요구한다. 첫 점과 후보의 정확도가 같거나 둘 다 잘못된 경우 어느 쪽이 진짜인지 위치만으로 알 수 없으므로 조기 복구를 보장하지 않는다. 일반5초/4점 및 마지막 승인 후10초 재획득 규칙은 이 모호한 경우에 유지한다. |
| D6:종점45m 옆 주차 | PM 결정(msg_f3d2918a0821)에 따라 반경을 확대하지 않는다. 자동 도착은 미확정이며 기존 `NaviPanel.kt`의 “안내 종료” 버튼으로 명시적으로 끝낸다. 정차+사용자 도착 확인은 정책 선택이 필요한 후속 UX 후보이며 이번에 새 확인 UI를 만들지 않았다. 이 최초 QA 반례의 자동 도착 기대값은 의도적으로 충족시키지 않는다. QA 소유자가 D6를 반례 파일에서 제거한 이후 스냅샷에는8개 반례만 존재하며, 이를 우리가 단언을 바꿔 통과시킨 것으로 세지 않는다. |

### 검증 범위와 주의

- 공유 core/build에서 동시에 클래스가 사라져 `VoiceAction$None.class`의 `NoSuchFileException` / Kotlin internal compiler error를 겪었다. 이를 로직 통과/실패로 해석하지 않고, 이후 개발 검증은 소스·QA 테스트·설정을 별도 임시 스냅샷으로 복사해 독립 build 디렉터리에서 실행한다.
- QA 테스트의 단언·환경 gate·소유 문서는 변경하지 않았다. D6를 제외하기 위해 QA 파일이나 테스트를 수정하지 않았다. 수정 전 실행은9개 실패였고, QA 소유 파일이8개로 변경된 뒤의 최종 실행은 그 정확한 개수로 기록한다.
- 단일 TTS·제한속도 프리셋은 계속 후속 범위다. 위치 파생 속도로 과속을 단정하거나, 미확인 START/END의 짝을 만들거나, 방향/병합 기본 정책을 바꾸지 않았다.
- Android SDK 부재와 앱 Kotlin/서비스/TTS/실차 미검증 한계는 동일하며, 최종 재시도 결과와 JVM 결과를 아래에 기록한다.


### QA 근거 정정과 최종 고정 스냅샷

- QA 문서가 작성 완료된 후 `docs/android-guidance/qa.md`와 `qa-evidence/round1-results.txt`를 읽었다. 앞서 읽었던 `qa-evidence/qa-repro-summary.txt`는 QA가 동시 수정/빌드로 오염된 폐기본이라고 확인해 `discarded-concurrent-edit/`로 이동한 자료다. **폐기본의 숫자·결과를 유효한 검증 근거로 사용하지 않는다.** 문서 생성 전에는 테스트 파일과 임시 요약을 참고했으며, 이 경로 정정은 PM 메시지 msg_9d4a111e3c57을 반영했다.
- 최종 고정 소스는 `/tmp/splittesla-final-review-hcoa8owb`다. core 전체(설정·프로토·구현·개발 테스트·최신 QA 소유 테스트 두 파일)와 변경된 app Kotlin3개를 복사했다. `dev-evidence/final-source-sha256.json`은 파일별 SHA-256 manifest다. Android SDK가 없어 app은 이 고정 소스에서도 컴파일 검증을 했다고 주장하지 않는다.
- 실행 A: `JAVA_HOME=/home/orca/project_folder/SplitTesla/tools/jdk-21 env -u QA_REPRO ./gradlew -p /tmp/splittesla-final-review-hcoa8owb/core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`. 일반 스위트에서8개 결함 재현은 skipped이며 정상 통과로 세지 않는다.
- 실행 B: 같은 고정 소스에서 `QA_REPRO=1 JAVA_HOME=... ./gradlew -p /tmp/splittesla-final-review-hcoa8owb/core test --tests 'com.splittesla.core.qa.*' --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`. 테스트 필터를 바꿔 A의 결과를 up-to-date로 재사용하지 않고 QA 정상/정책 테스트와8개 재현을 실제로 실행한다.
- A/B 결과는 각 XML을 따로 보존해 덮어쓰지 않고, 로그·집계·실행 전후 소스 해시 비교를 `dev-evidence/`에 남긴다. 이전 중간 실행의123개/124개 숫자는 최종 소스와 다르므로 최종 판정에 사용하지 않는다.
- Android 재시도: `./gradlew :app:assembleDebug --no-daemon`도 **실패**(1분36초). `SDK location not found` / `Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'`. `dev-evidence/android-build-after-fixes.log`에 원문을 보관했다. APK·app Kotlin·서비스 수명주기·실차는 계속 미검증이다.
- 이번 보완의 변경 파일: core `navi/FixGate.kt`, `navi/RouteTracker.kt`, `navi/GuidanceAnnouncer.kt`, `camera/CameraAlerter.kt`; app `navi/NaviHub.kt`; 개발 테스트 `navi/GuidanceRecoveryTest.kt`(신규), `navi/NaviTest.kt`, `camera/CameraTest.kt`; 이 구현 문서와 `dev-evidence/*`. QA 소유 문서·테스트·근거는 수정하지 않았다. 이전 단계 변경 파일 목록도 앞 절에 유지했다.


### 최종 결과와 구현 동결

- **일반 clean/test**: 총126개, 정상 통과118개, 결함 재현 건너뜀8개, 실패0/오류0. BUILD SUCCESSFUL,2분57초. `dev-evidence/normal-clean-test.log`, `normal-test-summary.json`, `normal-test-xml/*`에 보존했다.
- **QA_REPRO=1 QA 필터 실행**: 총29개 전부 실제 통과, 건너뜀0/실패0/오류0. QA 정상·정책21개 + D1/D2/D3/D4/D5/D7/D8/D9 결함 재현8개다. BUILD SUCCESSFUL,29초이며 test task가 실제 실행됐다. `qa-repro-test.log`, `qa-repro-summary.json`, `qa-repro-xml/*`에 보존했다.
- D6/L1은 “45m 옆 주차 자동 도착”을 구현 완료로 세지 않는다. QA 소유 정책 테스트와 개발 정책 테스트에서 기존 자동 도착 미확정이 유지됨을 확인했으며 수동 종료는 app 정적 확인이다.
- 실행 후 스냅샷과 작업 트리의 manifest 대상 소스 해시가 모두 일치한다. 최종 상태는 `dev-evidence/final-validation-summary.json`에 기록했다. 이 시점부터 **구현과 개발 테스트를 더 변경하지 않고** PM 및 독립 QA에 인계한다.

동결 기록 시각(UTC): `2026-11-03T19:32:19.130182+00:00`. manifest SHA-256: `6c2940d6651c31865d20728020a0b12393e3cba13dd35240d4803fa6361337e5`.

문서 보존 점검: 이 작업자는 `analysis.md`를 수정하지 않았다. 최종 점검에서 현재 SHA-256(`dc6dd007bbfa2811c6f3fd6984e2c29d27c191cfefd002f9bf5d22b2a840c77a`)이1차 작업 당시 기록(`ce8a539f487f083beb1b148f3494d65ec0139dd1a8f0c936ffa36137cf3e652f`)과 달라진 것을 발견해 PM에 외부 변경 가능성을 알렸다. 다른 작업자의 문서를 되돌리지 않았으며 core/app 구현 동결 manifest에는 영향이 없다.

## R3 헤어핀 복귀 / R1 저빈도 재획득 보완 (세 번째 구현 단계)

PM의 새 작업(task_8d98cc1aa3cc)으로 직전 동결을 아래 수정 범위에서 해제했다. QA 소유 `AndroidGuidanceQaFinalProbeTest.kt` 및 최신 `qa.md`를 읽었고 QA 파일·단언·문서는 수정하지 않았다. 앞 절의6c2940d6… 동결본과126개/29개 결과는 **이전 소스** 기록이다.

### 고정된 수정 전 재현

`/tmp/splittesla-r3-before-9htui2s8/core`에서 `QA_REPRO=1 ... test --tests '*AndroidGuidanceQaFinalProbeTest*'`를 실행했다.3개 모두 실패했다. `dev-evidence/round3/before-source-sha256.json`, `before-test.log`, `before-xml/*`, `before-summary.json`이 소스·원문 증거다.

- R3:31개 복귀 fix 동안 firstOk=null, along이483.333m까지 후진. QA와 같은 증상이다.
- R1:10초 간격19개 중1개만 승인.
- R2:동일한 정확도5m를 주장한 나쁜 최초 fix 이후 실제 궤적 승인까지10초.3초 복구 단언 실패.

### 변경과 안전 조건

1. **실제 위치 궤적의 방위 증거**: `FixGate.Accepted.courseDeg`는 실제 승인 fix 또는 시간·이동량·방향 검증을 통과한 재획득 후보 궤적에서 계산한다. 최근0.5–8초 안에 최소 max(10m, 두 fix 정확도 상한×2) 이동한 표본만 사용한다. 짧은 정차 지터는 방위로 확정하지 않는다. 새 재획득에서는 오래된 기준점과의 순변위 대신 **새 후보 궤적**의 방위를 사용한다. 차량 측정 속도나 실제 과속 근거로 쓰지 않는다.
2. **R3 경로 선택**: `RouteGeometry.project`가 유효한 이동 방위에서75도 넘게 벗어나는 세그먼트를 후보에서 제외한다. 따라서 남진하는 복귀 궤적을 가깝다는 이유로 북진 출발 구간에 고정하지 않는다. 헤어핀처럼 경로를 돈 거리가 직선거리보다 긴 경우에는, 시간 검증된 재획득·방위 일치·정확도 범위 내 global 투영·local의 유효 후보 부재·마지막 실제 경로 적용 후 시간의 최대30초/75m/s 전진 한도를 함께 만족해야 돌아온 구간을 수용한다. **같은 투영을 여러 번 받았다는 횟수만으로는 수용하지 않는다.**
3. **D4 유지**: 북진하는 실제 궤적은2km 뒤의 남진 평행 세그먼트와 방위가 맞지 않는다. 기존의 직선 이동량/현재 주변 투영 제한도 유지한다. 재획득이라고 모든 경로·후진을 무조건 허용하던 분기를 제거했다.
4. **누적 후진 방지**:30m 지터 여유를 매 fix마다 새로 주지 않고 마지막 실제 확정 진행의 상한에 고정한다. DR 뒤400m 보정은 기존 실제 진행 상한보다30m 이상 뒤로 넘어가지 않는 범위에서만 허용한다. 최초 약한 기준점을 독립적인 정확도 개선으로 교체하는 경우에만 명시적인 `anchorReset` 예외를 둔다. 정확도5m를 똑같이 주장하는 R2에는 이 예외가 적용되지 않는다.
5. **불확실 상태와 이탈 노출**: raw fix 또는 경로 투영이 아직 입증되지 않으면 `RouteProgress.positionUncertain=true`로 이전 진행을 보류하고 도착을 철회한다. 유효 정확도에서 연속3회 매칭하지 못하면 offRoute가 된다. 성공적으로 위치를 반영할 때만 불확실 상태와 misses를 지운다. NaviHub는 이 상태를 GPS 양호로 표시하지 않고 턴 발화·경유지 통과·lastGuideIndex 확정을 보류한다. core GuidanceAnnouncer도 불확실 진행에서 말하지 않는다. app 런타임 표시는 SDK 부재로 실행하지 않았으며 이 연결은 정적 검토다.
6. **R1 개선**: 후보 간격을 최대30초까지 시간·변위·방향 조건으로 검증해10초 간격 fix도 궤적을 재획득한다. 재획득 후 최근 실제 두 표본으로30초 이내 위치 예측을 검증하되,8초를 넘는 경우에는 기존 표본 간격≥8초이며 새 간격이 기존의0.5–1.5배인 **검증된 저빈도 수신 패턴**이어야 한다. 따라서1Hz 뒤 갑작스러운20초 무수신을 정상 연속 수신으로 오인하지 않는다. 10/15/30초의 일정 간격 개발 테스트에서19개 중12개 초과 승인을 확인한다. 최초에는4개 후보를 모으는 지연(10초 간격이면 약40초)이 있을 수 있고30초 초과 간격은 이 모델의 지원 범위가 아니다.
7. **R2 정보 한계 유지**: QA와 PM의 정보 한계 분류를 유지한다. 첫 점과 뒤 후보가 모두 정확도5m를 주장하면3초 안에 어느 집단이 참인지 구별할 독립 근거가 없다. 세 번 거절됐다는 이유로 후보를 빠르게 믿지 않는다. 약10초 대기로 경고·진행 공백이 생기는 한계를 인정하며 다중 위치 소스/이전 세션 위치 등 추가 근거는 후속 후보이다. R2 단언은 변경·삭제하지 않으며 QA_REPRO 실패를 그대로 기록한다. **R2가 남은 실행을 전체 통과라고 부르지 않는다.**

### 개발 검증과 변경 파일

- `RouteReacquisitionTest.kt`(신규8개): DR 없는 헤어핀 복귀와 누적 후진 금지, 대응 경로 없는 역방향 궤적의 불확실/이탈, 동일 지터 예산 반복 소비 방지, 정차 지터의 방위 미확정, 새 후보 궤적 기반 방위,10/15/30초 저빈도 수신, 갑작스러운 무수신과 저빈도 패턴 구분, 같은 정확도 최초 오차의 안전한 대기.
- 중간 독립 개발 스냅샷에서 `QA_REPRO=1 clean test` 총137개 중 R2만 실패했고 나머지136개가 실제 통과했다. 이 결과는 최종 동결 증거와 분리한다.
- 변경 구현: `core/navi/FixGate.kt`, `core/navi/RouteTracker.kt`, `core/navi/GuidanceAnnouncer.kt`, `app/navi/NaviHub.kt`. 개발 테스트·이 문서·`dev-evidence/round3/*`만 추가/수정했다. QA 소유 파일과 분석 원문은 쓰지 않았다.
- 공공 카메라 방향/병합 기본값, 미확인 구간 짝 금지, 실제 속도 없는 과속 주장 금지, DR 시간·차량 속도 신선도 제한, 사용자 설정 및 Compose 기능은 유지한다. 단일 TTS/제한속도 프리셋은 이번 범위에 포함하지 않는다.

추가 한계: 이동 방위는 GPS 두 점 이상으로부터 얻는 증거이지 차선·차량 방위의 진실을 보장하지 않는다. 낮은 속도/큰 정확도 오차에서는 방위가 없을 수 있고, 같은 방향의 겹치는 경로와 지속적인 GPS 편향은 여전히 모호하다. 현재 수치(75도, 최소 이동량, 최대30초)는 앱 설계값이며 실차 검증이 필요하다. 새 positionUncertain 연결의 실제 화면·음성 타이밍도 app 미컴파일 상태에서는 실행 검증이 아니다.


### R3/R1 최종 검증·재동결

고정 소스: `/tmp/splittesla-r3-final-5fr16a3b`.파일별 manifest: `dev-evidence/round3/final-source-sha256.json`.이전6c2940d6… 기록을 덮어쓰지 않고 새 라운드로 보존했다.

- **일반 clean/test (QA_REPRO 미설정)**: 총137개, 정상 통과126개, QA 재현 건너뜀11개, 실패0/오류0. BUILD SUCCESSFUL,2분53초. 건너뜀을 통과로 세지 않았다. `round3/normal-clean-test.log`, `normal-summary.json`, `normal-xml/*` 참조.
- **QA_REPRO=1 QA 필터 실행**: 총32개 실제 실행, **31개 통과·R2 한 건 실패·건너뜀0**, BUILD FAILED,23초(exit=1). R1/R3 두 개는 통과했고 기존 D4 포함29개도 전부 통과했다. R2는 “first real fix accepted after 10 s”로 실패했다. **이 실행은 전체 통과가 아니다.** `round3/qa-repro-test.log`, `qa-summary.json`, `qa-xml/*` 참조.
- 명령: `JAVA_HOME=... env -u QA_REPRO ./gradlew -p <snapshot>/core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'` 후 `QA_REPRO=1 JAVA_HOME=... ./gradlew -p <snapshot>/core test --tests 'com.splittesla.core.qa.*' --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`.후자는 필터를 바꾸어 test task를 실제 실행했다.
- R2에는 빠른 source 교체뿐 아니라 잘못된 최초 경로 anchor를 뒤로 확정하지 않는 제약도 있다. 따라서 route에는 불확실 상태·재탐색이 필요할 수 있다.3초 복구를 보장하거나 실제 경로를 모르는 상태에서 후진을 확정하지 않는다.
- Android `:app:assembleDebug --no-daemon` 재시도도 **SDK location not found**로 실패(1분3초). `round3/android-build.log`에 기록했다. app Kotlin·APK·기기·실차 동작은 미검증이다.
- 실행 후 스냅샷과 작업 트리의 manifest 대상 파일 해시는 모두 일치한다. `round3/final-validation-summary.json`에 소스 파일 수·결과·실패 원문·exit code·비교 결과를 남겼다. 이 시점부터 구현과 개발 테스트를 다시 동결하고 더 변경하지 않는다.

재동결 기록(UTC): `2026-11-03T20:01:12.469332+00:00`.manifest SHA-256: `4cc88c3235611fab981e9e42115b35443a0b5e14f4c561e4c84a05f4992bcf97`.

## R4 유효 추정 턴 예고 복구 (네 번째 구현 단계)

새 작업(task_f7d2081b5f2d) 범위에서 직전 동결을 해제했다. QA 소유 `AndroidGuidanceQaRound3ProbeTest.kt`와 최신 `qa.md`의 R4 수용 기준을 읽었고 QA 파일/단언/문서는 수정하지 않았다. 앞 절의4cc88c32… 결과는 이전 소스 기록이다.

### 재현

고정된 수정 전 소스 `/tmp/splittesla-r4-before-wzoigxsu`에서 `QA_REPRO=1 ... test --tests '*AndroidGuidanceQaRound3ProbeTest*'`를 실제 실행했다. **R4 실패, 대조군 통과(2개 중1개 실패)**. 정확도80m 거절 fix 뒤40회 DR 동안 spoken=[]이고 along≈850m, estimated=true, positionUncertain=true였다. `dev-evidence/round4/before-source-sha256.json`, `before-test.log`, `before-summary.json`, `before-xml/*`에 보존했다.

### 신뢰 계약

`positionUncertain`은 실제 위치를 확정할 수 있는지에 대한 상태다. 턴의 예측 가능 여부와 같은 플래그로 쓰면 터널 입구의 불량 fix 하나가 유효 DR 예고까지 영구히 막는다. 이 값은 **DR 때문에 false로 지우지 않는다.** 대신 다음을 분리했다.

| 상태 | 턴 예고 | 실제 도착/경유지 확정 |
|---|---|---|
| 실제 GPS가 경로에 유효하게 매칭됨 | 기존 실제 안내 | 기존 실제 fix 조건을 만족할 때만 |
| GPS 샘플 거절, 아직 유효 DR step 없음 | 보류 | 금지 |
| 마지막 매칭 경로 anchor + 유효하고 제한된 DR step | “추정 위치 기준”을 붙인 예상 안내 | 금지 |
| 실제 GPS가 경로와 모순되어 투영 거절 | anchor 취소, DR로 발화 제한을 우회하지 못함 | 금지 |
| 속도/시간 조건 만료·유효하지 않은 step | 추정 발화 허가 취소 | 금지 |
| 아직 실제 경로 anchor 없음 | 보류 | 금지 |

- `RouteProgress.estimatedTurnAllowed`와 계산 속성 `canAnnounceTurn`을 추가했다. 기존 필드는 유지하고 새 필드는 호환 기본값을 둔다. `estimated=true`일 때는 전용 예측 허가가 있어야 턴을 말한다. 실제 진행에서는 `positionUncertain=false`여야 한다.
- RouteTracker는 최초 실제 매칭 전부터 positionUncertain=true다. 실제 매칭 성공으로만 실제 확신을 회복한다. 잘못된 raw GPS 샘플은 실제 위치를 불확실하게 만들지만, 그 샘플만으로 마지막 유효 경로 anchor를 틀렸다고 확정하지 않는다. **유효한 실제 GPS의 경로 투영이 모순될 때는** anchor를 취소하며 실제 이탈 상태도 DR로 지우지 않는다. R1/R3/D4의 경로 재획득/방위·연속성 조건은 그대로다.
- `advance(metres, elapsedMs=1000, turnPredictionValid=true)`는 기존 geometry API를 보존한다. 거리 적분을 호출했다고 실제 위치가 확정되는 것은 아니다. 추정 턴 허가는 유효한 경로 anchor, 호출자 승인,1–2000ms step, 유한한 양수 거리와250km/h 상한, 누적 요청 적분시간30초 상한을 모두 요구한다. invalid/과도한 step은 geometry 호환 동작과 별개로 발화를 허용하지 않는다. 실제 매칭 성공만 적분 예산을 리셋한다.
- **속도·절대 시간의 주인은 NaviHub**다. 매번 `SpeedEvidence.deadReckoningKmh`로3초 이내의 실제 차량 속도와 마지막 실제 fix 후3–30초 범위를 확인하고, 그 분기 안에서 실제 loop 간격을 advance에 전달한다. 원시 거리 API만으로 외부 속도 timestamp를 알 수 없으므로 이 호출자 계약을 명시했다. 기존 한 인자 호출은1초의 승인된 step이라는 호환 가정을 쓴다. core30초 상한은 요청한 적분시간이며 앱은 별도로 마지막 실제 fix의 절대30초 deadline을 적용하므로 중간 대기가 허가를 연장하지 않는다.
- 서비스가 신선한 속도·유효시간·loop 간격 조건을 만족하지 못하면 `invalidateEstimatedTurn()`으로 허가를 즉시 취소하고 진행 상태를 갱신한다. 실제 위치 신뢰도를 올리거나 도착을 확정하지 않는다.
- GuidanceAnnouncer와 NaviHub의 발화 분기만 `canAnnounceTurn`을 사용한다. 추정 발화에는 **“추정 위치 기준, …”**를 붙인다. `arrived`와 실제 도착 발화는 estimated/positionUncertain인 상태에서 계속 금지한다. 경유지·lastGuideIndex 확정 조건은 `!estimated && !positionUncertain`을 그대로 유지한다.
- 카메라 서비스에 DR 위치를 전달하지 않았다. 카메라 통과·구간 평균은 기존 실제 GPS 증거만 사용하고 미확인 짝의 숫자를 만들지 않는다. 따라서 터널 속 카메라 경고 공백은 이번에도 해결했다고 주장하지 않는다.

### 개발 테스트와 검증 범위

`EstimatedGuidanceContractTest.kt` 신규7개는 실제 불확실성 유지 상태의 예상 문구, 모순된 경로의 DR 우회 차단,30초 만료와 실제 재획득 리셋, 속도/절대 deadline 만료의 허가 취소, malformed/미승인 step/anchor 없음, 추정 도착 플래그의 실제 도착 발화 금지, 실제 GPS 복귀 후 실제 안내 문구 복원을 검증한다. 중간 독립 스냅샷 `QA_REPRO=1 clean test` 총146개에서 R2만 실패했고 R4/대조군과 기존 기대 조건은 통과했다. 최종 고정 소스 결과와는 별도 기록이다.

이번 구현 변경: core `navi/RouteTracker.kt`, `navi/GuidanceAnnouncer.kt`; app `navi/NaviHub.kt`; 개발 테스트 `navi/EstimatedGuidanceContractTest.kt`(신규); 이 구현 문서와 `dev-evidence/round4/*`. QA 소유 파일, 분석 원문, 의존성·리소스·설정 키, 카메라 방향/병합 정책은 수정하지 않았다. 단일 TTS/제한속도 프리셋은 계속 후속 범위다.


### R4 최종 검증·재동결

고정 소스는 `/tmp/splittesla-r4-final-hv09h_ts`이고 파일별 manifest는 `dev-evidence/round4/final-source-sha256.json`이다. core 전체·최신 QA 테스트4개·개발 테스트·변경 app Kotlin3개를 포함한다. 이전 round3 manifest를 덮어쓰지 않았다.

- **일반 clean/test**: 총146개, 정상 통과133개, QA 재현 건너뜀13개, 실패0/오류0. BUILD SUCCESSFUL,2분1초. `round4/normal-clean-test.log`, `normal-summary.json`, `normal-xml/*` 보존. 건너뜀은 정상 통과가 아니다.
- **QA_REPRO=1 QA 필터 실제 실행**: 총34개, **33개 통과·R2만1개 실패·건너뜀0**. R4와 대조군2개, R1/R3 두 개, 기존 D4·D5·D7/도착 검증을 포함한29개가 모두 통과했다. R2는 “first real fix accepted after 10 s”로 실제 실패했고, BUILD FAILED(exit=1),20초다. **전체 QA 통과로 표현하지 않는다.** `round4/qa-repro-test.log`, `qa-summary.json`, `qa-xml/*` 보존.
- 실행 명령: `JAVA_HOME=... env -u QA_REPRO ./gradlew -p <snapshot>/core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'` 후 `QA_REPRO=1 JAVA_HOME=... ./gradlew -p <snapshot>/core test --tests 'com.splittesla.core.qa.*' --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`. 후자는 필터가 달라 test task가 실제 실행됐으며 정상 실행의 skipped 결과를 재사용하지 않았다.
- Android `:app:assembleDebug --no-daemon` 재시도는37초 후 **SDK location not found**로 실패했다. `round4/android-build.log`의 원문처럼 `:app:compileDebugJavaWithJavac` 의존성 확인 단계에서 막혔다. app Kotlin·APK·서비스·TTS·기기/실차 검증은 미실행/미검증이며 정적 계약 검토와 core 테스트를 기기 통과로 주장하지 않는다.
- 실행 후 스냅샷 및 작업 트리의 manifest 대상 해시는 모두 일치한다. `round4/final-validation-summary.json`에 결과·실패 원문·exit code·비교 결과·UTC 동결 시점을 남겼다. 이 시점부터 구현과 개발 테스트를 다시 동결하고 더 변경하지 않는다.

R4 동결 기록(UTC): `2026-11-03T20:21:53.632246+00:00`. manifest SHA-256: `53a0193a73f68811a344ac89cd29d269d1c300060d760653f88baa45499993fb`.

## R4b/R4c/R4d 양호 anchor·시각 보존 (다섯 번째 구현 단계)

새 작업(task_0709632fa172)의 PM 정책에 따라 직전 동결을 해제했다. QA 소유 `AndroidGuidanceQaRound4ProbeTest.kt`, `AndroidGuidanceQaR4PolicyProbeTest.kt`, 최신 `qa.md`와 `qa-evidence/round4-policy/*`를 읽었으며 이 파일들은 수정하지 않았다. 앞 절의53a0193a… 결과는 이전 소스 기록이다.

### 고정된 수정 전 재현

`/tmp/splittesla-r4b-before-zjrz3yk3`에서 QA_REPRO=1로 두 QA 클래스4개를 실행했다. **R4b·R4c·R4d3개 실패, 기존 만료/거친 step/도착 금지 계약1개 통과**였다. R4b는 spoken=[], R4c는 마지막 양호 fix 후35초에도 턴 허가, R4d는50m 표본으로 실제 위치를 확정하는 문제가 재현됐다. `dev-evidence/round5/before-source-sha256.json`, `before-test.log`, `before-summary.json`, `before-xml/*`에 고정 소스와 원문을 보존했다.

### 정책과 구현

- **양호성 경계는 기본30m**다. RouteTracker의 기존 신뢰 정확도와 위치 admission을 통일했다. 정확도가 이를 넘는 표본은 물리 이동 gate·진행·방위 이력·실제 경로 anchor 갱신 전에 보류한다. on-route처럼 보여도 실제 진행이나 실제 확신을 회복하지 않는다. `acceptedFix`가 외부에서 전달돼도 같은 정확도 검사를 적용한다. 정확도 미제공의 기존 호환 의미는 유지한다.
- 보류는 `invalidatePositionEvidence()`만 수행한다. 마지막 양호 경로 anchor와 그 시각, 실제 진행 상한, 누적 DR 시간, 기존 이탈 카운트는 그대로다. 따라서 단일 낮은 정확도 fix로 anchor를 취소하지 않으며 유효시간 안의 신선한 차량 속도가 있으면 **추정 위치 기준** 턴만 허용할 수 있다.
- **시간이 흐른 것은 무시하지 않는다.** `observeFixTime`은 표본의 단조 시각과 마지막 실제로 적용된 양호 route fix 시각의 차이를 기존 만료 예산에 max로 반영한다. 낮은 정확도 표본은 anchor를 새로 시작하지 않으며 예산을0으로 리셋하지 않는다. 양호 fix 뒤25초의 흐린 on-route 표본과10초 DR를 합치면35초이므로 만료한다. 관측 시각은 **기존 anchor의 나이를 늘리는 데만** 쓰며 위치/방위/실제 시각을 인증하는 근거가 아니다.
- RouteTracker의 `validateFix`를 앱과 core의 공통 admission으로 사용한다. 낮은 정확도이면 null이고 물리 gate의 기준점·후보 궤적·방위 이력도 오염시키지 않는다. 실제로 경로에 매칭된 양호 fix만 route anchor와 그 epoch를 바꾸며, 좋은 정확도의 모순 fix와 연속 이탈 카운트는 기존대로 anchor를 취소한다. 낮은 정확도 반복만으로 새로운 확정 정책을 만들지 않았다.
- **NaviHub의 시각을 구분했다.** `receivedAt`은 이번 콜백 처리 시각이며 권한의 새 기준점이 아니다. `SpeedEvidence.fixSampleAtMs`는 SDK 단조 sample age를 빼 실제 GPS 표본 시각에 대응하는 wall time을 계산한다(0–3초 나이만 허용). 이름을 `lastGoodFixAt`으로 바꾸고 양호 admission 통과 뒤에만 그 표본 시각과 위치를 저장한다. 낮은 정확도/오래된/거절된 표본은 이를 갱신하지 않는다. DR·gpsOk·15초 도착 재확인·저장 위치 신선도는 수신 시각이 아니라 이 양호 표본 기준을 사용한다.
- 안내 중에는 tracker의 admission 결과를 그대로 update에 전달해 이중 gate를 피한다. 비안내 Nav 위치도 동일한30m 양호 기준으로 저장한다. 범용 FixGate의 기본 정확도60m는 유지하고 구성 가능한 상한만 추가했으므로 **카메라 등 다른 호출자의 기존 기본 정책은 바꾸지 않았다**.
- R4의 별도 추정 발화 허가, 실제 불확실성 유지, 실제 도착/경유지/카메라 통과 및 미확인 평균 확정 금지는 유지한다. R1/R3/D4 방위·연속성, 정확도 좋은 모순 fix, 반복 이탈과 R2 정보 한계 정책도 유지한다.

### 개발 테스트와 범위

신규 `WeakFixAnchorPolicyTest.kt`8개는 낮은 정확도 옆길 표본의 실제 모델 이력 미오염과 추정 허가, on-route 표본의 실제 미확정,25초 관측+10초 적분 만료, 정확도30m 모순·반복 이탈 유지,30m 경계와 범용60m 기본 호환, 외부의 넓은 admission으로도 우회 불가, 수신 지연으로 deadline 연장 금지, 만료 후 낮은/과거 표본으로 허가 부활 금지를 검증한다. 중간 독립 스냅샷의 QA_REPRO 전체158개 실행은 R2만 실패했고 나머지는 통과했다. 최종 고정 소스와 결과는 아래에 별도 기록한다.

이번 구현 변경: core `navi/RouteTracker.kt`, `navi/FixGate.kt`; app `navi/NaviHub.kt`; 개발 테스트 `navi/WeakFixAnchorPolicyTest.kt`(신규); 이 문서와 `dev-evidence/round5/*`. QA 소유 파일, 분석 원문, 카메라 방향/병합 기본값, 사용자 설정, BLE·Compose 기능, 의존성은 수정하지 않았다. SDK 부재 시 앱 계약은 정적 검토이며 기기 실행으로 주장하지 않는다.


### R4b/c/d 최종 검증·재동결

고정 소스: `/tmp/splittesla-r4b-final-3lo422fr`.파일별 manifest: `dev-evidence/round5/final-source-sha256.json`.core 전체·최신 QA 소유6개 파일·개발 테스트·변경 app Kotlin3개를 포함하며 이전 라운드 기록을 덮어쓰지 않았다.

- **일반 clean/test**: 총158개, 정상 통과141개, QA 재현 건너뜀17개, 실패0/오류0. BUILD SUCCESSFUL,2분3초. 건너뜀은 통과로 세지 않았다. `round5/normal-clean-test.log`, `normal-summary.json`, `normal-xml/*` 보존.
- **QA_REPRO=1 QA 필터 실제 실행**: 총38개, **37개 통과·R2만1개 실패·건너뜀0**, BUILD FAILED(exit=1),20초. R4b/c/d 및 기존 만료 계약4개가 통과했고 나머지 R2 제외 기존33개(R4·대조군, R1/R3/D4/D5/D7 등)도 전부 통과했다. R2는 “first real fix accepted after 10 s”로 실제 실패했다. **이 실행은 전체 QA 통과가 아니다.** `round5/qa-repro-test.log`, `qa-summary.json`, `qa-xml/*` 보존.
- 명령: `JAVA_HOME=... env -u QA_REPRO ./gradlew -p <snapshot>/core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'` 후 `QA_REPRO=1 JAVA_HOME=... ./gradlew -p <snapshot>/core test --tests 'com.splittesla.core.qa.*' --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`.후자는 필터 변경으로 test task가 실제 실행되어 skipped 결과를 재사용하지 않았다.
- Android `:app:assembleDebug --no-daemon` 재시도는 **SDK location not found**로 실패(38초). `:app:compileDebugJavaWithJavac` 의존성 확인 단계에서 막혔으며 `round5/android-build.log`에 원문을 보관했다. app Kotlin·APK·서비스·기기/실차는 미검증이다. 앱의 양호 표본 시각 갱신 순서는 코드 검토와 공통 core 시간/자격 API 테스트로 확인한 것이며 Android 런타임 통과가 아니다.
- 실행 후 스냅샷과 작업 트리의 manifest 대상 파일 해시가 모두 일치한다. `round5/final-validation-summary.json`에 결과·실패 원문·exit code·소스 비교·UTC 동결 시각을 남겼다. 이 시점부터 구현과 개발 테스트를 다시 동결하고 더 변경하지 않는다.

R4b/c/d 동결 기록(UTC): `2026-11-03T20:48:37.622324+00:00`.manifest SHA-256: `572dce5752f28fe192158c3ec299376dfbe9337372632db00b79f3bd77208280`.
