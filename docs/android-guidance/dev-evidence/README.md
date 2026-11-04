# 개발자 검증 근거

- 최종 소스 고정 manifest: `final-source-sha256.json`.
- 최종 일반 스위트와 QA_REPRO 결과는 각각 별도의 로그·XML·집계로 보존한다.
- `android-build-after-fixes.log`: SDK 미설치에 따른 assembleDebug 실패 원문. APK/app Kotlin 검증 통과가 아니다.
- `qa-before-fixes.log`: 공유 작업 트리에서 관찰한 개발자 참고 로그. 소스 고정 해시가 없으므로 유효한 1차 또는 최종 QA 판정 근거로 쓰지 않는다.
- 유효한 1차 QA 자료는 QA 소유 `../qa.md`, `../qa-evidence/round1-results.txt`. `qa-repro-summary.txt` 및 `discarded-concurrent-edit/`는 폐기 자료이므로 어떤 통과·실패 판정에도 사용하지 않는다.
