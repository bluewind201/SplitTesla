package com.splittesla.core.camera

/**
 * A small built-in list so camera alerts work right after install, before
 * the full national dataset ("전국무인교통단속카메라표준데이터", data.go.kr)
 * is imported. Positions are approximate samples for major roads; the
 * imported dataset replaces them.
 */
object CameraSeed {
    private const val TSV = """
SEED-001	37.55470	126.97070	50		 	서울역 앞 (기본 시드·근사 위치)		S	B
SEED-002	37.49790	127.02760	50			강남역 사거리 (기본 시드·근사 위치)		S	B
SEED-003	37.56650	126.97800	50			서울시청 앞 (기본 시드·근사 위치)		S	B
SEED-004	37.52700	126.92550	50			여의대로 (기본 시드·근사 위치)			B
SEED-005	37.35950	127.10520	50			분당 정자동 (기본 시드·근사 위치)			B
SEED-006	37.36200	127.11200	30			정자동 초등학교 앞 (기본 시드·근사 위치)	어린이보호구역		B
SEED-007	37.51700	127.04700	60			영동대로 (기본 시드·근사 위치)			B
SEED-008	37.48300	127.03300	60			양재대로 (기본 시드·근사 위치)			B
SEED-009	37.40500	127.11500	100	START	3000	경부고속도로 판교 구간단속 시점 (기본 시드·근사 위치)
SEED-010	37.37800	127.11800	100	END	3000	경부고속도로 판교 구간단속 종점 (기본 시드·근사 위치)
SEED-011	35.15800	129.06000	50			부산 서면 (기본 시드·근사 위치)		S	B
SEED-012	35.87100	128.60100	50			대구 반월당 (기본 시드·근사 위치)		S	B
"""

    val cameras: List<SpeedCamera> by lazy { CameraStore.decode(TSV.trim('\n')) }
}
