package com.example.runtrail.domain

import com.example.runtrail.data.PersonalRecord
import com.example.runtrail.data.Run
import kotlin.math.roundToInt

/**
 * สูตรคำนวณ (ฟังก์ชันล้วน ไม่ผูก Android)
 */
object RunCalculator {

    /** เพซ วินาทีต่อกม. */
    fun paceSecPerKm(movingTimeS: Long, distanceM: Double): Double =
        if (distanceM <= 0) 0.0 else movingTimeS / (distanceM / 1000.0)

    fun pace(run: Run): Double = paceSecPerKm(run.movingTimeS, run.distanceM)

    /**
     * แคลอรี่ = MET × น้ำหนัก(กก.) × ชั่วโมง
     * MET จากสมการ ACSM: VO2 = 0.2 × ความเร็ว(ม./นาที) + 3.5 → MET = VO2 / 3.5
     */
    fun calories(distanceM: Double, movingTimeS: Long, weightKg: Int): Double {
        if (movingTimeS <= 0) return 0.0
        val speedMPerMin = distanceM / (movingTimeS / 60.0)
        val met = (0.2 * speedMPerMin + 3.5) / 3.5
        return met * weightKg * (movingTimeS / 3600.0)
    }

    /** สถิติดีที่สุดจากทุกการวิ่ง */
    fun records(runs: List<Run>, now: Long = System.currentTimeMillis()): List<PersonalRecord> {
        val recent = now - 7 * Periods.DAY_MS
        val out = mutableListOf<PersonalRecord>()

        // 1 กม. เร็วสุด = split ที่เร็วที่สุด
        var best1: Pair<Int, Run>? = null
        runs.forEach { run ->
            run.splits.filter { it.km <= run.distanceM / 1000.0 }.forEach { s ->
                if (best1 == null || s.paceSecPerKm < best1!!.first) best1 = s.paceSecPerKm to run
            }
        }
        best1?.let { (sec, run) ->
            out += PersonalRecord("1 กม. เร็วสุด", Fmt.pace(sec), run.startTime, run.startTime >= recent)
        }

        fun bestFor(km: Double, title: String) {
            val best = runs.filter { it.distanceM >= km * 1000 }
                .minByOrNull { it.movingTimeS * (km * 1000 / it.distanceM) }
            if (best != null) {
                val t = (best.movingTimeS * (km * 1000 / best.distanceM)).roundToInt().toLong()
                out += PersonalRecord(title, Fmt.duration(t), best.startTime, best.startTime >= recent)
            } else {
                out += PersonalRecord(title, "--:--", 0, false)
            }
        }
        bestFor(5.0, "5 กม. เร็วสุด")
        bestFor(10.0, "10 กม. เร็วสุด")

        runs.maxByOrNull { it.distanceM }?.let {
            out += PersonalRecord("วิ่งไกลสุด", "${Fmt.distance(it.distanceM, decimals = 1)} กม.", it.startTime, it.startTime >= recent)
        }
        return out
    }

    /** การวิ่งนี้ทำสถิติใหม่ไหม (ใช้กับป้าย "สถิติใหม่ 5 กม.") */
    fun newRecordLabel(run: Run, all: List<Run>): String? {
        for (km in listOf(10.0, 5.0)) {
            if (run.distanceM < km * 1000) continue
            val mine = run.movingTimeS * (km * 1000 / run.distanceM)
            val others = all.filter { it.id != run.id && it.distanceM >= km * 1000 && it.startTime < run.startTime }
            val bestOther = others.minOfOrNull { it.movingTimeS * (km * 1000 / it.distanceM) }
            if (bestOther == null || mine < bestOther) return "สถิติใหม่ ${km.toInt()} กม."
        }
        return null
    }
}
