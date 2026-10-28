package com.example.safetymonitor.detection

// 결과 데이터 (Java의 DTO/VO 클래스)
data class DetectionResult(
    val workerId: String = "WORKER_01",              // 작업자 ID (임의 설정)
    val type: DetectorType,                          // 위험 타입 (FALL, VITAL, BEACON)
    val timestamp: Long = System.currentTimeMillis(), // 발생 시간 (밀리초)
    val status: DetectionStatus,                     // 상태 (ALERT, NORMAL)
    val message: String = ""                         // 상세 메시지
)
