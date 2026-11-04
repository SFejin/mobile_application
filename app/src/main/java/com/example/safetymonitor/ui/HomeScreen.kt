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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.fall.MotionReading
import java.util.Locale

@Composable
fun HomeScreen(
    profile: WorkerProfile,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
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
            WorkSafeCard {
                HomeInfoRow(
                    label = "서버 연결 상태",
                    value = "DEMO · 미연결",
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                HomeInfoRow("BLE 접근 감지", "미감지 · 예시", MaterialTheme.colorScheme.onSurface)
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
