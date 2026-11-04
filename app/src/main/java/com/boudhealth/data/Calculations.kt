package com.boudhealth.data

/**
 * v5 (D7): the "How values are calculated" registry is retired (the app presents data, it doesn't explain it; the
 * method notes live in docs/BACKEND.md). Empty shim so older callers still compile; remove with the screen.
 */
data class CalcInfo(
    val id: String,
    val section: String,
    val name: String,
    val formula: String,
    val inputs: String,
    val parameter: String? = null,
)

@Deprecated("v5: retired, always empty")
object Calculations {
    val all: List<CalcInfo> = emptyList()
    fun bySection(): Map<String, List<CalcInfo>> = emptyMap()
}
