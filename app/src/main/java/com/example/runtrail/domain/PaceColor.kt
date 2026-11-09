package com.example.runtrail.domain

/**
 * PACE SCALE จาก Figma: เร็ว (เขียว) → คงที่ (เขียวมะนาว) → ปานกลาง (เหลือง) → ช้า (ส้มแดง)
 */
object PaceColor {

    const val FAST = 0xFF0EDA62.toInt()
    const val STEADY = 0xFFA3E635.toInt()
    const val MODERATE = 0xFFF5B83D.toInt()
    const val SLOW = 0xFFF0603F.toInt()

    /** แบ่งระดับเพซ: เร็ว < 5:50 · คงที่ ≤ 6:15 · ปานกลาง ≤ 6:40 · ช้ากว่านั้น */
    fun forPace(secPerKm: Int): Int = when {
        secPerKm < 350 -> FAST
        secPerKm <= 375 -> STEADY
        secPerKm <= 400 -> MODERATE
        else -> SLOW
    }

    fun label(secPerKm: Int): String = when (forPace(secPerKm)) {
        FAST -> "ช่วงเร็ว"
        STEADY -> "ช่วงคงที่"
        MODERATE -> "ช่วงปานกลาง"
        else -> "ช่วงช้า"
    }
}
