package com.example.safetymonitor.detection.events

import kotlin.math.abs

/** Experimental environment-matched trend; not core temperature or a clinical threshold. */
class SkinTrendRule {
    private val calibration = mutableListOf<Pair<Double, Double>>()
    var baseline: Double? = null
        private set
    private var ambientBaseline: Double? = null
    private var environment: SkinEnvironment? = null
    private var count = 0
    private var direction = 0
    private val reported = mutableSetOf<Int>()
    var status = "손목·주변 온도 수신 대기 · 온도 판단 대기"
        private set

    fun evaluate(value: Double?, contiguous: Boolean, rise: Double, ambient: Double?): Boolean {
        require(rise.isFinite() && rise > 0.5)
        if (value == null || !value.isFinite() || value !in 10.0..50.0 ||
            ambient == null || !ambient.isFinite() || ambient !in -30.0..70.0) {
            count = 0
            calibration.clear()
            status = "손목 또는 주변 온도 누락 · 온도 판단 대기"
            return false // Missing data never proves recovery.
        }
        val nextEnvironment = when {
            ambient <= 15.0 -> SkinEnvironment.COLD
            ambient >= 30.0 -> SkinEnvironment.HOT
            else -> SkinEnvironment.NORMAL
        }
        // Never reuse a baseline across environments or a large local ambient change.
        if (environment != nextEnvironment || ambientBaseline?.let { abs(ambient - it) >= 3.0 } == true) {
            environment = nextEnvironment
            baseline = null
            ambientBaseline = null
            calibration.clear()
            count = 0
            direction = 0
            reported.clear()
        }
        if (baseline == null) {
            if (!contiguous) calibration.clear()
            calibration += value to ambient
            if (calibration.size == 6) {
                val skin = calibration.map { it.first }.sorted()
                val air = calibration.map { it.second }.sorted()
                if (skin.last() - skin.first() <= 1.0 && air.last() - air.first() <= 2.0) {
                    baseline = (skin[2] + skin[3]) / 2
                    ambientBaseline = (air[2] + air[3]) / 2
                } else calibration.removeAt(0)
            }
            status = "${nextEnvironment.label} · 주변 %.1f°C · 기준선 준비 %d/6 · 온도 판단 대기".format(ambient, calibration.size)
            if (baseline != null) status = "${nextEnvironment.label} · 손목 기준선 %.1f°C 설정 · 다음 구간부터 판단".format(baseline)
            return false
        }
        status = "${nextEnvironment.label} · 주변 %.1f°C · 손목 기준선 %.1f°C · ±%.1f°C 연속 두 구간 확인".format(ambient, baseline, rise)
        val delta = value - baseline!!
        if (abs(delta) < rise) {
            count = 0
            direction = 0
            status = "손목 온도 정상 범위 · " + status
            if (abs(delta) < rise - 0.5) reported.clear()
            return false
        }
        val next = if (delta > 0) 1 else -1
        count = if (contiguous && direction == next) count + 1 else 1
        direction = next
        status = (if (count >= 2) "손목 온도 이상 감지 · " else "손목 온도 이상 의심 · 다음 구간 확인 중 · ") + status
        if (count < 2 || next in reported) return false
        reported += next
        return true
    }
}
