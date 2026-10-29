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
enum class Maneuver(val label: String, val symbol: String) {
    STRAIGHT("직진", "↑"),
    LEFT("좌회전", "↰"),
    RIGHT("우회전", "↱"),
    KEEP_LEFT("왼쪽 방향", "↖"),
    KEEP_RIGHT("오른쪽 방향", "↗"),
    UTURN("유턴", "↶"),
    ROUNDABOUT("회전교차로", "⟳"),
    ENTER("진입", "⤴"),
    EXIT("진출", "⤵"),
    TOLL("요금소", "▤"),
    WAYPOINT("경유지", "◆"),
    DESTINATION("목적지", "⚑"),
    ;

    companion object {
        /**
         * NAVER's guide codes: 1 straight, 2 left, 3 right, 6 U-turn, 8 unprotected left,
         * 21–34 roundabouts, 50–82 highway entries/exits/junctions, 87 waypoint, 88 destination,
         * 121–123 tollgates. Codes not listed fall back to the instruction text.
         */
        fun of(type: Int, text: String): Maneuver = when (type) {
            1 -> STRAIGHT
            2, 8 -> LEFT
            3 -> RIGHT
            6 -> UTURN
            in 21..34 -> ROUNDABOUT
            87 -> WAYPOINT
            88 -> DESTINATION
            in 121..123 -> TOLL
            else -> fromText(text)
        }

        private fun fromText(t: String): Maneuver = when {
            "유턴" in t -> UTURN
            "회전교차로" in t || "로터리" in t -> ROUNDABOUT
            "요금소" in t || "톨게이트" in t -> TOLL
            "목적지" in t -> DESTINATION
            "경유지" in t -> WAYPOINT
            "출구" in t || "진출" in t || "나가" in t -> EXIT
            "진입" in t || "합류" in t -> ENTER
            "좌회전" in t -> LEFT
            "우회전" in t -> RIGHT
            "왼쪽" in t -> KEEP_LEFT
            "오른쪽" in t -> KEEP_RIGHT
            else -> STRAIGHT
        }
    }
}
