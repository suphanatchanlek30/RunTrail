package com.example.runtrail.domain

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Unit conversions. The weekly goal is always stored in km, matching the server; miles only change display. */
object Units {

    /** Distance in the selected unit (km or miles). */
    fun inUnit(meters: Double, miles: Boolean): Double =
        if (miles) meters / 1000.0 / Fmt.KM_PER_MILE else meters / 1000.0

    /** Weekly goal in metres. */
    fun goalMeters(goalKm: Int): Double = goalKm * 1000.0

    /** Goal as shown in the selected unit: "20" km or "12.4" mi. */
    fun goalText(goalKm: Int, miles: Boolean): String =
        if (!miles) goalKm.toString()
        else String.format(Locale.US, "%.1f", inUnit(goalMeters(goalKm), true))

    /** Percent of the weekly goal reached; 0 when there is no goal. */
    fun goalPercent(doneMeters: Double, goalKm: Int): Int {
        val goal = goalMeters(goalKm)
        return if (goal <= 0) 0 else (doneMeters / goal * 100).roundToInt()
    }

    /** Axis tick values for a distance chart in the selected unit, at most about six. */
    fun axisMarks(meters: Double, miles: Boolean): List<Int> {
        val total = ceil(inUnit(meters, miles) - 1e-9).toInt().coerceAtLeast(1)
        val step = if (total > 6) ceil(total / 5.0).toInt() else 1
        return (0..total step step).toMutableList().apply { if (last() != total) add(total) }
    }
}
