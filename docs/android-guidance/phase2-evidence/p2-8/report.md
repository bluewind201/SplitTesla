# P2-8 안정적 안내 단계 수정

후속 task_9be73a108b40. QA 소유 f7/f7d/f7e 프로브·단언·문서를 읽었으며 수정하지 않았다. 기존 개발 문서 phase2.md도 이번 작업에서는 바꾸지 않고 증거 디렉터리에만 기록한다. SDK 약관 미승인, 기존 R2/L1/L2/30m·DR·도착·카메라 최종 선점 계약은 유지한다.

## 재현

이전 동결7bddc8a6의 core 생산43파일과 같은 사본을 확인(`baseline-production-verification.json`)하고, 신규 개발5개 및 QA 원본 최종 프로브10개를 실행했다. **15개 중8개 실패**(`baseline-probes.log`, `baseline-result.json`, `baseline-xml/*`). QA 실패는 f7, f7d, f7e이며 개발5개도 모두 실패했다.

- f7: 처음148m에서85km/h인 마지막 안내 뒤100m 안쪽에서70km/h로 감속하면 기존 재생 경로가 마지막 안내를 두 번 내는 반례를 재현했다.
- f7d:78km/h 접근 중150~80m에서만82km/h였다가78로 돌아오면144m·78m 마지막 안내가 각각 나오는 회귀를 재현했다. legacy next는1회다.
- f7e: 마지막 안내 뒤 가속하는 경우의 중복은 legacy next도 같은 동작이라 **새 회귀로 집계하지 않는다**. 이번의 안정적 최종 단계 모델이 이를 함께 억제하지만 신규 결함을 하나 더 고쳤다고 세지 않는다.

최초 baseline 재현에는 개발5개가 들어갔다. 이후 아직 듣지 못한 예고의 속도 구간 전환 갱신을 검사하는 개발1개를 더해 최종 개발 테스트는6개다. 수정 중 최초 core-normal.log는 중간 근거이며 최종 판정은 동결 후 실행의 frozen-core-normal.log와 harness.log를 따른다.

## 원인과 구현

기존 `(guide, stageIndex)`는 속도 구간별 의미가 달랐다. high의 stage2는500m 예고이고 stage3가 최종이지만 medium의 stage2는 최종이다. 완료 집합과 마지막 단계의 said 제거가 이 차이를 반영하지 못해 이미 들은 최종 안내가 새 최종 단계로 생성됐다.

재생 경로를 legacy said 인덱스 소비에서 분리했다. 완료·대기·중단·재안내 key는 **guide와 안정적 stepId**를 사용한다.

- 최종턴은 모든 속도 구간에서0, 도착은−1, 예고는 실제 임계거리(예:300/500m)를 ID로 쓴다. 같은300m 예고가 속도 구간에 따라 인덱스0/1이 되어도 다른 완료 상태가 되지 않는다.
- 한 guide의 단계는 거리가 가까워지는 방향으로만 전이한다. 이미 완료된 단계나 최종 안내가 속도 변경으로 지워지거나 더 먼 예고로 돌아가지 않는다. 최종 완료는 이후 같은 guide의 모든 예고/최종 중복을 막는다.
- 최종 단계에 진입한 뒤 감속해도 최초 최종 진입 임계값을 유지한다. 중단된 최종은 여전히 앞에 있고 현재성을 검증할 수 있을 때 새 attempt로 재생성한다. 새 속도 구간의 더 가까운 마지막 인덱스를 기다리게 하거나 예고 문구로 바꾸지 않는다.
- 아직 듣지 못한 예고는 속도 구간이 바뀌어도 안정적인 거리 단계로 유지하고 현재 거리로 갱신한다. 더 가까운 현재 단계가 오면 새 단계가 우선한다. 완료된 예고를 먼 단계로 되돌려 반복하지 않는다.
- PlaybackNotice에는 안정적 단계와 실제 유효성 검사용 임계값 profile을 함께 담는다. NaviHub는 이 metadata를 TurnSpeechContext에 전달한다. event key도 stableStage를 사용하며, 시작 직전 검증은 해당 profile의 실제 거리 전이를 확인한다. 레거시 문맥 생성자는 기존 stage 방식과 호환된다.

현재 정보 갱신, 대기 만료/중단 뒤 새 attempt, 실제 시작 예약, 최종 카메라 선점, 완료 뒤 반복 금지, 지난 턴·불확실 위치·경로 변경 시 금지는 유지한다. legacy next의 문장/거리 정책은 변경하지 않았고 공통 문구 생성만 공유한다.

수정 구현은 GuidanceAnnouncer.kt, TurnSpeechContext.kt, NaviHub.kt이며 개발 StablePlaybackPhaseTest.kt6개를 추가했다. QA 원본과 독립 Q1 시나리오·피드백·실제 전달 하네스는 그대로 복사해 재검증한다. 새 SDK 설치·약관 수락·유료 호출·외부배포·커밋·푸시는 하지 않았다.

## 최종 동결

`source.sha256`의 manifest SHA256은 `175a2c8222cdd4dc566a27d3ea2e523ee9d0e015758f128d78f13b1ef17c944c`다. 이 해시 이후 소스 변경 없이 전체 core clean test, core QA_REPRO, 기존 Q1/실제 전달 하네스 및 원본 최종 프로브를 검증한다. Android SDK 약관 미승인 때문에 실제 NaviHub/Compose의 android.jar 기반 컴파일과 기기 실행은 계속 미검증이다.

## 동결 검증 결과

- `frozen-core-normal.log`의 전체 `clean test`: **226개 중 176 통과, 50 skip, 실패 0**. 개발 안정 단계 테스트 6개 포함.
- `core-qa.log`의 `QA_REPRO=1` QA 재현: **71개 중 70 통과, 실패 1**. 유일 실패는 기존 R2 정보한계 `AndroidGuidanceQaFinalProbeTest.r2_badFirstFixWithGoodClaimedAccuracyRecoversWithinThreeSeconds`; R2를 완화하지 않았다.
- 확장 독립 Q1/서비스 전달 하네스 `harness.log`: **70개 중 69 통과, 실패 1**. 10개 Q1 최종 프로브(f7/f7d/f7e 포함) 전부 통과; Q1 API10, policy8, Speaker19, 전달 harness7, playback contract7도 각각 전부 통과. 유일 실패 O1 `SpeakerOutcomeWiringTest.obs_expiryIsReportedWithoutWaitingForTheNextQueueActivity`는 만료가 다음 큐 활동 때 관찰되는 기존 동작으로, 본 변경에서 건드리지 않았다.
- 최종 QA 원본 프로브 f7/f7d는 수정 후 기대 횟수로 통과했다. f7e도 통과했지만 baseline legacy next와 동일 중복이므로 신규 회귀 해소에는 포함하지 않는다.
- `source.sha256` 검증 전 항목 OK; 동결 해시 `175a2c8222cdd4dc566a27d3ea2e523ee9d0e015758f128d78f13b1ef17c944c`. `source-changes.json`는 변경/추가 구현 및 개발 테스트 파일을 기록한다. `git diff --check`는 출력 없이 성공했다. QA 소유 파일은 수정하지 않았다.
- 남은 검증 한계: SDK 라이선스 미승인 상태를 지켰으므로 Android app/Compose Gradle 컴파일과 기기 실행은 불가/미검증이다. 독립 QA가 동결된 manifest에서 재실행할 수 있도록 baseline/final XML 및 로그와 최종 manifest를 이 디렉터리에 보관했다.
