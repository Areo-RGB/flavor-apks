package com.paul.sprintsync.core.services

enum class SessionConnectionRole {
    NONE,
    HOST,
    CLIENT,
}

enum class SessionConnectionStrategy(
    val wireValue: String,
) {
    POINT_TO_POINT("point_to_point"),
    POINT_TO_STAR("point_to_star"),
    ;

    companion object {
        fun fromWireValue(rawValue: String?): SessionConnectionStrategy {
            return values().firstOrNull { it.wireValue == rawValue } ?: POINT_TO_POINT
        }
    }
}
