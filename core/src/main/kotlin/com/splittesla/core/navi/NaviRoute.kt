package com.splittesla.core.navi

import com.splittesla.core.assistant.LatLon

/** Route search options of NAVER Directions 5/15 (`option=`). */
enum class RouteOption(val code: String, val label: String) {
    FAST("trafast", "빠른길"),
    OPTIMAL("traoptimal", "추천"),
    COMFORT("tracomfort", "편한길"),
    AVOID_TOLL("traavoidtoll", "무료도로"),
    AVOID_CAR_ONLY("traavoidcaronly", "자동차전용 회피"),
    ;

    companion object {
        fun ofCode(code: String): RouteOption? = entries.firstOrNull { it.code == code }
    }
}

/** A place to drive to: a search hit, a geocoded address or a charger. */
data class NaviPlace(
    val name: String,
    val point: LatLon,
    val address: String = "",
    val category: String = "",
)

/** A stretch of a major road on the route (Directions `section`). */
data class RouteSection(
    val pointIndex: Int,
    val pointCount: Int,
    val distanceM: Int,
    val name: String,
    /** 0 unknown, 1 smooth, 2 slow, 3 congested. */
    val congestion: Int,
    val speedKmh: Int,
)

/** One turn-by-turn instruction (Directions `guide`); distance and time run from the previous guide point. */
data class RouteGuide(
    val pointIndex: Int,
    val type: Int,
    val instructions: String,
    val distanceM: Int,
    val durationMs: Long,
) {
    val maneuver: Maneuver get() = Maneuver.of(type, instructions)
}

/** A route as NAVER Directions returns it, with the request it answers. */
data class NaviRoute(
    val option: RouteOption,
    val path: List<LatLon>,
    val sections: List<RouteSection>,
    val guides: List<RouteGuide>,
    val distanceM: Int,
    val durationMs: Long,
    val tollFare: Int,
    val goal: NaviPlace,
    val waypoints: List<NaviPlace> = emptyList(),
    val fetchedAt: Long = 0,
) {
    val durationMin: Double get() = durationMs / 60_000.0
}

/** What the dashboard arrow shows, from the guide type code and its wording. */
/**
 * The kind of manoeuvre at a guide point, as navigation apps draw it. Forks,
 * exits and merges come in left and right because the icon depends on the
 * side; [symbol] is a plain-text stand-in for the notification.
 */
enum class Maneuver(val label: String, val symbol: String) {
    STRAIGHT("직진", "↑"),
    LEFT("좌회전", "←"),
    RIGHT("우회전", "→"),
    SLIGHT_LEFT("11시 방향", "↖"),
    SLIGHT_RIGHT("1시 방향", "↗"),
    SHARP_LEFT("8시 방향", "↙"),
    SHARP_RIGHT("4시 방향", "↘"),
    KEEP_LEFT("왼쪽 방향", "↖"),
    KEEP_RIGHT("오른쪽 방향", "↗"),
    EXIT_LEFT("왼쪽 진출", "↖"),
    EXIT_RIGHT("오른쪽 진출", "↗"),
    MERGE_LEFT("왼쪽 합류", "↑"),
    MERGE_RIGHT("오른쪽 합류", "↑"),
    OVERPASS("고가차도", "↑"),
    UNDERPASS("지하차도", "↑"),
    UTURN("유턴", "↶"),
    ROUNDABOUT("회전교차로", "⟳"),
    TOLL("요금소", "▤"),
    WAYPOINT("경유지", "◆"),
    DESTINATION("목적지", "⚑"),
    ;

    companion object {
        /**
         * NAVER Directions 5 guide codes (the 안내 종류 table): 1 straight, 2 left, 3 right,
         * 4/5 keep left/right, 6 U-turn, 8 unprotected left, 11–16 the clock directions
         * (8, 9, 11 o'clock left; 1, 3, 4 o'clock right), 21–34 and 91–104 roundabouts,
         * 41/42 onto the road on the left/right, 47 into a rest area, 50/51/54 highway entry,
         * exit or fork straight ahead, 55/56 overpass or underpass ahead, 57/58 and 66/67
         * highway entry/exit on the left/right, 62–65 and 71–74 overpass or underpass, or the
         * side road beside it, on the left/right, 81/82 merge from the left/right,
         * 87 waypoint, 88 destination, 121–123 tollgates. Others go by the wording.
         */
        fun of(type: Int, text: String): Maneuver = when (type) {
            1, 50, 51, 54, 48, 49 -> STRAIGHT
            2, 8, 12 -> LEFT
            3, 15 -> RIGHT
            4, 41, 57, 62, 63, 64, 65 -> KEEP_LEFT
            5, 42, 66, 71, 72, 73, 74 -> KEEP_RIGHT
            6 -> UTURN
            11 -> SHARP_LEFT
            13 -> SLIGHT_LEFT
            14 -> SLIGHT_RIGHT
            16 -> SHARP_RIGHT
            in 21..34, in 91..104 -> ROUNDABOUT
            47, 67 -> EXIT_RIGHT
            58 -> EXIT_LEFT
            55 -> OVERPASS
            56 -> UNDERPASS
            81 -> MERGE_LEFT
            82 -> MERGE_RIGHT
            87 -> WAYPOINT
            88 -> DESTINATION
            in 121..123 -> TOLL
            else -> fromText(text)
        }

        private fun fromText(t: String): Maneuver {
            val left = "왼쪽" in t
            return when {
                "유턴" in t || "U턴" in t -> UTURN
                "회전교차로" in t || "로터리" in t -> ROUNDABOUT
                "요금소" in t || "톨게이트" in t -> TOLL
                "목적지" in t || "도착" in t -> DESTINATION
                "경유지" in t -> WAYPOINT
                "합류" in t -> if (left) MERGE_LEFT else MERGE_RIGHT
                "출구" in t || "진출" in t || "나가" in t -> if (left) EXIT_LEFT else EXIT_RIGHT
                "좌회전" in t -> LEFT
                "우회전" in t -> RIGHT
                left -> KEEP_LEFT
                "오른쪽" in t -> KEEP_RIGHT
                "고가" in t -> OVERPASS
                "지하차도" in t || "터널" in t -> UNDERPASS
                else -> STRAIGHT
            }
        }
    }
}
