# SplitTesla 안드로이드 안내(카메라·길안내·음성) 강화 분석

- 작업공간: `/home/orca/orca/workspaces/SplitTesla/android-guidance` (브랜치 `bluewind201/android-guidance`)
- 기준: `origin/claude/project-thread-51js0y` = HEAD `6f21050` (원격 중 커밋일 최신, 2026-11-02). `origin/main`은 `470a58e`로 뒤처짐.
- AGENTS.md / CLAUDE.md: 저장소에 없음.
- 대조 문서: `/home/orca/project_folder/SplitTesla/docs/analysis/naver-guidance-ux.md`, `.../docs/analysis/evidence/rea-ledger.md`(E1~E35). 웹 MVP QA 보고(`docs/qa/report.md`)는 예외 시나리오만 참고했다(7.1절).
- 범위: 구현 코드는 읽기만 했고 수정·커밋하지 않음. 유료 API 호출 없음. 분석 당시 빌드는 미실행·미확인이었다. 개발 이후 core 테스트는 통과했고 app은 SDK가 없어 실패한다(7절).

## 1. 기존 동작 (코드 근거)

| 영역 | 동작 | 위치 |
|---|---|---|
| 카메라 데이터 | 공공 표준데이터 CSV/API → `camera()`로 필터(단속구분 1/2/1+2만, 이동식 제외, 제한 10–150) → `mergeSameSpot`(위경도 1/5000° 반올림 + 제한 + 구간 키로 묶음, 2건 이상이면 `bothWays=true`) | `core/camera/CameraData.kt` |
| 방향 정보 | `도로노선방향` 3 또는 "양방향"만 `bothWays`. 1/2는 "한 방향"이지만 **어느 쪽인지 데이터에 없음** | `CameraData.kt:camera()` |
| 경로 카메라 | 경로선 35 m 이내, `bothWays`가 아니면 진행방향 **왼쪽 7 m 초과 시 제외**(d197733) | `core/navi/RouteCameras.kt` |
| 실시간 카메라 경고 | 1 Hz GPS → `CameraAlerter.update`: 진행 경로(곡률 보정) 위·거리 감소 중인 카메라 → 조기 경고(속도×12 s, 200–600 m) 1회 + 100 m 최종 1회. 과속 = `speed > limit`. 안내 중이고 이탈 아님 → 경로 카메라만 | `core/camera/CameraAlerter.kt`, `app/camera/SpeedCameraService.kt` |
| 오경고 억제 | 사용자 "이 방향 무시"(`id@heading`, ±45°), 3회 연속 옆으로 빗나가면 자동 무시(`camera_learn`, 기본 on), Commander 도로 제한과 30 km/h 이상 차이 → 다른 도로(`camera_road_check`, 기본 on, 보호구역 제외) | `CameraRepository.kt` |
| 구간단속 | 시작 카메라 통과 → GPS 점 간 거리 누적/경과시간 = 평균. 종료 통과 시 평균 발화. 길이+2 km 초과 시 해제 | `CameraAlerter.onPassed` |
| 속도 원천 | 카메라: Commander(BLE/CAN) 속도 3 s 이내면 사용, 아니면 `location.speed` | `SpeedCameraService.onLocationChanged` |
| 경로 추적 | 직전 위치 −3 ~ +400 세그먼트 투영, 45 m+정확도(≤40) 초과가 3회 연속(정확도 ≤30 m인 fix만 카운트)이면 이탈. 뒤로는 30 m까지만 | `core/navi/RouteTracker.kt` |
| 터널 | 안내 중 정확도 >60 m fix 무시. 3 s 무수신 & 속도>3 → 1초마다 `advance(속도)`; 이후 8 fix 유예, 400 m 역보정 허용 | `NaviHub.handleFix/startLoops` |
| 턴 안내 | 현재 속도로 단계 선택(≥80: 2000/1000/500/150, ≥40: 700/300/80, 그 외 300/100/30), (안내, 단계)당 1회, 건너뛴 단계는 말하지 않음 | `core/navi/GuidanceAnnouncer.kt` |
| 도착 | 남은 거리 <30 m → 1회 발화, 15 s 후 안내 종료 | `GuidanceAnnouncer`, `NaviHub.afterProgress` |
| 음성 | `Speaker`(TTS, 내비 안내 오디오 속성, 덕킹)를 **서비스마다 따로** 생성: SpeedCameraService(카메라는 `interrupt=true`), NaviService, DriveAssistant | `app/assistant/Speaker.kt` |
| 어시스턴트 중복 억제 | 이벤트 종류별 `REPEAT_EVENT_MS` 간격, 앱 내 안내 중이면 경로 시작/도착 생략 | `DriveAssistant.announce` |

## 2. 네이버 관찰값과의 구분

| 항목 | 네이버 관찰(신뢰도) | 현재 앱 | 판단 |
|---|---|---|---|
| 경고 거리 기준 | **제한속도 구간**: ≥80 → 1000/500 m, 50–80 → 600/300 m, <50 → 300 m, 창 ±100/75/50 m (E4–E6, 높음) | 현재 속도×12 s(200–600 m) + 100 m | 다름. 관찰값은 "참고 프리셋"이며 우리 데이터(공공 좌표 오차)에 그대로 맞는다는 보장 없음 |
| 과속 판정 | `trunc(limit)+buffer < trunc(speed)`, 스쿨존 buffer 0, 기본 0 추정 (E12–E13, E34 중) | `speed > limit` | 거의 같음. 버퍼 설정 없음 |
| 위치 무효 시 | 경고음 억제 (E10, 높음) | 정확도 무시, 무효 판단 없음 | 결함 |
| 후면 카메라 유지 | 150/125/100 m (E4, 높음) | 데이터에 후면 속성 없음 | 적용 불가(데이터 부재) |
| 구간 평균 | 계산식 **미확인** (E2) | GPS 거리 누적 | 우리 설계 |
| 반대 방향 필터 | 서버가 경로 기준으로 내려준다는 **추론**, 클라이언트 방향각 필터 **미확인** (1.5절) | 왼쪽 7 m 규칙 | 네이버 근거 없음 → 우리 휴리스틱 |
| 재탐색 | 연속 3회(2/4), 정확도 >30/40/50 m 시 +1/+2/+3 (E25–E27, 높음) | 3회, 정확도 >30 m fix는 카운트 안 함 | 유사. 가산 방식은 선택 사항 |
| 추정항법 | 횡편차 200 m 초과 GPS 무시(E32), 시간 임계 **미확인** | 최대 지속시간·속도 신선도 제한 없음 | 결함 |
| 턴 단계 거리 | 구조(일반/고속 분리)만 관찰, 값 **미확인** (E16, E18) | 속도 기반 3표 | 값은 우리 설계로 유지 |

"미확인" 항목은 네이버 값처럼 표기하지 않는다. 아래 변경안의 숫자는 별도 표기가 없으면 **우리 설계값**이다.

## 3. 방향 미상 카메라: 불확실성과 균형

데이터 사실: 표준데이터는 방향을 1/2/3 코드로만 주고, 1/2가 실제로 어느 차로인지 알 수 없다. 좌표는 교차로 중앙 등 공유 지점인 경우가 많다(`CameraAlerter` 주석, 7cb8abd 커밋).

현재 정책(d197733)의 위험:
1. **미탐(놓침)**: 한 방향 레코드(코드 1/2)가 교차로 중앙·중앙분리대 쪽에 찍혀 있으면 우리 방향 카메라인데도 왼쪽 >7 m로 빠진다. 왕복 6~8차로에서 도로 중심선은 진행 경로에서 이미 7~15 m 왼쪽이다.
2. **병합 경계 문제**: `mergeSameSpot`은 `Math.round(lat*5000)` 격자 키라 1 m 떨어진 두 방향 레코드도 격자 경계를 넘으면 따로 남아 `bothWays=false` → 각자 7 m 규칙에 걸림. 제한속도가 다른 두 방향(예: 60/50)도 따로 남는다.
3. 이 제외는 **안내 중에만** 적용되고(경로 카메라 집합 → `routeCameraIds`), 안내가 없을 땐 `CameraAlerter`가 방향 필터 없이 경고한다. 즉 같은 카메라가 안내 여부에 따라 사라졌다 나타난다.

권장 균형(전체 억제 금지):
- 제외 대신 **"방향 불확실" 등급**으로 강등: 왼쪽 7~25 m는 경고는 하되(최종 100 m 경고만, 진동 약하게) 화면에 "방향 미확인" 표시. 25 m 초과 + 한 방향 코드 + 분리 도로(경로 근처에 반대 방향 차로로 보이는 구간) 근거가 있을 때만 제외.
- 사용자 학습 데이터 우선: `camera_ignored`(무시)와 `recordPass`(실제 통과)를 경로 필터보다 우선 적용. 통과 기록이 있는 카메라는 7 m 규칙과 무관하게 유지.
- 설정 토글 추가 `camera_strict_direction`(기본 **off** = 놓침 최소화). 켜면 현재 7 m 정책.
- 공공 카메라 기능이 사라지지 않게 회귀 테스트: 기존 데이터(병합 후 `bothWays` 비율)와 무시 목록을 로그로 집계해 정책 변경 전후 경고 수 비교.

## 4. 확인된 결함 (우선순위)

### P0 — 안전·누락
1. **GPS 속도 누락 시 카메라 경고 전면 중단**: `speed = canSpeed ?: location.speed*3.6` — `hasSpeed()`가 false면 0 → `moving=false` → 후보 없음. BLE 미연결 + 속도 없는 fix에서 경고가 조용히 사라진다. (`SpeedCameraService.kt` onLocationChanged)
2. **터널 안 카메라 무경고**: 카메라 서비스는 fix가 와야만 동작하고 추정항법이 없다. 길안내의 `RouteTracker.advance`는 카메라 쪽에 전달되지 않음. 터널 내 고정식/구간단속 종료를 놓침.
3. **오래된 BLE 속도로 추정항법 폭주**: `NaviHub`의 `car.speedKmh`는 `CarFeed.at` 신선도를 확인하지 않는다(카메라 쪽은 3 s 확인함). BLE가 끊긴 뒤에도 마지막 속도로 무한히 전진 → 턴 단계 건너뜀(건너뛴 단계는 말하지 않음) 및 거짓 도착 가능. 최대 추정 시간 제한도 없음.
4. **단발 튐(spike)**: 위치 점프 검증이 없다. `RouteTracker`는 앞쪽 400 세그먼트(약 3 km)까지 바로 이동 허용 → 한 번 튄 fix로 턴 안내 건너뜀/거짓 `arrived`. `arrived`가 한 번 true면 15 s 뒤 무조건 종료(`tracker === t`만 확인, 실제 도착 재확인 없음). 카메라 쪽은 튄 fix가 구간 평균(`sectionMeters`)을 부풀림.

### P1 — 정확도·중복
5. **TTS 3개 독립 엔진 겹침**: 카메라(`QUEUE_FLUSH`)는 자기 엔진만 비우고 내비/어시스턴트 발화와 동시에 나온다. 반대로 같은 엔진 안에선 카메라 경고가 진행 중 문장을 끊는다. 우선순위·직렬화 없음.
6. **"100미터 앞" 고정 문구**: `Final`은 실제 거리(처음 감지가 40 m일 수도)와 무관하게 "100미터 앞". 조기 경고 없이 바로 Final인 경우 늦은 경고임을 알 수 없음.
7. **경고 거리**: 속도×12 s 상한 600 m라 고속도로 100 km/h 이상에서 네이버 관찰(1000/500 m)보다 늦다. 반대로 공공 좌표 오차가 커서 1 km 앞 경고는 오경고 노출도 늘어남 → 설정으로 선택.
8. **구간단속 짝**: 시작 카메라와 종료 카메라의 짝을 확인하지 않는다(가장 최근 START만 기억). 다른 구간 종료를 지나도 평균 발화, 시작을 놓치면(터널·필터) 종료 발화 없음. 경로 카메라 목록(`RouteCameras`)에선 START/END를 따라 순서로 짝지을 수 있는데 쓰지 않음.
9. **도착 중복**: 마지막 안내(목적지 guide, 단계 마지막 30/80/150 m)에서 "목적지" 문장 + 30 m에서 "목적지 … 부근에 도착했습니다" 두 번. 경유지 도착 발화 없음.
10. **재탐색 발화 2회 + 실패 반복**: "경로를 벗어나 다시 찾습니다." + "새 경로로 안내합니다." 실패 시 15 s마다 앞 문장 반복.
11. **빈 경로 카메라 = 전체 경고**: 경로 위 카메라가 0개면 `takeIf { isNotEmpty() }`로 null → 안내 중에도 주변 모든 카메라 경고(옆 도로 오경고). 카메라 로딩 전과 "진짜 0개"를 구분 못 함.

### P2
12. 과속 버퍼 설정 없음(스쿨존 0 규칙 포함). 13. `Speaker` 준비 전 `queued`가 마지막 1개만 보관(첫 안내 유실 가능). 14. 정확도·provider 상태 `gpsOk`가 카메라 쪽에선 fix 수신만으로 true.

## 5. 구체적 Kotlin 변경안

### P0-A 속도 선택기 (core, 순수 함수)
```kotlin
// core/camera/SpeedSource.kt (신규)
data class SpeedSample(val kmh: Double, val source: Source) { enum class Source { CAN, GPS, DERIVED, HELD } }

class SpeedSelector(private val canFreshMs: Long = 3_000, private val holdMs: Long = 5_000) {
    private var last: SpeedSample? = null; private var lastAt = 0L
    private var prev: Triple<Double, Double, Long>? = null // lat, lon, t
    fun select(now: Long, canKmh: Double?, canAt: Long, gpsKmh: Double?, lat: Double, lon: Double, fixMs: Long): SpeedSample? {
        val derived = prev?.let { (pl, po, pt) ->
            val dt = (fixMs - pt) / 1000.0
            if (dt in 0.5..5.0) Geo.distanceM(pl, po, lat, lon) / dt * 3.6 else null }
        prev = Triple(lat, lon, fixMs)
        val s = when {
            canKmh != null && now - canAt < canFreshMs -> SpeedSample(canKmh, SpeedSample.Source.CAN)
            gpsKmh != null -> SpeedSample(gpsKmh, SpeedSample.Source.GPS)
            derived != null && derived < 250 -> SpeedSample(derived, SpeedSample.Source.DERIVED)
            last != null && now - lastAt < holdMs -> last!!.copy(source = SpeedSample.Source.HELD)
            else -> null }
        if (s != null && s.source != SpeedSample.Source.HELD) { last = s; lastAt = now }
        return s
    }
}
```
`SpeedCameraService`: `location.speed*3.6` 대신 `selector.select(...)`; null이면 이번 fix 건너뛰고 상태에 "속도 정보 없음" 표시(조용한 실패 금지). `NaviHub`도 같은 선택기 사용 → **BLE 신선도 3 s** 적용(P0-3).

### P0-B fix 검증기 (튐·무효)
```kotlin
// core/navi/FixGate.kt (신규)
class FixGate(private val maxAccuracyM: Double = 60.0, private val maxJumpKmh: Double = 250.0) {
    private var last: Triple<Double, Double, Long>? = null; private var rejected = 0
    enum class Verdict { OK, VAGUE, SPIKE }
    fun check(lat: Double, lon: Double, accuracyM: Double?, timeMs: Long): Verdict {
        if (accuracyM != null && accuracyM > maxAccuracyM) return Verdict.VAGUE
        val l = last
        if (l != null) {
            val dt = ((timeMs - l.third) / 1000.0).coerceAtLeast(0.2)
            val v = Geo.distanceM(l.first, l.second, lat, lon) / dt * 3.6
            // 두 번 연속 같은 쪽으로 튀면 진짜 이동으로 받아들임(멈춤 방지)
            if (v > maxJumpKmh && rejected < 2) { rejected++; return Verdict.SPIKE }
        }
        rejected = 0; last = Triple(lat, lon, timeMs); return Verdict.OK
    }
}
```
- `CameraAlerter.update` 앞, `NaviHub.handleFix` 앞에 적용. SPIKE는 구간 거리 누적에서도 제외.
- `RouteTracker.update`: 앞쪽 점프 상한 `maxForwardM = max(150, 속도×dt×1.5)` 인자 추가; 넘으면 `along` 유지하고 `pendingJump` 카운트 2회 연속일 때만 수용.
- 도착: `arrivedFixes >= 2`(연속 2회 <30 m) 또는 `속도 < 10 && remaining < 50`일 때만 `arrived`. 15 s 종료 직전 `t.progress().arrived` 재확인.

### P0-C 추정항법 제한과 카메라 연동
- `NaviHub.startLoops`: `drStartedAt` 기록, `DR_MAX_MS = 120_000`(설계값) 초과 또는 속도 소스가 HELD/null이면 전진 중단, `gpsOk=false` 표시.
- `NaviHub`에 `val drProgress = MutableStateFlow<Double?>(null)` 노출. `SpeedCameraService`는 fix가 3 s 넘게 없고 안내 중이면 1 Hz 타이머로 `RouteCameras.next(cams, along)`까지의 경로상 거리로 `CameraAlerter.updateAlong(cam, distanceM, speed, now)`(신규, onPath/closing 검사 생략) 호출 → 터널 안 카메라·구간 종료 발화.
- 구간 평균은 DR 동안 `sectionMeters += 경로상 전진 거리`.

### P1-A 단일 발화 큐 (`app/assistant/SpeechBus.kt`, 신규)
```kotlin
enum class Priority { CAMERA_FINAL, CAMERA, TURN, REROUTE, ASSISTANT }
object SpeechBus {
    data class Item(val text: String, val priority: Priority, val key: String, val expiresAt: Long)
    // 단일 Speaker 하나. 높은 우선순위가 오면 ASSISTANT만 끊고(QUEUE_FLUSH), TURN/CAMERA끼리는 이어 붙임.
    // key(예: "cam:<id>:final", "turn:<i>:<stage>")로 30 s 내 중복 제거, expiresAt 지난 항목(예: 턴 안내 8 s)은 버림.
}
```
세 서비스의 `Speaker(...)` 생성을 `SpeechBus.speaker(context)` 공유 인스턴스로 교체(참조 카운트로 shutdown). `Speaker.queued`는 리스트로.

### P1-B 카메라 경고 거리 프리셋 (설정형)
```kotlin
enum class CameraDistanceMode { SPEED_BASED /*현재, 기본*/, LIMIT_BASED /*네이버 관찰 프리셋*/ }
data class Preset(val minLimit: Int, val pointsM: List<Double>, val tolM: Double)
val LIMIT_PRESETS = listOf(Preset(80, listOf(1000.0, 500.0), 100.0), Preset(50, listOf(600.0, 300.0), 75.0), Preset(0, listOf(300.0), 50.0)) // E4 관찰값
```
`CameraAlerter`에 `mode` 인자. LIMIT_BASED에선 지점마다 `[max(d−tol,0), d+tol]` 창 안 1회 발화(`warned: Set<Pair<id, idx>>`), 창을 건너뛴 경우(fix 간격) 다음 지점에서 실제 거리로 발화. `Final` 문구는 `distanceWords(실거리)` 사용(P1-6). 과속은 `trunc(limit) + buffer < trunc(speed)`, `zone == "어린이보호구역"`이면 buffer 0(E12–E13), `camera_speed_buffer` 기본 0.

### P1-C 구간단속 짝
- 안내 중: `RouteCameras.along` 결과에서 START 다음 END를 짝지어 `RouteSection(startId, endId, lengthM = end.along − start.along)` 생성 → `CameraAlerter.sectionPairs` 주입. END 통과 시 `section?.id`가 짝과 맞을 때만 평균 발화, 시작 누락이어도 짝 정보가 있으면 "구간단속 종료" 1회(평균 없이).
- 안내 없음: 기존 동작 유지 + 시작 후 다른 START 통과 시 재시작.

### P1-D 방향 정책 (3절)
```kotlin
// RouteCameras.along: 제외 대신 등급
data class RouteCamera(val camera: SpeedCamera, val alongM: Double, val directionSure: Boolean = true)
if (!c.bothWays && hit.rightM < -oppositeSideM) {
    if (strictDirection && hit.rightM < -STRICT_EXCLUDE_M /*25*/) continue
    out += RouteCamera(c, hit.alongM, directionSure = false); continue
}
```
`mergeSameSpot`: 격자 키 대신 ±25 m 이내·제한속도 차이 ≤10 클러스터링(인덱스 이웃 셀 검색)으로 `bothWays` 판단 → 격자 경계 누락 해소. 저장 포맷 불변.

### P1-E 발화 정리
- 도착: 목적지 guide(`Maneuver.DESTINATION`)의 마지막 단계는 생략하고 도착 문장 하나만. 경유지 통과 시 "경유지 ○○를 지났습니다" 1회.
- 재탐색: "경로를 벗어나 다시 찾습니다" 한 번만; 성공 시 문장 없음(또는 짧은 효과음), 실패는 지수 백오프(15/30/60 s) 후 화면 메시지만.
- 경로 카메라: `cameras: List<RouteCamera>?`(null=로딩 중 → 전체 경고, 빈 리스트=경로 위 0개 → 경로 근처 카메라만).

## 6. 호환성과 테스트 계획

호환성: SharedPreferences 키 기존 유지(`camera_ignored`, `camera_misses`, `camera_learn`, `camera_road_check`), 신규 키는 기본값이 현재 동작과 같거나(거리 모드 SPEED_BASED, 버퍼 0) 놓침을 줄이는 쪽(`camera_strict_direction`=false — 이것만 d197733 대비 동작 변화이며 PM 결정 필요). `cameras.tsv` 포맷 변경 없음. 공개 API(`CameraAlert`, `RouteProgress`)는 필드 추가만.

core 단위 테스트(JVM, `core/src/test/...`, 기존 `CameraTest.kt` 285줄·`NaviTest.kt` 227줄에 추가):
| 시나리오 | 기대 |
|---|---|
| hasSpeed=false fix 연속, BLE 없음 | DERIVED 속도로 Approaching 발생 |
| BLE 속도 마지막 수신 10 s 전 + GPS 없음 | DR 전진 중단, 턴 단계 건너뜀 없음 |
| 단발 300 m 튐 1회 | `along`·구간 거리 불변, 2회 연속이면 수용 |
| 튐으로 remaining<30 1회 | arrived=false, 안내 계속 |
| 터널 90 s 무수신, 경로상 카메라 | DR 거리로 Final 1회 |
| 구간 START→다른 구간 END | 평균 발화 없음 |
| 한 방향 레코드 왼쪽 12 m | strict=false: 경고(directionSure=false) / strict=true: 7–25 m 경고, 25 m 초과 제외 |
| 두 방향 레코드가 격자 경계로 1 m 분리 | 병합되어 bothWays=true |
| LIMIT_BASED, 제한 100, fix 간격 30 m | 1000·500 m 창 각 1회, 이후 없음 |
| 스쿨존 30, buffer 5, 속도 33 | overLimit=true |
| 목적지 접근 | 도착 문장 정확히 1회 |
| 같은 key 발화 30 s 내 2회 | SpeechBus 1회 |

app 측: SpeechBus는 TTS를 인터페이스로 감싸 Robolectric 없이 가짜 엔진으로 순서·우선순위 검증. 실차 체크리스트는 `docs/실차테스트.md`에 터널·BLE 끊김 항목 추가 제안.

## 7. 환경·빌드 가능 여부

### 7-A. 분석 당시 (미확인)
- 분석 중에는 빌드와 테스트를 실행하지 않았다. 시스템 PATH에 `java`가 없었고, 시스템 경로(`/usr/lib/jvm`, `~/.jdks`, sdkman)만 찾아봤다. 저장소 바깥의 `/home/orca/project_folder/SplitTesla/tools/jdk-21`은 확인하지 않았다. 그래서 "JDK 없음, Gradle 테스트 불가"라고 쓴 것은 **잘못된 단정**이었고, 실제로는 "미확인"이었다.
- `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`local.properties`가 없다는 점은 그때 확인했다.

### 7-B. 개발 이후 확인된 사실 (출처: [`implementation.md`](implementation.md) 환경 절)
- **core**: 기존 설치 `/home/orca/project_folder/SplitTesla/tools/jdk-21`(Temurin 21.0.13+9)을 `JAVA_HOME`으로 지정했다. 새로 설치한 것은 없다. 실행 명령은 `JAVA_HOME=... ./gradlew -p core clean test --no-daemon -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=768m'`이고, 결과는 **87개 테스트, 실패 0, BUILD SUCCESSFUL**이다. `./gradlew :core:test --offline`은 플러그인 캐시가 없어 실패했다. core가 included build이므로 `-p core` 형태로 실행해야 한다. 이 수치는 개발자가 보고한 것이며, 분석 담당은 다시 실행하지 않았다.
- **app**: `./gradlew :app:assembleDebug`가 **실패**한다(`SDK location not found`). Android SDK가 없는 상태는 그대로다. app의 Kotlin 컴파일, APK 생성, TTS·서비스·실차 동작은 **미검증**이다.

- 기준 테스트(HEAD 기준, 미실행): `NaviTest.kt`는 경로 길이, 진행, 이탈 3회, 뒤쪽 흔들림, 추정항법, 흐린 fix, 터널 직후 유예, 터널 초과 보정, 안내 단계 1회+도착, 경로 카메라 순서, 좌우 부호, 반대 차로 제외를 다룬다. **없는 것**: 속도 누락, BLE 신선도, 앞쪽 튐, 거짓 도착, 구간 짝, 발화 중복.
- 참고: 이 분석 도중 같은 작업공간에서 다른 역할(개발)이 구현 파일을 수정하고 있었다(`FixGate.kt`, `CameraAnnouncement.kt`, `CameraAlerter.kt`, `RouteTracker.kt`, `NaviHub.kt` 등이 미커밋 상태). 이 문서는 **HEAD `6f21050` 기준**이며, 그 변경은 검토하지 않았고 건드리지도 않았다.

## 7.1 웹 MVP QA 보고서에서 가져온 예외 시나리오 (코드 이식 없음)

`/home/orca/project_folder/SplitTesla/docs/qa/report.md`는 시나리오만 참고했다. 안드로이드에 해당하는 항목:

| QA 항목 | 웹에서 확인된 현상 | 안드로이드 대응 |
|---|---|---|
| QA-11 | GPS 속도 null을 0으로 다뤄 진행 상한이 고정됐고, 108 km/h를 넘으면 계속 거절됐다 | 5절 P0-A·B 설계 시 **속도 없음을 0으로 취급 금지**. 튐 판정은 직전에 승인한 좌표에서 추정한 속도를 쓰고, 그것도 없으면 물리 상한(웹 50 m/s, 우리 250 km/h ≈ 69 m/s)을 쓴다. 테스트: 100/130/150/180 km/h, 속도 null, 1 Hz에서 신뢰 비율 90% 초과 |
| QA-04 / QA-07 | 경로 방향으로 한 번 튄 fix가 카메라 300 m 창이나 회전 300/100 m 단계를 먼저 소비했다 | 거절한 fix(SPIKE/VAGUE)는 `GuidanceAnnouncer`·`CameraAlerter`에 **절대 넘기지 않는다**. 테스트: 튐 샘플에서는 발화 0회, 이후 실제 위치에서 각 단계 1회 |
| QA-06 | 구간 종점 너머로 튄 fix 때문에 "구간 종료"가 일찍 나오고 추적이 끝났다 | 종료는 END 카메라를 지난 뒤 신뢰할 수 있는 fix가 2회 이어질 때만 판정한다(종료 안내가 1주기 늦어지는 트레이드오프). 뒤로 튄 fix가 `sectionMeters`를 되돌리거나 부풀리지 않게 한다 |
| QA-08 | 도착 확인이 약했다 | 5절 P0-B의 연속 2회 도착 조건. 테스트: 도착 안내 정확히 1회, 이후 안내·재탐색 0회, 주차장 안에서 움직여도 이탈 판정 없음 |
| G12 | 터널 추정항법이 웹에서는 구현되지 않았다 | 안드로이드는 이미 구현돼 있다. 지속 시간과 속도 신선도 제한만 추가한다(P0-C) |
| QA R.4-3 | 잘못된 GPS가 연속으로 오면 판별할 수 없다 | `FixGate`가 2회 연속 튀면 받아들이는 것도 같은 한계다. 실차 로그로 보정해야 한다 |

## 8. 남은 한계
- 위 결함은 코드를 읽고 찾은 것이며 분석 단계에서는 재현 테스트로 확인하지 않았다(이후 core 테스트 결과는 implementation.md 참고). 특히 4-9 도착 중복과 4-5 TTS 겹침은 실기기 확인이 필요하다.
- 기준 테스트는 함수 목록만 검토했고, 단언 본문은 일부만 봤다. `NaviPrefs`·`TrafficEvents`·UI는 검토하지 않았다.
- DR 120 s, 튐 250 km/h, 25 m, 백오프 같은 숫자는 우리 설계값이며 네이버 값이 아니다. 실제 GPS 노이즈로 보정하지 않았다.
- 방향 정책 기본값(`camera_strict_direction`=false)은 PM이 결정해야 한다.
