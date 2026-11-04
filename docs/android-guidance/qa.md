# Android 안내 안전성 강화: 독립 QA 보고서 (1차 → 동결본 → 라운드3 → 라운드4 → 라운드5 최종)

- 기준 커밋: `6f21050`. 대상은 그 위의 미커밋 작업 트리 변경(app 3개, core 5개, 테스트, 문서)이다.
- QA 일시: 2026-11-04. 작업자: QA 워커(task_5493d1abb061). 구현 코드는 수정하지 않았다. 커밋·푸시·유료 API·코드 복제는 하지 않았다.
- 수정한 파일: `core/src/test/kotlin/com/splittesla/core/qa/AndroidGuidanceQaTest.kt`(정상 반례 21개), `.../qa/AndroidGuidanceQaDefectsTest.kt`(1차 결함 재현 8개), `.../qa/AndroidGuidanceQaFinalProbeTest.kt`(동결본 새 반례 R1–R3), `.../qa/AndroidGuidanceQaRound3ProbeTest.kt`(라운드3 R4와 대조군), `.../qa/AndroidGuidanceQaRound4ProbeTest.kt`(라운드4 R4b와 계약 probe), `.../qa/AndroidGuidanceQaR4PolicyProbeTest.kt`(R4c/R4d), `.../qa/AndroidGuidanceQaRound5ProbeTest.kt`(라운드5 독립 probe 11개, manifest 밖), 이 문서, `qa-evidence/`.
- 실행 환경: 기존 JDK `/home/orca/project_folder/SplitTesla/tools/jdk-21`(JAVA_HOME)과 `./gradlew -p core ...`. analysis.md에 적힌 "JDK 없음"은 분석 당시 환경 추정이다. 실제로는 위 JDK로 core 테스트를 실행했다.

## ★ 최종 판정: 라운드5 재동결본 (2026-11-04 05:49)

**판정: 조건부. 전체 통과가 아니다.**
- **core 단위 범위에서 확인한 것:** D1–D5, D7–D9, R1, R3, R4, R4b, R4c, R4d가 독립 재현으로 해소됐다. 기존 테스트와 QA 기대 조건도 유지된다. 이번 동결본에서 현 범위 구현 결함은 새로 찾지 못했다.
- **남은 항목:**
  - R2: 정보 한계로 실패를 유지하며 따로 집계한다.
  - L1: 정책 한계(자동 도착 반경).
  - L2: 이번 안전 강화 정책의 **명시적 잔여 한계**(PM 확정, 아래 표). 실차 분포 근거 없이 문턱을 다시 바꾸지 않는다.
  - 표시 한계: L2의 침묵이 정상 안내로 오인될 수 있다(아래 "위치 불확실 표시" 절). 제한 모드와 표시 개선은 후속 개발에서 검토한다.
  - 미검증: Android SDK가 없어 app Kotlin 컴파일, APK, 기기·실차 실행을 확인하지 못했다.
  - 후속 범위: 단일 TTS와 제한속도 거리 프리셋은 미구현이다.
- **출시 가능 조건:** (1) SDK 환경에서 app 빌드, (2) 실차 체크리스트(터널, 저빈도 GPS, 도착, 구간단속) 통과, (3) 실차에서 정확도 31–60m 지속 빈도 측정. L2 자체는 이번 범위의 잔여 한계로 유지하며, 측정 결과는 후속 제한 모드·표시 개선의 근거로 쓴다.

| 항목 | 결과 |
|---|---|
| 소스 고정 | `dev-evidence/round5/final-source-sha256.json`(파일 SHA256 `572dce5752f28fe192158c3ec299376dfbe9337372632db00b79f3bd77208280`, 63개 파일). QA 독립 스냅샷(`qa-evidence/round5/source-manifest.sha256`, `source-snapshot-id.txt`), 작업 트리, 개발 `/tmp/splittesla-r4b-final-3lo422fr` 모두 **63/63 일치**. 스냅샷과 작업 트리는 실행 뒤에도 일치했다. QA 파일 6개 해시는 QA가 기록한 값과 같다(Defects `3d833313`, Qa `a7f84c97`, FinalProbe `f35e8de6`, Round3Probe `b4ce94d9`, Round4Probe `41621e8b`, R4Policy `0c49d587`). QA 단언은 바뀌지 않았다 |
| 일반 `core clean test` | **158개 중 141개 통과, 17개 건너뜀, 실패 0**, exit 0(`qa-evidence/round5/core-clean-test*`). 건너뜀 17개는 QA_REPRO 게이트가 걸린 재현·probe 테스트다 |
| `QA_REPRO=1` QA 38개 | **37개 통과, 1개 실패(R2)**, exit 1(`qa-evidence/round5/qa-repro*`). 통과 37개: D1–D5, D7–D9(8) + R1, R3(2) + R4와 대조군(2) + R4b와 만료 계약(2) + R4c, R4d(2) + 정상 반례 21(D4 회귀, L1 정책 고정 포함). **R2("first real fix accepted after 10 s")는 단언을 바꾸지 않고 정보 한계 실패로 따로 집계한다** |
| 개발 보고와 대조 | 일반 141/17/0, QA_REPRO 37/38(R2 실패)로 **일치** |
| 라운드5 독립 probe (`AndroidGuidanceQaRound5ProbeTest`, manifest 밖, 동결 소스 사본에서 실행) | **11개 모두 통과**(`qa-evidence/round5/round5-probe*`). 이 파일을 작업 트리에 추가했으므로 이후 작업 트리에서 일반 실행하면 총 169개, 건너뜀 28개로 보인다. 위 일반 158개 수치는 파일 추가 전 동결본 결과다 |

### 지시받은 유지 조건 확인 (라운드5 probe)

| 조건 | 입력 | 결과 |
|---|---|---|
| 좋은 정확도 모순 유지 | 정확도 20m, 도로 옆 90m fix 1개 | 위치 불확실, 이후 DR 턴 예고 금지 |
| 반복 이탈 유지 | 같은 fix 3회 | offRoute가 false, false, true |
| 낮은 정확도 반복은 이탈 아님 | 정확도 50m, 옆 90m fix 21회 | offRoute 안 됨 |
| 경계 | 정확도 30.01m / 30m | 30.01m는 보류, 30m는 진행 반영 |
| 진행·이동 게이트 비오염 | 정확도 50m로 300m 앞 fix 4개 뒤 양호 fix | along 불변, 다음 양호 fix가 바로 정상 궤적으로 이어짐 |
| 만료창 연장 불가 | 양호 fix 뒤 흐린 fix 20초 + DR 1초 / 30초 + DR 1초 | 20초는 허용, 30초는 금지 |
| 만료 후 복구 | DR 만료 뒤 양호 fix | 실제 위치 복구 뒤에야 예고 재허용 |
| 도착 | 목적지에서 정확도 35m fix 11개 | 도착 확정 없음. 이후 양호 fix 8개째에 도착(재획득 + 2회 확인) |
| 표본 시각 | 나이 2.9초, 3.0초, 3.000000001초, 음수, 수신 시각 0 | `fixSampleAtMs`가 나이를 빼서 계산, 범위 밖은 null |

probe 작성 중 정정 1건: 도착 테스트의 첫 초안은 "흐린 구간 뒤 양호 fix 2개면 도착"을 기대해 실패했다. 11초 공백은 이동 게이트에서 실제 무수신으로 처리되어 재획득이 먼저 필요하므로, 이는 구현 결함이 아니라 QA 기대값 오류였다. 측정값(8개)을 확인하는 단언으로 바꿨고, 상한 8은 측정 전에 정했는데 측정값이 정확히 상한이다. 이 파일은 동결 manifest에 없는 새 파일이며 기존 QA 단언은 수정하지 않았다.

### 명시적 잔여 한계 L2 (PM 확정, 구현 결함으로 분류하지 않음)

| ID | 영향 | 입력 | 실제 |
|---|---|---|---|
| L2 | 중 (빈도 미측정) | 60km/h로 200m 주행 뒤 경로 위 정확도 40m fix 60초 | 차량 속도 없음: along 멈춤, 발화 0건, offRoute 없음. 차량 속도 있음: 추정 예고가 약 28초에서 끝나고 그 뒤 침묵. 정확도가 돌아오면 양호 fix 5개째에 복구되며, 그 사이 지난 턴은 늦게 말하지 않는다 |

안내 쪽 양호 기준이 60m에서 30m로 좁아진 결과다. 기준 `6f21050`은 정확도 31–60m fix를 진행에 썼다(정적 확인). "낮은 정확도 표본은 실제 진행·기준점·만료창을 갱신하지 못한다"는 PM 정책대로의 동작이므로 결함이 아니다. 다만 도심 협곡에서 이 구간이 얼마나 자주 지속되는지 데이터가 없어 위험 크기를 판단할 수 없다. 카메라 쪽 `FixGate()` 기본값은 60m로 유지되어 이 변경의 영향을 받지 않는다.

### 위치 불확실 표시: 침묵이 정상 안내로 오인될 여지 (정적 검토, 기기 화면 미확인)

**결론: 오인 여지가 있다.** L2 동안 앱이 운전자에게 주는 신호는 안내 상세 화면의 작은 문구 하나뿐이고, 그 문구도 차량 속도가 없거나 추정이 만료된 경우에는 사실과 다르다. UI 파일은 이번 작업에서 바뀌지 않았다(`git diff 6f21050 -- app/src/main/kotlin/com/splittesla/app/ui` 변경 없음).

| 표면 | 위치 | L2 중 표시 | 문제 |
|---|---|---|---|
| 안내 상세 화면 | `NaviPanel.kt:184` | `gpsOk=false`면 12sp 황색 문구 "GPS 신호 약함: 차량 속도로 위치를 추정 중" | 조건이 `!gpsOk` 하나다. 차량 속도가 없어 추정이 전혀 없거나 30초가 지나 멈춘 경우에도 같은 문구가 나와 **사실과 다르다**. 화면 아래쪽 상세 값들 사이에 있다 |
| 턴 카드·배너 | `NaviPanel.kt` `TurnCard`, `TurnBanner`, `CockpitParts.kt:305` | 마지막 값의 턴과 남은 거리를 그대로 표시 | GPS 상태 표시 없음. 멈춘 거리가 현재 값처럼 보인다 |
| 지도 | `NaviMap.kt`, `NaviHub.afterProgress` | 경로상 마지막 진행 위치에 차를 그림 | 실제 차는 앞서가는데 지도는 멈춰 있고, 멈췄다는 표시가 없다 |
| 대시보드 카드 | `SplitDashboard.kt` `NaviCard` | 다음 턴, 시간, 거리 | GPS 상태 표시 없음 |
| 알림 | `NaviService.kt` `banner()` | 다음 턴, 남은 거리, 시간 | GPS 상태 표시 없음 |
| 음성 | `NaviHub` | 없음 | 위치 불확실이나 안내 보류를 알리는 발화가 없다. 침묵만 남는다 |
| 카메라 화면 | `CameraSection.kt:87`, `SpeedCameraService` | 카메라 쪽 기준은 60m라 31–60m에서 `gpsOk=true` | "GPS 신호를 기다리는 중…"이 나오지 않는다. 같은 순간 내비는 약함, 카메라는 정상으로 **두 표시가 다를 수 있다** |

`RouteProgress.positionUncertain`과 `estimated`는 UI가 직접 읽지 않는다. 앱 안의 사용처는 `NaviHub`뿐이고, 화면에는 `gpsOk`로 합쳐져 전달된다. 그래서 화면은 "추정 중", "추정 만료", "진행 보류"를 구분할 수 없다.

운전자 관점의 위험: 턴 예고가 없고 거리 숫자가 멈춘 상태를 "아직 턴이 멀다"로 읽을 수 있다. 특히 대시보드·알림·턴 배너만 보고 있으면 경고가 전혀 보이지 않는다. 이번 범위에서는 구현을 바꾸지 않으며, 제한 모드와 표시 개선(문구를 실제 상태별로 구분, 턴 카드 경고, 음성 고지)은 후속 개발 검토 항목으로 넘긴다.

### app 정적 계약 (코드 읽기 결과, 실제 컴파일 아님)
- **마지막 양호 표본 시각:** `NaviHub.handleFix`가 `SpeedEvidence.fixSampleAtMs(수신 시각, 표본 나이)`로 표본의 실제 시각을 계산한다. `lastGoodFixAt`은 admission을 통과한 뒤에만 이 값으로 저장된다. 낮은 정확도, 오래됨, 거절된 표본은 갱신하지 못한다. 수신 지연은 시각을 새로 만들지 못한다.
- **사용처:** DR 창(`deadReckoningKmh`), gpsOk, 15초 도착 재확인, DR 감시 루프, `bestPosition` 신선도가 모두 `lastGoodFixAt`을 쓴다. 벽시계끼리만 비교한다. 단조 시각은 FixGate와 tracker 쪽에서만 쓴다.
- **admission:** 안내 중에는 `t.validateFix` 결과를 `update(..., acceptedFix = accepted)`로 넘겨 이중 게이트가 없다. `update`는 외부 acceptedFix가 있어도 정확도를 다시 검사한다. 비안내 때는 30m 기준 `fixGate`를 쓴다.
- **DR 루프:** DR 조건이 안 되면 `invalidateEstimatedTurn()`으로 허가를 내린다. 턴 발화는 `canAnnounceTurn`만 따른다. 도착·경유지·`lastGuideIndex`는 `!estimated && !positionUncertain`일 때만 확정한다.
- 관찰 1건: `lastGoodFixAt`은 tracker 투영 전에 갱신되므로 정확도는 좋지만 경로와 모순인 fix도 시각을 갱신한다. 이 경우 tracker가 기준점을 취소해 DR 예고는 막힌다. 위험은 낮다고 판단한다.

**Android SDK가 없다.** `ANDROID_HOME`이 비어 있고 `local.properties`도 없으며, 개발 `dev-evidence/round5/android-build.log`도 BUILD FAILED다. 따라서 위 내용은 실제 Kotlin 컴파일로 확인한 것이 아니다. 이번 라운드에서 QA가 assembleDebug를 다시 실행하지는 않았다.

### 잔여 위험·한계 (최종)
- R2: 첫 fix가 크게 틀렸는데 정확도를 좋게 주장하면 약 10초 공백. 판단할 추가 근거가 없는 정보 한계이며 QA_REPRO 실패로 유지한다.
- L1: 경로 끝 45m 옆 주차는 자동 도착이 안 되고 수동 "안내 종료"로 끝낸다.
- L2: 양호 기준이 60m에서 30m로 좁아졌다. 31–60m가 지속되면 실제 진행을 보류하고, 추정 예고는 약 28초에 끝나며(차량 속도 있을 때), 복귀에 양호 fix 5개가 필요하고, 지난 턴은 말하지 않는다. 실차 분포 근거 없이 문턱은 다시 바꾸지 않는다.
- 표시: L2의 침묵이 정상 안내로 오인될 수 있고, 안내 상세 화면 문구는 추정이 없을 때도 "추정 중"이라고 표시한다.
- 재획득 공백: 10초 넘는 흐린 구간이나 무수신 뒤 양호 fix 약 5개가 필요하다. 목적지에서는 도착까지 8개가 걸렸다.
- 30초를 넘는 fix 간격은 지원 범위 밖이다. 10초 간격은 첫 재획득에 약 40초가 걸린다(개발 문서).
- 카메라 경고 공백: 안내 중 경로 카메라 목록이 비어 있을 때, 터널 무수신 중, 재획득 동안. 한 세션에서 같은 START를 다시 통과하면 SectionStart가 다시 나지 않을 수 있다.
- **후속 범위 미구현:** 단일 TTS(세 엔진 동시 발화)와 제한속도 거리 프리셋.
- **미검증:** app Kotlin 컴파일, APK, TTS·서비스·Compose 런타임, 실차.

---

## 이전 판정: 라운드4 재동결본 (2026-11-04 05:22, 대체됨)

**판정: 조건부. 전체 통과가 아니다.**
- **core 단위 범위에서 확인한 것:** 1차 결함 D1–D5, D7–D9, 동결본 결함 R1·R3, 라운드3 결함 R4가 독립 재현으로 해소됐다. 기존 테스트와 QA 기대 조건(정상 반례 21개)도 유지된다.
- **남은 항목:**
  - R2: 정보 한계라 실패로 유지하며 따로 집계한다.
  - R4b(하~중): 정책 확인이 필요한 미해결 반례다.
  - L1: 정책 한계다.
  - 미검증: Android SDK가 없어 app Kotlin 컴파일, APK, 기기·실차 실행을 확인하지 못했다.
  - 후속 범위: 단일 TTS와 거리 프리셋은 미구현이다.
- **출시 가능 조건:** (1) R4b 정책 결정, (2) SDK 환경에서 app 빌드, (3) 실차 체크리스트(터널, 저빈도 GPS, 도착, 구간단속) 통과.

| 항목 | 결과 |
|---|---|
| 소스 고정 | `dev-evidence/round4/final-source-sha256.json`(파일 SHA256 `53a0193a73f68811a344ac89cd29d269d1c300060d760653f88baa45499993fb`, 60개 파일). QA 독립 스냅샷(`qa-evidence/round4/source-manifest.sha256`, `source-snapshot-id.txt`), 작업 트리, 개발 `/tmp/splittesla-r4-final-hv09h_ts` 모두 실행 전후 **60/60 일치**. QA 테스트 4개 해시는 QA가 마지막으로 쓴 내용과 같다(Defects `3d833313`, Qa `a7f84c97`, FinalProbe `f35e8de6`, Round3Probe `b4ce94d9`) |
| 일반 `core clean test` | **146개 중 133개 통과, 13개 건너뜀, 실패 0**, exit 0(`qa-evidence/round4/core-clean-test*`). 건너뜀은 QA_REPRO 게이트가 걸린 재현·probe 테스트다 |
| `QA_REPRO=1` QA 34개 | **33개 통과, 1개 실패(R2)**, exit 1(`qa-evidence/round4/qa-repro*`). 통과 33개의 구성: D1–D5, D7–D9(8) + R1, R3(2) + R4와 대조군(2) + 정상 반례 21(D4 회귀, L1 정책 고정 포함). **R2 실패는 단언을 바꾸지 않고 정보 한계로 따로 집계한다** |
| 개발 보고와 대조 | 일반 133/13/0, QA_REPRO 33/34(R2 실패)로 **일치** |
| 새 probe (manifest 밖 `AndroidGuidanceQaRound4ProbeTest`) | 계약 probe 통과, R4b 실패(`qa-evidence/round4/round4-probe*`) |

### R4 계약 정적 점검 (core 코드 + NaviHub, 컴파일 미확인)
- **실제 위치 신뢰와 추정 턴 허용의 분리:** `RouteProgress.canAnnounceTurn = if (estimated) estimatedTurnAllowed else !positionUncertain`(`RouteTracker.kt:124`). `GuidanceAnnouncer.next`와 `NaviHub.afterProgress:448`이 모두 이 값으로만 턴 발화를 허용한다.
- **`estimatedTurnAllowed` 조건:** `routeAnchorUsable`, 단계 1–2000ms, 누적 DR ≤30초, 거리가 유한하고 0보다 크며 ≤250km/h 상당. 실제 fix가 반영되면 false가 되고 누적 시간이 0으로 초기화된다. NaviHub는 그 위에 `SpeedEvidence.deadReckoningKmh`(신선한 차량 속도, 마지막 실제 fix 후 3–30초)와 루프 간격 ≤2초를 강제한다. 계약 probe에서 30초 초과와 3초 단계는 발화 불가로 확인했다.
- **만료·모순:** FixGate가 거절한 fix는 `invalidatePositionEvidence`만 호출해 기준점을 유지하므로 R4가 해소됐다. 투영 모순(`rejectProjection`)과 이탈 fix는 `invalidateRouteAnchor`로 다음 실제 on-route fix까지 DR 예고를 금지한다. 다만 신뢰 정확도를 넘는 이탈 fix도 금지시키는 점이 R4b다.
- **확정 금지:** `arrived = !estimated && !positionUncertain && 확인 fix ≥2`. DR probe 30초 동안 arrived는 한 번도 true가 아니었다. 도착 발화도 `estimated || positionUncertain`이면 막힌다. 경유지 drop과 `lastGuideIndex`는 `!estimated && !positionUncertain`일 때만 확정한다. gpsOk도 같은 조건이다. 카메라 서비스에는 DR 위치를 넘기지 않으므로 카메라 통과·구간 평균은 실제 승인 GPS 선분으로만 확정된다.

### 라운드4 미해결 반례 (정책 확인 필요)

| ID | 심각도 | 위치 | 재현 입력 | 기대 | 실제 |
|---|---|---|---|---|---|
| R4b | 하~중 | `RouteTracker.kt` update의 lateral>limit 분기 중 신뢰 정확도 초과 쪽 `else { invalidateRouteAnchor() }`(약 l.232–233) | 60km/h로 200m 실제 주행 → 터널 입구에서 정확도 50m fix가 도로 동쪽 90m에 찍힘(FixGate 도달 범위 안이라 수용, lateral 90 > 한도 85) → 30초 DR | DR 턴 예고. 이 fix는 신뢰 정확도(30m)를 넘어 이탈 카운트에도 들지 않는다 | 발화 0건. 흐린 fix 하나가 터널 전체의 DR 예고를 끈다. "모순 경로 시 발화 금지"에 해당하는지 PM이 결정해야 한다 |

### R4b 정책 결정과 다음 라운드 수용 기준 (PM msg_aeb41b030180)
R4b는 이번 정책 정합성 수정에 포함한다. 결정 내용은 다음과 같다.
- 신뢰 한도(30m)보다 정확도가 낮은 단일 fix는 경로 모순을 확정하는 근거로 쓰지 않는다.
- 마지막 양호 기준점은 원래 시각과 만료를 그대로 보존한다.
- 실제 위치·도착 확정은 보류한다.
- 정확도가 낮은 fix로 기준점을 갱신하거나 유효시간을 연장하지 않는다.
- 신선한 속도와 DR 제한 안의 추정 턴만 허용한다.

QA는 수용 기준 probe를 미리 작성했다(`AndroidGuidanceQaR4PolicyProbeTest`, QA_REPRO 게이트). **라운드4 동결본(53a0193a…) 스냅샷에서 2개 모두 기대 실패**로 재현된다(`qa-evidence/round4-policy/`). 다음 동결의 재검증 항목이다.

| ID | 입력 | 기대(정책) | 라운드4 실제 |
|---|---|---|---|
| R4b | 위 표 | 정확도 50m 이탈 fix 뒤에도 DR 예고 허용 | 발화 0 |
| R4c | 양호 fix로 200m 주행(마지막 양호 t=11s) → 정확도 50m on-route fix 25초 → DR 10초 | 마지막 양호 fix로부터 35초가 지났으므로 `canAnnounceTurn=false` | 발화 허용. 흐린 fix가 기준점과 DR 창을 다시 시작했고 positionUncertain=false |
| R4d | 양호 주행 → 80m fix(거절, 불확실) → 정확도 50m on-route fix | 위치를 확정하지 않음(불확실 또는 추정 유지) | `positionUncertain=false`, `estimated=false`로 확정 |

app 정적 관찰(컴파일 미확인): `NaviHub.handleFix`는 FixGate가 수용한 모든 fix(정확도 ≤60m)에서 `lastFixAt`을 갱신한다. 그런데 `SpeedEvidence.deadReckoningKmh`의 30초 창은 `lastFixAt` 기준이다. 따라서 정책을 지키려면 core뿐 아니라 NaviHub의 `lastFixAt`도 양호 fix에서만 갱신해야 할 가능성이 있다. 다음 라운드에서 함께 정적 확인한다.

### 잔여 위험·한계 (최종)
- R2: 첫 fix가 크게 틀렸는데 정확도를 좋게 주장하면 약 10초 공백(정보 한계).
- L1: 경로 끝 45m 옆 주차는 자동 도착이 안 되고 수동 종료한다.
- R4b: 위 표.
- R1 개선 뒤에도 30초를 넘는 fix 간격은 지원 범위 밖이다. 10초 간격은 첫 재획득에 약 40초가 걸린다.
- 카메라 경고 공백: 안내 중 경로 카메라 목록이 비어 있을 때, 터널 무수신 중, 재획득에 걸리는 시간 동안. 한 세션 안에서 같은 START를 다시 통과하면 SectionStart가 다시 나지 않을 수 있다.
- 단일 TTS(세 엔진 동시 발화)와 제한속도 거리 프리셋은 후속 범위로 미구현.
- **Android SDK가 없어 app Kotlin 컴파일, APK, TTS·서비스·Compose 런타임, 실차는 미검증이다.** app 판단은 모두 정적 검토다.

---

## 이전 판정: 라운드3 재동결본 (2026-11-04 05:02, 대체됨)

**판정: 조건부. 전체 통과가 아니다.** core 단위 범위에서는 D1–D5, D7–D9, R1, R3의 해소와 기존·QA 기대 조건 유지를 독립적으로 확인했다. 다만 다음 항목이 남아 있다.
- R2: 정보 한계라 QA_REPRO에서 실패로 유지된다.
- **새 결함 R4(중)**: 이번 라운드에서 발견했고 미해결이다.
- 정책 한계 L1.
- app Kotlin 컴파일·APK·실차: 미검증.

따라서 출시 판정은 R4 처리와 Android 빌드·실차 확인이 끝날 때까지 보류한다.

| 항목 | 결과 |
|---|---|
| 소스 고정 | `dev-evidence/round3/final-source-sha256.json`(파일 SHA256 `4cc88c3235611fab981e9e42115b35443a0b5e14f4c561e4c84a05f4992bcf97`, 58개 파일: core 전체, app 변경 3개, QA 테스트 3개 포함). QA 독립 스냅샷, 작업 트리, 개발 `/tmp/splittesla-r3-final-5fr16a3b` 모두 실행 전후 **58/58 일치**. QA 테스트 파일 해시는 QA가 마지막에 쓴 내용과 같다(FinalProbe `f35e8de6…`). 즉 개발자가 QA 단언을 바꾸지 않았다 |
| 일반 `core clean test` | **137개 중 126개 통과, 11개 건너뜀, 실패 0**(`qa-evidence/round3/core-clean-test*`). 건너뜀은 QA_REPRO 게이트가 걸린 결함 재현·probe 테스트다 |
| `QA_REPRO=1` QA 32개 | **31개 통과, 1개 실패(R2)**(`qa-evidence/round3/qa-repro*`). 통과 31개의 구성: 1차 결함 재현 8개(D1–D5, D7–D9) + 동결본 probe R1·R3 + 정상 반례 21개(D4 포함 회귀, L1 정책 고정 포함). **R2는 따로 집계한 실패**로, 단언은 바꾸지 않았다 |
| 새 probe (manifest 밖 QA 파일 `AndroidGuidanceQaRound3ProbeTest`) | R4 실패, 대조군 통과(`qa-evidence/round3/round3-probe*`) |
| 개발 보고와 대조 | 개발 보고(일반 126/11/0, QA_REPRO 31/32이고 R2만 실패)와 독립 결과가 **일치**한다 |

### 라운드3 새 결함

| ID | 심각도 | 위치 | 재현 입력 | 기대 | 실제 |
|---|---|---|---|---|---|
| R4 | 중 | `RouteTracker.kt` `advance()`가 `positionUncertain`을 그대로 둠 + `GuidanceAnnouncer.kt:21` + `NaviHub.kt:448` | 60km/h, 1Hz 실제 fix로 200m 주행 → 터널 입구에서 정확도 80m fix 1개(FixGate 거절) → `advance`로 40초 DR(1000m 지점 우회전) | DR 중에도 턴 예고(implementation.md: "DR는 진행 표시/턴 예고를 지원") | **발화 0건**. 입구 fix만 뺀 대조군은 "우회전" 예고를 한다. 앱에서는 터널 입구의 흐린 fix가 흔하므로 터널 안이나 출구 직후 턴의 700/300m 예고가 빠지고, GPS가 돌아와도 건너뛴 단계는 늦게 말하지 않는다 |

R4는 `positionUncertain` 도입(R3 수정 과정)으로 생긴 결함이다. **PM 결정(msg_3da92a2f2c87): 기존 터널 턴 예고의 회귀이므로 이번 범위에 포함해 개발 수정한다.** 수용 기준은 다음과 같다. 신선한 차량 속도와 시간 제한(실제 fix 후 3–30초) 안의 DR에서는 예상 턴 예고를 허용한다. 실제 도착, 경유지 통과, 카메라 통과 확정은 계속 실제 fix로만 한다. 재검증 항목은 R4와 대조군 통과, D5(경유지·lastGuideIndex), D7·도착 조건, 기존 29개와 R1·R3 회귀 없음, R2 실패의 별도 집계 유지다.

### app 정적 계약 (컴파일 미확인)
- `NaviHub.handleFix`: FixGate 결과 `accepted`를 `RouteTracker.update(..., acceptedFix = accepted)`로 넘긴다. tracker 안의 FixGate는 건너뛰어 이중 재획득·이중 지연이 없다. 거절되면 `invalidatePositionEvidence()`, 도착 Job 취소, gpsOk=false로 처리한다.
- 도착: `arrived = !estimated && !positionUncertain && 확인 fix ≥2`. 15초 뒤에도 같은 tracker, arrived, 최근 3초 안의 실제 fix를 다시 확인한다. 도착 문구는 "부근에 도착했습니다."로 바뀌었고 "안내를 마칩니다"는 빠졌다(D7).
- 경유지·`lastGuideIndex`: `!estimated && !positionUncertain`일 때만 확정한다(D5).
- gpsOk: `!estimated && !positionUncertain && 최근 3초 실제 fix`.
- 카메라: `SpeedCameraService`의 호출 계약은 1차와 같다. 통과 판정은 실제로 승인된 GPS 선분을 카메라에 투영하는 방식으로 바뀌었다(D2). `passedCameras`는 warnedEarly와 같은 생명주기(50개 초과 시 1.5km 근방만 유지)를 따른다. 그래서 한 세션 안에서 같은 START를 다시 지나면 SectionStart가 다시 나지 않을 수 있다. 기존 1회 경고 정책과 같은 성격의 잔여 위험이다.
- 보존: CameraData 병합, RouteCameras 방향 필터, 설정 키 변경 없음(diff 대상 6개 파일에 없음).
- **Android SDK가 없어 app Kotlin 컴파일, APK, 기기·실차 실행은 미검증**이다. 위 내용은 코드 읽기 결과일 뿐이다.

### 잔여 위험·한계 (라운드3)
- R2: 첫 fix 신뢰성의 정보 한계로 약 10초 공백(아래 R2 처리 참조).
- L1: 경로 끝에서 45m 옆에 주차하면 자동 도착 없음(수동 종료).
- R1 개선 뒤에도 30초를 넘는 간격은 지원 범위 밖이다(개발 문서). 10초 간격은 첫 재획득까지 약 40초가 걸린다.
- 카메라 경고 공백: 경로 카메라 목록이 비어 있을 때, 터널 무수신 중, 재획득에 걸리는 시간 동안.
- 단일 TTS와 거리 프리셋은 미구현(후속 범위).

---

## 이전 판정: 동결본 재검증 (2라운드, 2026-11-04 04:33, 대체됨)

**판정: 조건부 불합격, 전체 통과 보류.** 1차 결함 8건(D1–D5, D7–D9)은 동결본에서 해소가 재현됐다. 그러나 동결 수정본에서 **새 결함 R3(중상)**와 R1(하)이 나왔다. 둘 다 PM이 개발자에게 넘겨 수정이 재개됐다(msg_4141fb30706b). 새 소스 해시가 나오면 다시 검증한다.

| 항목 | 결과 |
|---|---|
| 소스 고정 | 개발 manifest `dev-evidence/final-source-sha256.json`(파일 SHA256 `6c2940d6651c31865d20728020a0b12393e3cba13dd35240d4803fa6361337e5`, 56개 파일, core 전체와 app 변경 3개 포함). QA가 별도 스냅샷 디렉터리로 복사해 실행했다. 스냅샷, 작업 트리, 개발 `/tmp/splittesla-final-review-hcoa8owb` 모두 **56/56 일치**했고 실행 뒤에도 일치했다. 공유 `core/build`는 쓰지 않았다. analysis.md 변경은 PM이 확인한 문서 정정으로 manifest 대상이 아니다 |
| 일반 스위트 `core clean test` | **126개 중 118개 통과, 8개 건너뜀, 실패 0**, BUILD SUCCESSFUL(`qa-evidence/final/core-clean-test*.txt/log`). 건너뜀 8개는 QA 결함 재현 테스트이며 통과 수에 넣지 않았다 |
| `QA_REPRO=1` QA 29개 | **29/29 실제 통과**: 결함 재현 8개(D1–D5, D7–D9)와 정상 반례 21개(L1 정책 고정 포함). 1차에서 기대 실패였던 8개가 모두 통과로 바뀌어 해소로 판정한다(`qa-evidence/final/qa-repro*.txt/log`) |
| 기대 조건 유지 | 정상 반례 21개(속도 NaN/미래/오래됨, 고속, 정차, 반복 점프, 교차, 실거리 문구, 중복 발화, 빈 필터, 미확인 구간 숫자 금지, DR 상한)는 동결본에서도 모두 통과했다 |
| D6 → L1 | 정책 한계 유지(자동 도착 반경 확대 없음, 수동 "안내 종료"). 수정 완료로 세지 않는다 |
| 새 반례 (동결본, `AndroidGuidanceQaFinalProbeTest`, QA_REPRO 게이트) | 3개 모두 기대 실패로 재현(`qa-evidence/final/final-probe*.txt/log`). 일반 실행에서는 3개 모두 건너뜀 |
| app | 정적 검토만 했다. NaviHub가 FixGate 결과를 `RouteTracker.update(..., acceptedFix = accepted)`로 넘겨 이중 재획득을 막는 계약이 맞는 것을 확인했다. **Kotlin 컴파일, APK, 기기 실행은 미검증**(SDK 없음). 개발 `dev-evidence/android-build-after-fixes.log`도 독립 확인이 아니다 |

### 새 결함 (동결본 6c2940d6…)

| ID | 심각도 | 위치 | 재현 입력 | 기대 | 실제 |
|---|---|---|---|---|---|
| R3 | **중상** | `RouteTracker.kt` update의 local/global 투영 선택(`globalSupported`, `local`, reacquired 후진 허용) | 1000m 북진 후 30m 옆에서 1000m 남진하는 헤어핀 경로. 50km/h로 along 944까지 1Hz 주행 → 20초 GPS 공백(차량 속도 없음 = DR 없음) → 복귀 구간에서 1Hz fix 31개 | 몇 fix 안에 along≈1130 이상을 따라감 | FixGate 재획득 뒤 global(복귀 구간)은 "직선거리×1.5+40" 미지원으로 버려진다. local 창의 출발 구간(lateral 30m가 이탈 한도 50m 안)에 붙고 reacquired라 후진이 허용된다. 그래서 **차가 전진하는 동안 along이 944→483으로 계속 줄고**, offRoute가 되지 않아 재탐색도 없다. 31 fix 동안 회복이 없다 |
| R1 | 하 | `FixGate.kt` 저빈도 허용(`elapsed in 5001..8000`, 후보 간격 `500..8000`) | 60km/h, 10초 간격 fix 19개 | 대부분 승인 | 19개 중 1개만 승인하고 영구 거절. D1(6초)은 해소됐지만 8초를 넘는 간격에서 같은 유형이 남아 있다 |
| R2 | 하 (근본 한계) | `FixGate.kt` `betterBootstrap`(첫 fix 정확도 ≥20 조건) | 첫 fix가 500m 튀었는데 정확도 5m로 보고, 이후 60km/h 실제 궤적 | ≤3초에 승인 | 10초 뒤 승인. D8은 첫 fix 정확도 30m 조건에서만 해소됐다. 처음 위치가 믿을 만한지 판단할 근거가 없어 근본 한계로 분류(PM 동의) |

R2 처리 (PM msg_8bf382619d3d): R2 단언은 바꾸거나 지우지 않는다. 다음 라운드에서도 QA_REPRO 실패로 남을 수 있으며, **R1/R3 수정 검증과 따로 집계**한다. 남은 실패가 R2뿐이어도 "전체 통과"라고 쓰지 않는다. 현 정책의 성격은 다음과 같다. 안전성 측면에서는 정확도를 5m로 주장하는 첫 위치와 나중 후보를 구분할 추가 근거(차량 위치, 이전 세션 위치, 다중 소스)가 없다. 그래서 둘 중 하나를 빨리 믿으면 튄 위치를 확정할 위험이 생기므로 보수적 대기를 택했다. 대기 시간은 1Hz 기준 약 10초이고, 이 동안 카메라 경고와 진행 갱신이 공백이 된다. 개선하려면 추가 근거 신호가 필요하며 후속 후보로 둔다.

재현: `QA_REPRO=1 ./gradlew -p core test --tests 'com.splittesla.core.qa.AndroidGuidanceQaFinalProbeTest'` (JAVA_HOME은 위 JDK).

### 잔여 위험 (동결본)
- 카메라 경고 공백: 안내 중 경로 카메라 목록이 비어 있으면 0건이다(로딩, 재탐색, 계산 실패). 터널 무수신 중과 재획득에 걸리는 약 6초 동안도 공백이다.
- 단일 TTS·거리 프리셋은 미구현(후속 범위, §6).
- app 컴파일·실차·TTS 동시 발화는 미검증.

### 조건부 판정 문구
"core 단위 범위에서 1차 결함 8건 해소와 기존·QA 기대 조건 유지는 확인했다. 동결본에 새 결함 R3(중상)와 R1(하)이 남아 있어 불합격이다. R3·R1 수정본을 새 해시로 다시 검증해야 하고, app 컴파일·실차 검증은 별도로 필요하다."

---

## 0. 판정 요약 (1차 소스 기준, 조건부)

| 구분 | 결과 |
|---|---|
| 기존 core 테스트 재현 | `clean test`에서 **87/87 통과**(실패 0, 건너뜀 0). 개발 보고와 일치 |
| QA 정상 반례 | 당시 20개 모두 통과. 현재 21개이며, 추가된 1개는 D6을 정책 한계 L1로 바꾼 테스트다 |
| QA 결함 재현 | **9건 모두 기대 실패로 재현**. D1–D5, D7–D9가 결함이고 D6은 정책 한계로 재분류했다. 기대 실패는 통과 수에 넣지 않았다 |
| app Kotlin 컴파일 / APK / 기기·실차 | **미검증**. Android SDK가 없어 `assembleDebug`는 `SDK location not found`로 실패한다. 아래 app 항목은 모두 **정적 검토**이고 컴파일을 확인한 것이 아니다 |
| 단일 TTS·거리 프리셋 | 후속 범위로 **미구현**. 현 범위 결함과 구분한다(§5) |

**1차 판정: 조건부 불합격.** 현 범위 결함 8건이 있다(중상 1, 중 4, 하~중 1, 하 2). 개발자가 수정 중이므로 수정 완료 후 고정 소스 스냅샷에서 다시 판정한다(§6).

## 1. 검증 시점과 증거의 유효성

- `qa-evidence/round1-core-clean-test-existing87.log`: 1차 소스에서 기존 87개를 clean test로 돌린 로그. BUILD SUCCESSFUL.
- `qa-evidence/round1-results.txt`: QA 파일 추가 후 결과다. clean test는 116개 중 107개 통과, 9개 건너뜀(결함 재현), 실패 0. `QA_REPRO=1` 실행에서는 결함 재현 9개가 모두 실패했다. 같은 결과가 두 번 실행에서 동일하게 나왔다.
- **1차 실행 시점의 소스 해시는 남기지 못했다.** 그 뒤 개발자가 수정을 시작해 1차 소스는 다시 만들 수 없다.
- `qa-evidence/discarded-concurrent-edit/`: 실행 도중 구현 소스가 바뀌었고(해시 230128d0… → 912e2995…) 공유 `core/build`에서 동시 빌드가 일어났다. 결과는 123개 중 2개 실패, 결함 재현 0개 실패였다. **폐기했고 어떤 판정에도 쓰지 않는다.** 이때 생긴 클래스 소실과 실패는 동시 clean 빌드 충돌이며 제품 결함이 아니다.
- 개발자가 참고한 기존 경로 `qa-evidence/qa-repro-summary.txt`는 폐기한 실행의 산출물이라 `discarded-concurrent-edit/`로 옮겼다. 유효한 1차 근거는 `round1-results.txt`다.

재현 방법:

```sh
JAVA_HOME=/home/orca/project_folder/SplitTesla/tools/jdk-21 ./gradlew -p core clean test --no-daemon   # 정상 스위트: 결함 재현은 skipped
QA_REPRO=1 JAVA_HOME=... ./gradlew -p core test --tests 'com.splittesla.core.qa.*' --no-daemon     # 결함 재현: 수정 전에는 실패가 기대값
```

## 2. 결함 (현 범위, 1차 소스에서 재현)

| ID | 심각도 | 위치 | 재현 입력 | 기대 | 실제 | 분류 |
|---|---|---|---|---|---|---|
| D4 | 중상 | `RouteTracker.kt` update: grace/reacquire 중 `from=0..끝` 전체 탐색과 투영 후보 | 출발 경로 옆 10m에 평행한 복귀 구간이 있는 왕복 경로. 60km/h로 500m 주행 → `advance` 20초(터널) → 동쪽 6m 편향 fix를 1Hz로 16개 | 실제 위치 ≈1083m 부근 유지 | along=**3010m**(복귀 구간)을 수용. 이후 안내 전체가 어긋난다 | 신규 전체 재탐색 때문에 노출된 회귀 성격. 기존 +400 세그먼트 창도 짧은 경로에서는 같은 위험이 있었다 |
| D1 | 중 | `FixGate.kt` accept: `elapsed<=5000` 예산과 후보 간격 `500..2500` | 80km/h, 6초 간격 fix 31개(3분) | 대부분 승인 | **31개 중 1개만 승인**하고 이후 영구 거절. 카메라 경고·내비 진행이 전부 멈추고 gpsOk=false | 문서에 없는 회귀. 기존에는 모두 승인했다 |
| D9 | 중 | `FixGate.kt` `pendingReachM` 고정(첫 의심 시 허용거리) | 100km/h 1Hz, t=6에 400m 튐, t=7–8 무수신, t=9부터 실제 궤적 | 수 초 안에 복구 | t=15에야 승인. **약 10초 공백** | implementation.md에 "복구 지연 가능"으로 적힌 설계 지연이다. 다만 3초 무수신만으로 10초 공백이 생기므로 결함으로 분류 |
| D2 | 중 | `CameraAlerter.kt` passed 판정 `lastDistance < PASSED_NEAR_M(40)` | 2초 간격, 100km/h, 구간 START 카메라. 6개 위상 오프셋 | 모든 위상에서 SectionStart | **6개 중 1개 누락** | 정적 추정상 회귀다. 기존 기준은 80m였다. 1Hz·160km/h에서는 9개 위상 모두 통과(정상 테스트) |
| D8 | 하~중 | `FixGate.kt` 첫 fix를 무조건 기준점으로 채택 | 첫 fix가 500m 튐(정확도 30m), 이후 60km/h 실제 궤적 | ≤3초 안에 실제 궤적 승인 | **10초 뒤** 승인 | 근본 한계 + 설계 지연. 어느 쪽이 참인지 선험 정보가 없다. 명확한 회귀는 아니다. 기존에는 튐이 진행을 바로 옮겼다 |
| D5 | 하 | `RouteTracker.kt` update: `estimated=false`가 투영 점프 검사보다 먼저 실행됨 | 60km/h, 25초 DR(along≈583m) 뒤 1Hz 실제 fix | 실제 위치로 갱신하거나 estimated 유지 | 5–9번째 fix에서 along=DR값인데 **estimated=false**. NaviHub의 경유지 drop과 lastGuideIndex가 DR 값으로 확정될 수 있다(정적) | 결함 |
| D3 | 하 | `CameraAlerter.kt` onPassed END(`expected==null`이면 section 유지)와 정리 조건 | 미확인 구간 C3→C4를 100km/h로 통과 | END 통과 뒤 구간 상태 해제 | "종료 지점" 발화 후에도 `status.sectionLimitKmh=100` 유지(길이+2km 또는 20분까지). 알림 문구와 표시가 불일치 | 결함(UI·상태) |
| D7 | 하 | `GuidanceAnnouncer.kt` arrived 처리 + RouteTracker 철회 | 50km/h로 경로 끝을 지나쳐 계속 주행 | 확정 전에는 종료 문구 없음, 또는 실제로 종료 | "부근에 도착했습니다. **안내를 마칩니다**" 발화 후 arrived 철회, 안내 계속. 다시 도착해도 재발화 없음 | 결함(발화·상태 불일치) |

## 3. 정책 한계 (결함 아님, 수정 완료로 세지 않음)

- **L1 (구 D6)**: 경로 끝에서 45m 옆에 60초 주차해도 자동 도착이 확정되지 않는다. PM 결정(msg_acd8ed3444da)에 따라 평행 도로와 구분할 근거가 없으므로 반경을 늘리지 않는다. 사용자는 기존 수동 종료(`NaviPanel` "안내 종료", 알림 액션 "안내 종료" → `NaviHub.stop`)로 끝낸다. 정적 확인만 했다. 정차 후 사용자 확인 UI는 후속 후보다. 테스트 `policyLimitParkedFortyFiveMetresBesideTheEndIsNotAutoArrival`은 현 정책을 고정할 뿐 품질 통과의 근거가 아니다.
- 지속적으로 한 지점에 고정된 잘못된 GPS가 10초 이상 이어지면 재획득으로 수용된다. 지속 편향과 구분할 수 없다.
- FixGate 재획득에는 터널 뒤 1Hz 기준 약 6초가 걸린다(정상 테스트에서 ≤6초 확인). 이 시간 동안 카메라 경고가 공백이다.
- 터널 무수신 중에는 카메라 서비스로 DR 위치를 전달하지 않아 새 카메라 경고가 없다. implementation.md에 기록된 한계다.

## 4. 정상 반례 (1차 소스에서 통과)

| 영역 | 테스트 | 확인한 내용 |
|---|---|---|
| 속도 증거 | `speedEvidenceRejects…` | NaN, ±∞, 음수, 250 초과, null, 미래 시각, 시각 0, 3.001초 경과는 거부. 3.000초와 250은 허용 |
| DR 창 | `deadReckoningWindowIsBoundedOnBothSides` | 실제 fix 후 3.001–30초 사이만 허용. 오래된 BLE·미래 BLE·fix 없음은 거부 |
| 고속 | `highSpeedAt1HzIsNeverRejected` | 130/200/250km/h 1Hz에서 거절 0 |
| 저빈도 | `lowRateFixesUpToFiveSecondsApartAreAccepted` | 5초 간격 100km/h 모두 승인. 6초 간격은 D1 |
| 정차 | `stationaryJitter…`, `stationaryNearACameraWithoutSpeedDoesNotWarn` | 8m 지터는 승인되고 재획득으로 처리되지 않음. 속도가 없을 때 카메라 150m 앞 정차·6m 지터로 경고 없음 |
| 반복 점프 | `alternatingTrueAndJumpedFixes…` | 400m 교대 튐은 매번 거절되고 실제 fix는 매번 승인 |
| 시각 역전 | `duplicateAndBackwardsTimestamps…` | 같은 시각·과거 시각·음수 시각·위도 범위 밖·정확도 60m 초과 거절. 정확도 60m는 허용 |
| 후보 재획득 | `tunnelExitReacquires…` | 20초 무수신 후 ≤6초에 reacquired=true, continuous=false, derived=null |
| 속도·방위 누락 | `missingSpeedAndMissingBearing…` | 위치만으로 Final 경고를 내고 과속 주장 없음 |
| 중복 발화 | `eachCameraIsAnnouncedAtMostOnce…`, `arrivalAnnouncementIsSpokenOnce` | 튐 거절 뒤에도 단계별 1회. 도착은 1회 |
| 빈 경로 필터 | `emptyRouteFilter…` | emptySet이면 무경고, null이면 경고. 기존 동작 유지 |
| 구간 | `unverifiedSection…`, `verifiedPair…`, `sectionStart…AtEveryPhase` | 미확인 구간은 숫자 없이 "평균 속도 미확인". 검증된 짝은 평균 100±5. 1Hz·160km/h에서 START 누락 없음 |
| 실거리 문구 | `spokenDistances…` | 97→100m, 63→60m, 3→10m, 286→300m(Approaching). 과속 아니면 "줄이세요" 없음 |
| 경로 | `normalDrive…`, `crossingLaterLeg…`, `deadReckoningIgnoresNan…` | 정상 주행에서는 끝 전에 도착하지 않고 정차 2 fix로 도착. 교차하는 후속 구간으로 점프하지 않음. advance(NaN/∞/음수)는 무시하고 DR 상태는 도착이 아님 |

## 5. app 정적 검토 (컴파일 미확인)

코드를 읽고 core 계약과 맞춰 본 결과다. **실제 Kotlin 컴파일과 기기 실행은 하지 않았다.**

- `SpeedCameraService`: `hasSpeed`, 유한한 bearing, 정확도 ≤60, `elapsedRealtimeNanos` 기준 3초 나이를 확인한다. `update(..., speed ?: NaN, elapsedRealtime ms, measuredSpeedKmh=speed, accuracyM)`로 core 계약(측정 속도와 이동 판단 분리)에 맞는다. `lastAcceptedFixMs`와 1초 감시 루프는 같은 elapsedRealtime 시계를 쓴다. `routeCameraIds`는 안내 중 빈 집합도 그대로 전달한다.
  - 위험: 경로 전환이나 `loadCameras` 계산 중, 그리고 그 계산이 예외·데이터 없음으로 끝나면 **안내 내내 카메라 경고가 0건**이 된다(`NaviHub.loadCameras`는 실패 시 로그만 남김). 의도된 정책(전체 폴백 금지)이지만 경고 공백 위험이므로 실차·로그 확인이 필요하다.
  - `kotlin.math.roundToInt` import는 announce를 옮긴 뒤에도 알림 문자열(`SpeedCameraService.kt:161,163`)에서 계속 쓰인다(정적 확인).
- `NaviHub`: FixGate와 RouteTracker 이중 게이트, timestamp 전달, 재탐색 in-flight 차단, 15/30/60초 백오프, 오래된 응답 무시(`tracker?.route !== s.route`), `arrivalJob` 취소와 15초 후 재확인이 implementation.md 기술과 맞는다. `CarFeed.speedAt`의 기본값(`= at`)은 기존 호출과 호환된다. DR은 `deadReckoningKmh`(벽시계)와 `lastFixAt`(벽시계)로 같은 시계를 쓴다.
  - `replaceRoute`가 `handleFix(lastFix)`를 다시 부를 때 lastFix가 3초보다 오래됐으면 gpsOk=false가 다음 fix까지 일시 표시된다(경미).
- `AppViewModel`: 차량 속도는 Commander 값을 3초 신선도로 거르고, 없으면 `nav.speedKmh`와 `navUpdatedAt`(수신 시각)을 쓴다. Tesla nav 갱신 주기 때문에 실제로는 대부분 신선도에서 탈락한다. 기존 `d.speedKmh`(live) 대신 `CameraState.commanderSpeedKmh`를 쓴다. 두 값은 같은 Commander `delta.speedKmh` 원천(`AppViewModel.kt` onData)이며, 후자에만 실제 수신 시각이 붙는다(정적 확인).
- 보존 정책: `CameraData.mergeSameSpot`, `RouteCameras` 방향 필터(왼쪽 7m), CSV/저장 포맷, `camera_learn`·`camera_road_check`·`autoReroute`·`voice` 설정 키는 diff에 변경이 없다(`git diff`로 확인). 사용자 설정 동작은 유지된 것으로 판단한다.

## 6. 후속 범위 (미구현, 현 범위 결함과 별개)

- 단일 TTS 엔진·우선순위 큐: SpeedCameraService, NaviService, DriveAssistant가 각자 Speaker를 쓰므로 동시 발화가 미해결이다.
- 제한속도 기반 거리 프리셋(1000/500, 600/300, 300m): 미구현. 기본값은 속도×12초(200–600m)+100m로 유지했다.

## 7. 최종 재검증 계획 (개발 수정 완료 후)

1. 개발 완료 시점의 소스를 **별도 디렉터리 스냅샷**으로 복사하고 파일별 sha256 manifest를 기록한다. 공유 `core/build`는 쓰지 않는다.
2. 스냅샷에서 `core clean test`(정상 스위트)와 `QA_REPRO=1` 결함 재현을 실행하고, 실행 후 작업 트리 변경 여부를 기록한다.
3. 결함 재현 테스트가 통과로 바뀌면 해당 결함을 해소로 판정한다. 기존·QA 정상 테스트에 회귀가 없는지 확인한다. L1은 정책 유지로 별도 보고한다.
4. app 컴파일·APK·실차는 SDK가 없으므로 계속 미검증으로 남긴다.
