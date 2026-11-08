package com.example.safetymonitor.detection.beacon

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal fun solvePosition(ranges: List<BeaconRange>): BeaconPosition {
    if (ranges.size < 3) {
        return BeaconPosition.Unavailable("서로 다른 비콘 3개 이상 필요")
    }

    val validById = LinkedHashMap<Int, BeaconRange>(ranges.size)
    val conflictedIds = HashSet<Int>()

    for (range in ranges) {
        val config = range.config
        val distance = range.distanceMeters
        if (!BeaconProtocol.isValid(config) || !distance.isFinite() || distance <= 0.0) {
            continue
        }
        if (conflictedIds.contains(config.id)) {
            continue
        }
        val existing = validById[config.id]
        if (existing != null) {
            validById.remove(config.id)
            conflictedIds.add(config.id)
        } else {
            validById[config.id] = range
        }
    }

    val usable = validById.values.toList()
    if (usable.size < 3) {
        val reason = if (conflictedIds.isNotEmpty()) {
            "동일 ID 비콘 충돌 감지 (서로 다른 비콘 3개 이상 필요)"
        } else {
            "서로 다른 비콘 3개 이상 필요"
        }
        return BeaconPosition.Unavailable(reason)
    }

    val count = usable.size
    var meanX = 0.0
    var meanY = 0.0
    for (i in 0 until count) {
        val cfg = usable[i].config
        meanX += cfg.xCm / 100.0
        meanY += cfg.yCm / 100.0
    }
    meanX /= count.toDouble()
    meanY /= count.toDouble()

    val u = DoubleArray(count)
    val v = DoubleArray(count)
    val residual = DoubleArray(count)
    var meanResidual = 0.0

    for (i in 0 until count) {
        val item = usable[i]
        val ui = (item.config.xCm / 100.0) - meanX
        val vi = (item.config.yCm / 100.0) - meanY
        val d = item.distanceMeters
        val ri = (ui * ui) + (vi * vi) - (d * d)
        u[i] = ui
        v[i] = vi
        residual[i] = ri
        meanResidual += ri
    }
    meanResidual /= count.toDouble()

    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    var rhsX = 0.0
    var rhsY = 0.0

    for (i in 0 until count) {
        val ui = u[i]
        val vi = v[i]
        val bi = 0.5 * (residual[i] - meanResidual)
        sxx += ui * ui
        sxy += ui * vi
        syy += vi * vi
        rhsX += ui * bi
        rhsY += vi * bi
    }

    val trace = sxx + syy
    val det = (sxx * syy) - (sxy * sxy)
    val discSq = max(0.0, (sxx - syy) * (sxx - syy) + 4.0 * sxy * sxy)
    val disc = sqrt(discSq)
    val lambdaMax = 0.5 * (trace + disc)
    val lambdaMin = 0.5 * (trace - disc)

    if (!det.isFinite() ||
        det <= 0.0 ||
        lambdaMax < MIN_GEOMETRY_SPREAD_SQ ||
        lambdaMin < MIN_EIGENVALUE ||
        (lambdaMin / lambdaMax) < MIN_CONDITION_RATIO
    ) {
        return BeaconPosition.Unavailable("비콘 배치가 일직선에 가까워 위치를 계산할 수 없음")
    }

    val localX = ((syy * rhsX) - (sxy * rhsY)) / det
    val localY = ((sxx * rhsY) - (sxy * rhsX)) / det
    val xMeters = meanX + localX
    val yMeters = meanY + localY

    if (!xMeters.isFinite() || !yMeters.isFinite()) {
        return BeaconPosition.Unavailable("유효한 위치 좌표를 계산할 수 없음")
    }

    return BeaconPosition.Available(
        xMeters = xMeters,
        yMeters = yMeters,
        anchorCount = count
    )
}

private const val MIN_GEOMETRY_SPREAD_SQ = 1e-2
private const val MIN_EIGENVALUE = 1e-4
private const val MIN_CONDITION_RATIO = 1e-3

class BeaconPositionEstimator {
    private val slotsById = LinkedHashMap<Int, BeaconIdSlot>()
    private val medianScratch = IntArray(MAX_SAMPLES)
    private var latestNowElapsedMillis: Long = Long.MIN_VALUE
    private var hadAnyObservation: Boolean = false

    fun observe(
        config: BeaconConfig,
        rssi: Int,
        observedAtElapsedMillis: Long,
        nowElapsedMillis: Long = observedAtElapsedMillis
    ) {
        if (!BeaconProtocol.isValid(config)) {
            return
        }
        if (rssi !in MIN_VALID_RSSI..MAX_VALID_RSSI) {
            return
        }
        if (observedAtElapsedMillis < 0L || nowElapsedMillis < 0L || observedAtElapsedMillis > nowElapsedMillis) {
            return
        }

        val effectiveNow = if (latestNowElapsedMillis == Long.MIN_VALUE) {
            nowElapsedMillis
        } else {
            max(latestNowElapsedMillis, nowElapsedMillis)
        }

        if (effectiveNow - observedAtElapsedMillis > SAMPLE_EXPIRY_MILLIS) {
            if (nowElapsedMillis > latestNowElapsedMillis) {
                latestNowElapsedMillis = nowElapsedMillis
            }
            pruneExpired(effectiveNow)
            return
        }

        val existingSlot = slotsById[config.id]
        if (existingSlot != null && observedAtElapsedMillis < existingSlot.lastSeenElapsedMillis) {
            if (nowElapsedMillis > latestNowElapsedMillis) {
                latestNowElapsedMillis = nowElapsedMillis
            }
            pruneExpired(effectiveNow)
            return
        }

        latestNowElapsedMillis = effectiveNow
        pruneExpired(effectiveNow)

        val slot = slotsById.getOrPut(config.id) { BeaconIdSlot(config.id) }
        slot.record(config, rssi, observedAtElapsedMillis)
        hadAnyObservation = true
    }

    fun snapshot(nowElapsedMillis: Long): BeaconEstimate {
        val effectiveNow = if (latestNowElapsedMillis == Long.MIN_VALUE) {
            nowElapsedMillis
        } else {
            max(latestNowElapsedMillis, nowElapsedMillis)
        }
        if (nowElapsedMillis >= 0L) {
            latestNowElapsedMillis = effectiveNow
        }

        pruneExpired(effectiveNow)

        if (slotsById.isEmpty()) {
            val reason = if (hadAnyObservation) {
                "유효한 비콘 신호 만료"
            } else {
                "유효한 비콘 신호 대기 중"
            }
            return BeaconEstimate(
                observations = emptyList(),
                position = BeaconPosition.Unavailable(reason)
            )
        }

        val observations = ArrayList<BeaconObservation>(slotsById.size)
        val ranges = ArrayList<BeaconRange>(slotsById.size)
        var hasConflict = false

        for (slot in slotsById.values) {
            val observation = slot.toObservation(medianScratch) ?: continue
            observations.add(observation)
            if (observation.conflicted) {
                hasConflict = true
            } else {
                val dist = observation.distanceMeters
                if (dist != null && dist.isFinite() && dist > 0.0) {
                    ranges.add(BeaconRange(observation.config, dist))
                }
            }
        }

        observations.sortBy { it.config.id }

        val position = when (ranges.size) {
            0 -> BeaconPosition.Unavailable(
                if (hasConflict) "동일 ID 비콘 충돌 감지" else "유효한 비콘 신호 대기 중"
            )
            1 -> BeaconPosition.Proximity(ranges[0])
            2 -> weightedPosition(ranges, null)
            else -> when (val strict = solvePosition(ranges)) {
                is BeaconPosition.Available -> strict
                is BeaconPosition.Unavailable -> weightedPosition(ranges, strict.reason)
                else -> strict
            }
        }

        return BeaconEstimate(
            observations = observations,
            position = position
        )
    }

    private fun weightedPosition(ranges: List<BeaconRange>, fallbackReason: String?): BeaconPosition {
        var minDistance = Double.POSITIVE_INFINITY
        for (i in ranges.indices) minDistance = minOf(minDistance, ranges[i].distanceMeters)
        var sumWeight = 0.0
        var x = 0.0
        var y = 0.0
        for (i in ranges.indices) {
            val range = ranges[i]
            val weight = minDistance / range.distanceMeters
            sumWeight += weight
            x += weight * (range.config.xCm / 100.0)
            y += weight * (range.config.yCm / 100.0)
        }
        x /= sumWeight
        y /= sumWeight
        return if (x.isFinite() && y.isFinite()) {
            BeaconPosition.Weighted(x, y, ranges.size, fallbackReason)
        } else {
            BeaconPosition.Unavailable(fallbackReason ?: "유효한 위치 좌표를 계산할 수 없음")
        }
    }

    fun clear() {
        slotsById.clear()
        latestNowElapsedMillis = Long.MIN_VALUE
        hadAnyObservation = false
    }

    internal fun solvePosition(ranges: List<BeaconRange>): BeaconPosition {
        return com.example.safetymonitor.detection.beacon.solvePosition(ranges)
    }

    private fun pruneExpired(nowElapsedMillis: Long) {
        val iterator = slotsById.entries.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next().value
            slot.pruneExpired(nowElapsedMillis)
            if (slot.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private class BeaconIdSlot(
        val id: Int
    ) {
        private val variants = ArrayList<ConfigVariant>(2)
        private val combinedWindow = SampleWindow()
        var lastSeenElapsedMillis: Long = Long.MIN_VALUE
            private set

        fun record(config: BeaconConfig, rssi: Int, observedAtElapsedMillis: Long) {
            var variant: ConfigVariant? = null
            for (i in 0 until variants.size) {
                val candidate = variants[i]
                if (candidate.config == config) {
                    variant = candidate
                    break
                }
            }
            if (variant == null) {
                variant = ConfigVariant(config)
                variants.add(variant)
            }
            variant.window.add(rssi, observedAtElapsedMillis)
            combinedWindow.add(rssi, observedAtElapsedMillis)
            if (observedAtElapsedMillis > lastSeenElapsedMillis) {
                lastSeenElapsedMillis = observedAtElapsedMillis
            }
        }

        fun pruneExpired(nowElapsedMillis: Long) {
            var maxSeen = Long.MIN_VALUE
            val iterator = variants.iterator()
            while (iterator.hasNext()) {
                val variant = iterator.next()
                variant.window.prune(nowElapsedMillis)
                if (variant.window.count == 0) {
                    iterator.remove()
                } else if (variant.window.lastSeenElapsedMillis > maxSeen) {
                    maxSeen = variant.window.lastSeenElapsedMillis
                }
            }
            combinedWindow.prune(nowElapsedMillis)
            lastSeenElapsedMillis = maxSeen
        }

        fun isEmpty(): Boolean = variants.isEmpty()

        fun toObservation(scratch: IntArray): BeaconObservation? {
            if (variants.isEmpty()) {
                return null
            }
            if (variants.size == 1) {
                val single = variants[0]
                val medianRssi = single.window.median(scratch)
                val distance = estimateDistanceMeters(single.config.referenceRssi, medianRssi)
                return BeaconObservation(
                    config = single.config,
                    rssi = medianRssi,
                    distanceMeters = distance,
                    lastSeenElapsedMillis = single.window.lastSeenElapsedMillis,
                    conflicted = false
                )
            }

            var representative = variants[0]
            for (i in 1 until variants.size) {
                val candidate = variants[i]
                if (candidate.window.lastSeenElapsedMillis >= representative.window.lastSeenElapsedMillis) {
                    representative = candidate
                }
            }
            val medianRssi = if (combinedWindow.count > 0) {
                combinedWindow.median(scratch)
            } else {
                representative.window.median(scratch)
            }
            return BeaconObservation(
                config = representative.config,
                rssi = medianRssi,
                distanceMeters = null,
                lastSeenElapsedMillis = lastSeenElapsedMillis,
                conflicted = true
            )
        }
    }

    private class ConfigVariant(
        val config: BeaconConfig
    ) {
        val window = SampleWindow()
    }

    private class SampleWindow {
        private val rssiValues = IntArray(MAX_SAMPLES)
        private val timestamps = LongArray(MAX_SAMPLES)
        var count: Int = 0
            private set
        var lastSeenElapsedMillis: Long = Long.MIN_VALUE
            private set

        fun add(rssi: Int, timestamp: Long) {
            if (count < MAX_SAMPLES) {
                rssiValues[count] = rssi
                timestamps[count] = timestamp
                count++
            } else {
                for (i in 1 until MAX_SAMPLES) {
                    rssiValues[i - 1] = rssiValues[i]
                    timestamps[i - 1] = timestamps[i]
                }
                rssiValues[MAX_SAMPLES - 1] = rssi
                timestamps[MAX_SAMPLES - 1] = timestamp
            }
            if (timestamp > lastSeenElapsedMillis) {
                lastSeenElapsedMillis = timestamp
            }
        }

        fun prune(nowElapsedMillis: Long) {
            var writeIdx = 0
            var latest = Long.MIN_VALUE
            for (readIdx in 0 until count) {
                val ts = timestamps[readIdx]
                if (nowElapsedMillis - ts <= SAMPLE_EXPIRY_MILLIS) {
                    rssiValues[writeIdx] = rssiValues[readIdx]
                    timestamps[writeIdx] = ts
                    if (ts > latest) {
                        latest = ts
                    }
                    writeIdx++
                }
            }
            count = writeIdx
            lastSeenElapsedMillis = latest
        }

        fun median(scratch: IntArray): Double {
            val n = count
            for (i in 0 until n) {
                val value = rssiValues[i]
                var j = i - 1
                while (j >= 0 && scratch[j] > value) {
                    scratch[j + 1] = scratch[j]
                    j--
                }
                scratch[j + 1] = value
            }
            return if (n % 2 == 1) {
                scratch[n / 2].toDouble()
            } else {
                (scratch[(n / 2) - 1] + scratch[n / 2]) / 2.0
            }
        }
    }

    companion object {
        const val SAMPLE_EXPIRY_MILLIS: Long = 5_000L
        const val MAX_SAMPLES: Int = 5
        const val DEFAULT_PATH_LOSS_EXPONENT: Double = 2.0
        private const val MIN_VALID_RSSI: Int = -127
        private const val MAX_VALID_RSSI: Int = -1

        internal fun estimateDistanceMeters(
            referenceRssi: Int,
            rssi: Double,
            pathLossExponent: Double = DEFAULT_PATH_LOSS_EXPONENT
        ): Double? {
            if (!rssi.isFinite() || pathLossExponent <= 0.0) {
                return null
            }
            val distance = 10.0.pow((referenceRssi.toDouble() - rssi) / (10.0 * pathLossExponent))
            return if (distance.isFinite() && distance > 0.0) distance else null
        }
    }
}
