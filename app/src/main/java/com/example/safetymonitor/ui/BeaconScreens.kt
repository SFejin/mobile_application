package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.beacon.BeaconConfig
import com.example.safetymonitor.detection.beacon.BeaconIssue
import com.example.safetymonitor.detection.beacon.BeaconRole
import com.example.safetymonitor.detection.beacon.BeaconSnapshot
import com.example.safetymonitor.detection.beacon.BeaconStatus
import java.util.Locale

@Composable
fun BeaconTransmitterScreen(
    savedConfig: BeaconConfig,
    snapshot: BeaconSnapshot,
    onSaveConfig: (BeaconConfig) -> Unit,
    onStartTransmitter: (BeaconConfig) -> Unit,
    onStopBeacon: () -> Unit,
    onReturnToReceiver: () -> Unit,
    onResolveIssue: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The shared snapshot describes whichever role is running. Only a TRANSMITTER snapshot may be shown
    // as transmitter status; a receiver snapshot (even blocked) is not a transmitter problem.
    val isTxSnapshot = snapshot.role == BeaconRole.TRANSMITTER
    val isTxActive = snapshot.role == BeaconRole.TRANSMITTER &&
        (snapshot.status == BeaconStatus.STARTING || snapshot.status == BeaconStatus.ACTIVE)

    var idText by remember(savedConfig.id) { mutableStateOf(savedConfig.id.toString()) }
    var xText by remember(savedConfig.xCm) {
        mutableStateOf(String.format(Locale.US, "%.2f", savedConfig.xCm / 100.0))
    }
    var yText by remember(savedConfig.yCm) {
        mutableStateOf(String.format(Locale.US, "%.2f", savedConfig.yCm / 100.0))
    }
    var rssiText by remember(savedConfig.referenceRssi) {
        mutableStateOf(savedConfig.referenceRssi.toString())
    }
    var saveFeedback by remember { mutableStateOf<String?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // When active transmitter is broadcasting, show actual transmitterConfig so config cannot lie.
    val activeConfig = snapshot.transmitterConfig

    // Enforce ID: 1..65535 (uint16 LE)
    val parsedId = idText.trim().toIntOrNull()
    val idError = when {
        idText.isBlank() -> "비콘 ID를 입력해주세요."
        parsedId == null || parsedId !in 1..65535 -> "비콘 ID는 1 ~ 65535 범위의 정수여야 합니다."
        else -> null
    }

    // Enforce X: -327.68..327.67 m; the wire format stores whole centimeters, so 1.234 m is rounded to 1.23 m (-32768..32767 cm)
    val parsedX = xText.trim().toDoubleOrNull()
    val xError = when {
        xText.isBlank() -> "X 좌표(m)를 입력해주세요."
        parsedX == null || !parsedX.isFinite() -> "올바른 숫자를 입력해주세요."
        parsedX < -327.68 || parsedX > 327.67 -> "X 좌표는 -327.68m ~ +327.67m 범위여야 합니다."
        else -> null
    }

    // Enforce Y: -327.68..327.67 m then round to cm (-32768..32767 cm)
    val parsedY = yText.trim().toDoubleOrNull()
    val yError = when {
        yText.isBlank() -> "Y 좌표(m)를 입력해주세요."
        parsedY == null || !parsedY.isFinite() -> "올바른 숫자를 입력해주세요."
        parsedY < -327.68 || parsedY > 327.67 -> "Y 좌표는 -327.68m ~ +327.67m 범위여야 합니다."
        else -> null
    }

    // Enforce Reference RSSI: -127..-1 signed8 (default -59)
    val parsedRssi = rssiText.trim().toIntOrNull()
    val rssiError = when {
        rssiText.isBlank() -> "1m 기준 RSSI를 입력해주세요."
        parsedRssi == null -> "올바른 숫자를 입력해주세요."
        parsedRssi !in -127..-1 -> "1m 기준 RSSI는 -127 ~ -1 dBm 사이의 음수 정수여야 합니다. (기본: -59)"
        else -> null
    }

    val isInputValid = idError == null && xError == null && yError == null && rssiError == null
    val showAdvanced = advancedExpanded || xError != null || yError != null

    fun buildConfig(): BeaconConfig? {
        if (!isInputValid || parsedId == null || parsedX == null || parsedY == null || parsedRssi == null) {
            return null
        }
        val xCm = Math.round(parsedX * 100.0).toInt().coerceIn(-32768, 32767)
        val yCm = Math.round(parsedY * 100.0).toInt().coerceIn(-32768, 32767)
        return BeaconConfig(
            id = parsedId,
            xCm = xCm,
            yCm = yCm,
            referenceRssi = parsedRssi
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Current status & issue banner
        WorkSafeCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("송신기 동작 상태", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = when {
                        isTxActive -> MaterialTheme.colorScheme.secondaryContainer
                        isTxSnapshot && (snapshot.status == BeaconStatus.BLOCKED || snapshot.status == BeaconStatus.ERROR) ->
                            MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Text(
                        when (if (isTxSnapshot) snapshot.status else BeaconStatus.IDLE) {
                            BeaconStatus.IDLE -> "대기"
                            BeaconStatus.STARTING -> "시작 중"
                            BeaconStatus.ACTIVE -> "송신 중"
                            BeaconStatus.STOPPED -> "중지됨"
                            BeaconStatus.BLOCKED -> "차단됨"
                            BeaconStatus.ERROR -> "오류"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isTxActive -> MaterialTheme.colorScheme.onSecondaryContainer
                            isTxSnapshot && (snapshot.status == BeaconStatus.BLOCKED || snapshot.status == BeaconStatus.ERROR) ->
                                MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Text(
                if (isTxSnapshot) snapshot.message
                else "송신하지 않는 상태입니다. '비콘 송신 시작'을 눌러야만 송신 모드로 전환됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (isTxSnapshot && (snapshot.issue != BeaconIssue.NONE || snapshot.status == BeaconStatus.BLOCKED)) {
                val issueDescription = when (snapshot.issue) {
                    BeaconIssue.PERMISSIONS -> "블루투스 광고 및 주변 기기 검색 권한이 필요합니다."
                    BeaconIssue.BLUETOOTH_OFF -> "블루투스가 꺼져 있습니다. 블루투스를 켜주세요."
                    BeaconIssue.LOCATION_OFF -> "위치 서비스가 꺼져 있습니다."
                    BeaconIssue.UNSUPPORTED -> "이 기기는 BLE 비콘 광고를 지원하지 않습니다."
                    BeaconIssue.SCAN_FAILED -> "BLE 스캔에 실패했습니다."
                    BeaconIssue.ADVERTISE_FAILED -> "BLE 비콘 광고(송신)에 실패했습니다."
                    BeaconIssue.START_FAILED -> "BLE 서비스 시작에 실패했습니다."
                    BeaconIssue.NONE -> "권한 또는 기기 설정 확인이 필요합니다."
                }
                Text(
                    issueDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                WorkSafeButton(
                    text = "권한 / 설정 문제 해결",
                    onClick = onResolveIssue,
                    modifier = Modifier.fillMaxWidth(),
                    danger = true
                )
            }
        }

        // Show actual active config while transmitting so configuration cannot lie
        if (isTxActive && activeConfig != null) {
            WorkSafeCard(muted = true) {
                Text(
                    "현재 실제 송신 중인 파라미터 (광고 패킷)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "비콘 ID: ${activeConfig.id}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (showAdvanced) {
                    Text(
                        String.format(
                            Locale.KOREA,
                            "좌표: X = %.2fm (%dcm), Y = %.2fm (%dcm)",
                            activeConfig.xCm / 100.0,
                            activeConfig.xCm,
                            activeConfig.yCm / 100.0,
                            activeConfig.yCm
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "1m 기준 RSSI: ${activeConfig.referenceRssi} dBm",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Form settings card (disabled while transmitting)
        WorkSafeCard {
            Text("비콘 송신 파라미터 설정", style = MaterialTheme.typography.titleMedium)
            WorkSafeField(
                label = "비콘 ID (1 ~ 65535)",
                value = idText,
                onValueChange = {
                    idText = it
                    saveFeedback = null
                },
                placeholder = "예: 1",
                keyboardType = KeyboardType.Number,
                isError = idError != null,
                supportingText = idError ?: "고유 비콘 식별자 (uint16)",
                enabled = !isTxActive
            )

            WorkSafeField(
                label = "1m 기준 RSSI (dBm, -127 ~ -1)",
                value = rssiText,
                onValueChange = {
                    rssiText = it
                    saveFeedback = null
                },
                placeholder = "예: -59",
                keyboardType = KeyboardType.Text,
                isError = rssiError != null,
                supportingText = rssiError ?: "1m 거리의 보정 수신 감도 (기본값: -59)",
                enabled = !isTxActive
            )

            WorkSafeButton(
                text = if (showAdvanced) "고급 설정 닫기" else "고급 설정",
                onClick = {
                    if (!showAdvanced || (xError == null && yError == null)) {
                        advancedExpanded = !showAdvanced
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                secondary = true,
                enabled = !showAdvanced || (xError == null && yError == null)
            )

            if (showAdvanced) {
                // Plain text keyboard chosen so negative sign '-' is enterable on real Android keyboards
                WorkSafeField(
                    label = "설치 X 좌표 (m, -327.68 ~ +327.67)",
                    value = xText,
                    onValueChange = {
                        xText = it
                        advancedExpanded = true
                        saveFeedback = null
                    },
                    placeholder = "예: -1.00",
                    keyboardType = KeyboardType.Text,
                    isError = xError != null,
                    supportingText = xError ?: "미터(m) 단위 입력. cm 단위로 반올림해 저장 (예: 1.234 → 1.23)",
                    enabled = !isTxActive
                )

                WorkSafeField(
                    label = "설치 Y 좌표 (m, -327.68 ~ +327.67)",
                    value = yText,
                    onValueChange = {
                        yText = it
                        advancedExpanded = true
                        saveFeedback = null
                    },
                    placeholder = "예: 2.50",
                    keyboardType = KeyboardType.Text,
                    isError = yError != null,
                    supportingText = yError ?: "미터(m) 단위 입력. cm 단위로 반올림해 저장 (예: 1.234 → 1.23)",
                    enabled = !isTxActive
                )
            }

            if (saveFeedback != null) {
                Text(
                    saveFeedback!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WorkSafeButton(
                    text = "설정 저장",
                    onClick = {
                        buildConfig()?.let { config ->
                            onSaveConfig(config)
                            saveFeedback = "설정이 저장되었습니다. (송신에는 영향 없음)"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    secondary = true,
                    enabled = !isTxActive && isInputValid
                )
                WorkSafeButton(
                    text = "비콘 송신 시작",
                    onClick = {
                        buildConfig()?.let { config ->
                            onStartTransmitter(config)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    emphasized = true,
                    enabled = !isTxActive && isInputValid
                )
            }
        }

        // Actions: Stop beacon & Return to receiver
        if (isTxActive || snapshot.role == BeaconRole.TRANSMITTER) {
            WorkSafeButton(
                text = "비콘 송신 중지",
                onClick = onStopBeacon,
                modifier = Modifier.fillMaxWidth(),
                danger = true
            )
        }

        WorkSafeButton(
            text = "수신 모드로 복귀",
            onClick = onReturnToReceiver,
            modifier = Modifier.fillMaxWidth(),
            secondary = true
        )

        Spacer(Modifier.height(16.dp))
    }
}
