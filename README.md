# SplitTesla

테슬라 공식 BLE와 Enhance S3XY Commander BLE를 한 앱에서 같이 쓰는 안드로이드 앱입니다.
서버나 원격 API 없이 차 근처에서만 동작합니다. 그래서 차량에 목적지를 보내는 기능(Fleet API 전용)은 없습니다.

| 담당 | 테슬라 공식 BLE | S3XY Commander BLE |
|---|---|---|
| 잠금·트렁크·프렁크 | ✓ | |
| 충전 시작/중지, 한도, 전류, 충전구 | ✓ | |
| 공조, 온도, 감시 모드, 창문 환기 | ✓ | |
| 상태 조회 (충전, 공조, 타이어 공기압 등) | ✓ | |
| 내비: 경로·남은 시간·도착 배터리, 현재 위치, 주변 슈퍼차저 | ✓ | |
| 내비: 현재 속도와 제한속도 | | ✓ |
| 과속카메라 알림 (폰 GPS + 공공데이터, 속도별 사전 알림과 100m 최종 알림, 구간단속 평균속도) | 폰 | ✓ 속도 |
| 실시간 CAN 데이터 약 130개 (속도, 출력, 셀 전압·온도, 사각지대 등) | | ✓ |
| 주행 모드, 트랙 모드, 미러, 비상등, 상향등 스트로브 등 100개 동작 | | ✓ |

## 구조

- `core/`: 순수 Kotlin/JVM 라이브러리. 두 BLE 프로토콜(암호화, 세션, 메시지)을 구현하고 안드로이드 없이 테스트합니다.
  - `tesla/`: 공식 vehicle-command 프로토콜 (ECDH P-256, AES-GCM, HMAC 세션)
  - `s3xy/`: Commander 프로토콜 (X25519 + AES-CTR, protobuf 수동 인코딩)
- `app/`: Jetpack Compose 앱. BLE 연결(`ble/`), 화면(`ui/`)

## 빌드

GitHub Actions가 PR과 push마다 테스트를 돌리고 APK를 아티팩트로 올립니다. 서명 키는 [docs/배포.md](docs/배포.md)를 보세요.
직접 빌드하려면 JDK 17과 Android SDK가 필요합니다.

```
./gradlew -p core test        # 프로토콜 테스트
./gradlew :app:assembleRelease  # APK
```

실차 테스트 순서는 [docs/실차테스트.md](docs/실차테스트.md)를 보세요.
