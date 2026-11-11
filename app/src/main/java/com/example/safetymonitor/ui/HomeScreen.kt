package com.example.safetymonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safetymonitor.detection.beacon.BeaconIssue
import com.example.safetymonitor.detection.beacon.BeaconObservation
import com.example.safetymonitor.detection.beacon.BeaconPosition
import com.example.safetymonitor.detection.beacon.BeaconProtocol
import com.example.safetymonitor.detection.beacon.BeaconRole
import com.example.safetymonitor.detection.beacon.BeaconSnapshot
import com.example.safetymonitor.detection.beacon.BeaconStatus
import com.example.safetymonitor.detection.fall.MotionReading
import java.util.Locale

@Composable
fun HomeScreen(
    profile: WorkerProfile,
    onLogout: () -> Unit,
    beacon: BeaconSnapshot,
    onStartReceiver: () -> Unit,
    onStopBeacon: () -> Unit,
    onResolveBeaconIssue: () -> Unit,
    modifier: Modifier = Modifier,
    sosContent: @Composable () -> Unit = {},
    motion: MotionReading = MotionReading(),
    fallMessage: String = "센서 측정 대기 중",
    fallAlert: Boolean = false
) {
    val detectionReady = motion.monitoring && motion.accelerometerActive &&
        motion.gyroscopeActive && motion.acceleration != null && motion.gyroscope != null
    val statusTitle = when {
        fallAlert -> "낙상 감지됨"
        !motion.monitoring -> "낙상 감지 중지"
        !motion.accelerometerActive || !motion.gyroscopeActive -> "낙상 감지 불가"
        !detectionReady -> "센서 수신 대기"
        else -> "정상"
    }
    val statusTextColor = if (fallAlert) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            sosContent()

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (fallAlert) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(5.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "실시간 감지 상태",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (fallAlert) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        statusTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = statusTextColor
                    )
                    Text(
                        fallMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusTextColor
                    )
                    Surface(
                        color = if (fallAlert) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            when {
                                fallAlert -> "HIGH"
                                detectionReady -> "NORMAL"
                                else -> "대기"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (fallAlert) MaterialTheme.colorScheme.onError
                                else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Real BLE positioning & monitoring card replacing former single BLE row
            WorkSafeCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (beacon.role == BeaconRole.TRANSMITTER) "BLE 비콘 송신 모드" else "BLE 실시간 측위 (수신기)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Surface(
                        color = when {
                            beacon.status == BeaconStatus.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
                            beacon.status == BeaconStatus.BLOCKED || beacon.status == BeaconStatus.ERROR ->
                                MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            when (beacon.status) {
                                BeaconStatus.IDLE -> "대기"
                                BeaconStatus.STARTING -> "시작 중"
                                BeaconStatus.ACTIVE -> if (beacon.role == BeaconRole.TRANSMITTER) "송신 중" else "수신 중"
                                BeaconStatus.STOPPED -> "중지됨"
                                BeaconStatus.BLOCKED -> "차단됨"
                                BeaconStatus.ERROR -> "오류"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                beacon.status == BeaconStatus.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
                                beacon.status == BeaconStatus.BLOCKED || beacon.status == BeaconStatus.ERROR ->
                                    MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Text(
                    beacon.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Transmitter state notice visible on HOME (no hidden menu exposed)
                if (beacon.role == BeaconRole.TRANSMITTER) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (beacon.status == BeaconStatus.ACTIVE) "⚠️ 현재 단말기가 고정 비콘(송신기)으로 동작 중입니다."
                                else "송신기 모드이지만 현재 송신하지 않습니다. (요청한 파라미터)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            beacon.transmitterConfig?.let { tx ->
                                Text(
                                    String.format(
                                        Locale.KOREA,
                                        "송신 파라미터: ID #%d, 좌표 (%.2fm, %.2fm), 1m RSSI %d dBm",
                                        tx.id,
                                        tx.xCm / 100.0,
                                        tx.yCm / 100.0,
                                        tx.referenceRssi
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Issue explanation & resolution button
                if (beacon.issue != BeaconIssue.NONE || beacon.status == BeaconStatus.BLOCKED) {
                    val issueText = when (beacon.issue) {
                        BeaconIssue.PERMISSIONS -> "블루투스 및 위치 권한이 필요합니다."
                        BeaconIssue.BLUETOOTH_OFF -> "블루투스가 꺼져 있습니다. 블루투스를 켜주세요."
                        BeaconIssue.LOCATION_OFF -> "위치 서비스(GPS)가 꺼져 있습니다. 위치 서비스를 켜주세요."
                        BeaconIssue.UNSUPPORTED -> "이 기기는 BLE 기능을 지원하지 않습니다."
                        BeaconIssue.SCAN_FAILED -> "BLE 비콘 스캔에 실패했습니다."
                        BeaconIssue.ADVERTISE_FAILED -> "BLE 비콘 광고(송신)에 실패했습니다."
                        BeaconIssue.START_FAILED -> "BLE 서비스 시작에 실패했습니다."
                        BeaconIssue.NONE -> "권한 또는 기기 설정 확인이 필요합니다."
                    }
                    Text(
                        issueText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    WorkSafeButton(
                        text = "권한 / 설정 문제 해결",
                        onClick = onResolveBeaconIssue,
                        modifier = Modifier.fillMaxWidth(),
                        danger = true
                    )
                }

                // Position information reflects the available evidence, including lower-anchor modes.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when (beacon.position) {
                                is BeaconPosition.Proximity -> "비콘 거리 참고"
                                is BeaconPosition.Weighted -> "가중 참고 위치"
                                is BeaconPosition.Available -> "작업자 추정 위치"
                                is BeaconPosition.Unavailable -> "위치 정보"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (val pos = beacon.position) {
                            is BeaconPosition.Proximity -> {
                                Text(
                                    String.format(Locale.KOREA, "비콘 #%d · 추정 거리 %.2f m", pos.anchor.config.id, pos.anchor.distanceMeters),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            is BeaconPosition.Weighted -> {
                                Text(
                                    String.format(Locale.KOREA, "X = %.2f m,  Y = %.2f m", pos.xMeters, pos.yMeters),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("거리 가중 참고 위치 · 비콘 ${pos.anchorCount}개", style = MaterialTheme.typography.bodySmall)
                                pos.fallbackReason?.let {
                                    Text("2D 추정 불가: $it · 가중 참고 위치로 표시합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            is BeaconPosition.Available -> {
                                Text(
                                    String.format(
                                        Locale.KOREA,
                                        "X = %.2f m,  Y = %.2f m",
                                        pos.xMeters,
                                        pos.yMeters
                                    ),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "참여 비콘 수: ${pos.anchorCount}개 · 2D 근사 추정",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            is BeaconPosition.Unavailable -> {
                                Text(
                                    "위치 측정 불가",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "사유: ${pos.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            "장애물과 환경에 따라 오차가 발생할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Reference map for current eligible anchors and position mode
                BeaconPositionMap(
                    observations = beacon.observations,
                    position = beacon.position,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )

                // Beacon observation list
                if (beacon.observations.isNotEmpty()) {
                    Text(
                        "수신된 비콘 (${beacon.observations.size}개)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        beacon.observations.forEach { obs ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (obs.conflicted) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "비콘 #${obs.config.id}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (obs.conflicted) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.error,
                                                shape = RoundedCornerShape(3.dp)
                                            ) {
                                                Text(
                                                    "좌표 충돌",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onError,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(3.dp)
                                            ) {
                                                Text(
                                                    "정상 수신",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }

                                    if (obs.conflicted) {
                                        Text(
                                            "⚠️ 동일 ID에 상이한 좌표가 감지되었습니다. 비인가 신호로 간주하여 측위 계산에서 제외됩니다.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        Text(
                                            String.format(
                                                Locale.KOREA,
                                                "고정 좌표: X = %.2fm, Y = %.2fm (1m RSSI: %d dBm)",
                                                obs.config.xCm / 100.0,
                                                obs.config.yCm / 100.0,
                                                obs.config.referenceRssi
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            String.format(Locale.KOREA, "신호(중앙값): %.1f dBm", obs.rssi),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            obs.distanceMeters?.let {
                                                String.format(Locale.KOREA, "추정 거리: %.2f m", it)
                                            } ?: if (obs.conflicted) "거리 계산 불가" else "거리 계산 대기",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = if (obs.distanceMeters != null) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (beacon.role == BeaconRole.RECEIVER) {
                    Text(
                        "감지된 비콘 신호가 없습니다. (주변에 전용 비콘 송신기 동작 필요)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Control action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        beacon.status == BeaconStatus.ACTIVE || beacon.status == BeaconStatus.STARTING -> {
                            WorkSafeButton(
                                text = "비콘 중지",
                                onClick = onStopBeacon,
                                modifier = Modifier.fillMaxWidth(),
                                danger = true
                            )
                        }
                        beacon.role == BeaconRole.TRANSMITTER -> {
                            WorkSafeButton(
                                text = "수신 모드로 전환",
                                onClick = onStartReceiver,
                                modifier = Modifier.fillMaxWidth(),
                                secondary = true
                            )
                        }
                        else -> {
                            WorkSafeButton(
                                text = "비콘 수신 시작",
                                onClick = onStartReceiver,
                                modifier = Modifier.fillMaxWidth(),
                                emphasized = true
                            )
                        }
                    }
                }
            }

            // Preserved sensor info card (real motion rows + other examples)
            WorkSafeCard {
                Text("센서 정보", style = MaterialTheme.typography.titleMedium)
                HomeInfoRow(
                    "가속도 센서",
                    motion.acceleration?.let {
                        String.format(Locale.KOREA, "%.2f m/s²", it.magnitude)
                    } ?: when {
                        !motion.monitoring -> "측정 중지"
                        !motion.accelerometerActive -> "사용 불가"
                        else -> "수신 대기"
                    },
                    MaterialTheme.colorScheme.error
                )
                HomeInfoRow("Galaxy Watch", "연결됨 · 예시", MaterialTheme.colorScheme.tertiary)
                HomeInfoRow("심박수", "78 bpm · 예시", MaterialTheme.colorScheme.tertiary)
                HomeInfoRow("SpO₂", "98% · 예시", MaterialTheme.colorScheme.primary)
            }

            WorkSafeCard(muted = true) {
                Text("개인 / 작업자 정보", style = MaterialTheme.typography.titleMedium)
                Text(
                    "이름  ${profile.name}   성별  ${profile.gender}   나이  ${profile.age}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "직급  ${profile.jobTitle}   소속  ${profile.department}   ID  ${profile.workerId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        WorkSafeButton(
            text = "로그아웃",
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            secondary = true
        )
    }
}

@Composable
private fun BeaconPositionMap(
    observations: List<BeaconObservation>,
    position: BeaconPosition,
    modifier: Modifier = Modifier
) {
    val available = position as? BeaconPosition.Available
    val weighted = position as? BeaconPosition.Weighted
    val proximity = position as? BeaconPosition.Proximity
    val textMeasurer = rememberTextMeasurer()
    val anchorColor = MaterialTheme.colorScheme.primary
    val pointColor = MaterialTheme.colorScheme.error
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(4.dp)
    val weightedMarkerPath = remember { Path() }
    val density = LocalDensity.current
    val weightedMarkerStroke = remember(density) {
        Stroke(width = with(density) { 2.dp.toPx() })
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape)
            .border(1.dp, outlineColor.copy(alpha = 0.35f), shape)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            if (available != null) {
                minX = available.xMeters
                maxX = available.xMeters
                minY = available.yMeters
                maxY = available.yMeters
            } else if (weighted != null) {
                minX = weighted.xMeters
                maxX = weighted.xMeters
                minY = weighted.yMeters
                maxY = weighted.yMeters
            } else if (proximity != null) {
                val anchor = proximity.anchor
                val x = anchor.config.xCm / 100.0
                val y = anchor.config.yCm / 100.0
                minX = x - anchor.distanceMeters
                maxX = x + anchor.distanceMeters
                minY = y - anchor.distanceMeters
                maxY = y + anchor.distanceMeters
            }
            fun eligible(obs: BeaconObservation): Boolean {
                val distance = obs.distanceMeters
                return !obs.conflicted && BeaconProtocol.isValid(obs.config) &&
                    distance != null && distance.isFinite() && distance > 0.0
            }
            for (i in observations.indices) {
                val obs = observations[i]
                if (eligible(obs)) {
                    val x = obs.config.xCm / 100.0
                    val y = obs.config.yCm / 100.0
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
            if (!minX.isFinite()) {
                val emptyMsg = textMeasurer.measure(
                    text = "수신된 유효 비콘 없음",
                    style = TextStyle(fontSize = 12.sp, color = onSurfaceColor.copy(alpha = 0.6f))
                )
                drawText(emptyMsg, topLeft = Offset(
                    (size.width - emptyMsg.size.width) / 2f,
                    (size.height - emptyMsg.size.height) / 2f
                ))
                return@Canvas
            }

            val centerX = (minX + maxX) / 2.0
            val centerY = (minY + maxY) / 2.0
            val spanX = maxOf(maxX - minX, 4.0) + 2.4
            val spanY = maxOf(maxY - minY, 4.0) + 2.4
            // Leave space around markers and below the map for the legend.
            val top = 20.dp.toPx()
            val bottom = size.height - 38.dp.toPx()
            val centerPxY = (top + bottom) / 2f
            val scale = minOf(
                (size.width - 48.dp.toPx()).coerceAtLeast(1f) / spanX.toFloat(),
                (bottom - top).coerceAtLeast(1f) / spanY.toFloat()
            )
            fun toCanvasOffset(x: Double, y: Double) = Offset(
                size.width / 2f + ((x - centerX) * scale).toFloat(),
                centerPxY - ((y - centerY) * scale).toFloat()
            )

            val origin = toCanvasOffset(0.0, 0.0)
            if (origin.x in 0f..size.width) {
                drawLine(outlineColor.copy(alpha = 0.25f), Offset(origin.x, 0f), Offset(origin.x, size.height),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f))
            }
            if (origin.y in 0f..size.height) {
                drawLine(outlineColor.copy(alpha = 0.25f), Offset(0f, origin.y), Offset(size.width, origin.y),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f))
            }
            for (i in observations.indices) {
                val obs = observations[i]
                if (eligible(obs)) {
                    val anchorPos = toCanvasOffset(obs.config.xCm / 100.0, obs.config.yCm / 100.0)
                    val radius = (obs.distanceMeters!! * scale).toFloat()
                    if (radius.isFinite() && radius > 0f && radius < size.width * 2.5f) {
                        drawCircle(anchorColor.copy(alpha = 0.2f), radius, anchorPos,
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)))
                    }
                    drawCircle(anchorColor, 6.dp.toPx(), anchorPos)
                    drawCircle(surfaceColor, 2.5.dp.toPx(), anchorPos)
                }
            }
            if (available != null) {
                val point = toCanvasOffset(available.xMeters, available.yMeters)
                drawCircle(pointColor.copy(alpha = 0.25f), 14.dp.toPx(), point)
                drawCircle(pointColor, 7.dp.toPx(), point)
                drawCircle(surfaceColor, 2.5.dp.toPx(), point)
            }
            if (weighted != null) {
                val point = toCanvasOffset(weighted.xMeters, weighted.yMeters)
                val r = 8.dp.toPx()
                val diamond = weightedMarkerPath.apply {
                    reset()
                    moveTo(point.x, point.y - r)
                    lineTo(point.x + r, point.y)
                    lineTo(point.x, point.y + r)
                    lineTo(point.x - r, point.y)
                    close()
                }
                drawPath(diamond, pointColor, style = weightedMarkerStroke)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(surfaceColor.copy(alpha = 0.9f), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("● 비콘 · ID/좌표는 목록 참조", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = anchorColor)
            when (position) {
                is BeaconPosition.Proximity -> Text("점선: 추정 거리", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                is BeaconPosition.Weighted -> Text("◇ 가중 참고 위치", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = pointColor)
                is BeaconPosition.Available -> Text("● 추정 위치", style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = pointColor)
                is BeaconPosition.Unavailable -> Unit
            }
        }
    }
}

@Composable
private fun HomeInfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}
