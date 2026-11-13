package com.example.runtrail.domain

/** Pace colour scale: fast (green) → steady (lime) → moderate (yellow) → slow (orange-red). */
object PaceColor {

    const val FAST = 0xFF0EDA62.toInt()
    const val STEADY = 0xFFA3E635.toInt()
    const val MODERATE = 0xFFF5B83D.toInt()
    const val SLOW = 0xFFF0603F.toInt()

    /** Bands: fast < 5:50, steady ≤ 6:15, moderate ≤ 6:40 per km, slow otherwise. */
    fun forPace(secPerKm: Int): Int = when {
        secPerKm < 350 -> FAST
        secPerKm <= 375 -> STEADY
        secPerKm <= 400 -> MODERATE
        else -> SLOW
    }

    fun label(secPerKm: Int): String = when (forPace(secPerKm)) {
        FAST -> Lang.t("ช่วงเร็ว", "Fast")
        STEADY -> Lang.t("ช่วงคงที่", "Steady")
        MODERATE -> Lang.t("ช่วงปานกลาง", "Moderate")
        else -> Lang.t("ช่วงช้า", "Slow")
    }
}
