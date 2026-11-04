package com.example.safetymonitor.detection.fall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.example.safetymonitor.detection.DetectionStatus
import com.example.safetymonitor.detection.DetectorType
import com.example.safetymonitor.detection.SafetyDetector
import com.example.safetymonitor.detection.SafetyEvent
import kotlin.math.abs
import kotlin.math.sqrt

// 스마트폰의 가속도계와 자이로스코프를 이용해 낙상을 감지하는 클래스다.
// 자유낙하 -> 충격 -> 큰 회전 -> 일정 시간 정지 순서로 상태를 판정한다.
// 최종 낙상 판단 결과는 SafetyEvent 콜백으로 전달한다.
class FallDetector(
    context: Context,
    private val thresholds: Thresholds = Thresholds()
) : SafetyDetector, SensorEventListener {

    // 낙상 판단에 사용하는 임계값과 각 단계의 제한 시간을 저장한다.
    // Thresholds 객체를 반환하는 함수는 없으며 생성 시 FallDetector에 전달한다.
    data class Thresholds(
        val freeFallMax: Float = 3.0f,
        val impactMin: Float = 18.0f,
        val rotationMin: Float = 2.5f,
        val stillAccelerationTolerance: Float = 1.2f,
        val stillGyroscopeMax: Float = 0.35f,
        val freeFallToImpactTimeoutMillis: Long = 1_500L,
        val impactToRotationTimeoutMillis: Long = 1_500L,
        val stillnessRequiredMillis: Long = 3_000L,
        val rotationToStillnessTimeoutMillis: Long = 6_000L,
        val alertCooldownMillis: Long = 10_000L
    )

    // 현재 낙상 감지가 어느 단계까지 진행되었는지 나타낸다.
    enum class State {
        NORMAL,
        FREE_FALL,
        IMPACT,
        ROTATION,
        FALL_DETECTED
    }

    // 이 감지기가 담당하는 위험 종류인 FALL을 반환한다.
    override val detectorType = DetectorType.FALL

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var listener: ((SafetyEvent) -> Unit)? = null
    private var running = false
    private var motionListener: ((MotionReading) -> Unit)? = null
    private var reading = MotionReading()
    private var lastUiUpdateMillis = 0L
    private var accelerationAtMillis: Long? = null
    private var gyroscopeAtMillis: Long? = null
    private var samplesReady = false
    private var stateEnteredAtMillis = 0L
    private var stillnessStartedAtMillis: Long? = null

    // 현재 상태 머신의 단계를 반환한다. 외부에서는 읽기만 가능하다.
    var currentState: State = State.NORMAL
        private set

    // 가장 최근 가속도계 샘플의 3축 합성 크기(m/s²)를 반환한다.
    var accelerationMagnitude: Float = SensorManager.GRAVITY_EARTH
        private set

    // 가장 최근 자이로스코프 샘플의 3축 합성 크기(rad/s)를 반환한다.
    var gyroscopeMagnitude: Float = 0f
        private set

    // 가속도계와 자이로스코프가 모두 있으면 true를 반환한다.
    val sensorsAvailable: Boolean
        get() = accelerometer != null && gyroscope != null

    // 감지 상태와 최종 경고를 전달할 콜백을 저장한다. 이후 상태가 변할 때 listener로 SafetyEvent를 전달한다.
    override fun setEventListener(listener: (SafetyEvent) -> Unit) {
        this.listener = listener
    }

    fun setMotionListener(listener: (MotionReading) -> Unit) {
        motionListener = listener
        listener(reading)
    }

    // 사용 가능한 센서는 표시용으로 수신한다. 낙상 판정에는 두 센서 모두 필요하다.
    override fun start() {
        if (running) return

        running = true
        accelerationAtMillis = null
        gyroscopeAtMillis = null
        samplesReady = false
        accelerationMagnitude = SensorManager.GRAVITY_EARTH
        gyroscopeMagnitude = 0f
        reset(notify = false)
        val accelerationActive = accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        val rotationActive = gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        reading = MotionReading(
            accelerometerActive = accelerationActive,
            gyroscopeActive = rotationActive,
            monitoring = true
        )
        lastUiUpdateMillis = 0L
        motionListener?.invoke(reading)
        publishNormal(if (accelerationActive && rotationActive) {
            "가속도·자이로 센서 수신 대기 중"
        } else {
            "낙상 감지 불가: 가속도계 또는 자이로스코프가 없거나 시작할 수 없습니다."
        })
    }

    // 센서 리스너를 해제하고 상태를 초기화한다.
    override fun stop() {
        if (!running) return

        sensorManager.unregisterListener(this)
        running = false
        reset(notify = false)
        accelerationAtMillis = null
        gyroscopeAtMillis = null
        reading = MotionReading()
        motionListener?.invoke(reading)
        publishNormal("센서 측정 중지")
    }

    // 상태 머신을 NORMAL 상태로 되돌린다.
    fun reset() {
        reset(notify = true)
    }

    // 새로운 센서값을 받으면 3축 합성 크기를 갱신하고 상태 머신을 평가한다.
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val magnitude = calculateMagnitude(event.values)
        val vector = MotionVector(event.values[0], event.values[1], event.values[2], magnitude)

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerationMagnitude = magnitude
                accelerationAtMillis = now
                reading = reading.copy(acceleration = vector)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroscopeMagnitude = magnitude
                gyroscopeAtMillis = now
                reading = reading.copy(gyroscope = vector)
            }
            else -> return
        }

        // Detect with every sample; limit only UI updates to 10 Hz.
        if (now - lastUiUpdateMillis >= 100L) {
            lastUiUpdateMillis = now
            motionListener?.invoke(reading)
        }
        val accelerationAt = accelerationAtMillis
        val rotationAt = gyroscopeAtMillis
        if (reading.accelerometerActive && reading.gyroscopeActive &&
            accelerationAt != null && rotationAt != null &&
            now - accelerationAt <= 500L && now - rotationAt <= 500L
        ) {
            if (!samplesReady) {
                samplesReady = true
                publishNormal("낙상 감지 정상 작동 중")
            }
            evaluate(now)
        } else {
            // Never confirm stillness using a stalled sensor's previous value.
            reset(notify = false)
            if (samplesReady) publishNormal("센서 수신 지연: 두 센서의 새 측정값 대기 중")
            samplesReady = false
        }
    }

    // 센서 정확도 변경은 현재 낙상 판정에 사용하지 않는다.
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // 기존 팀 테스트 버튼에서 사용하는 가짜 낙상 이벤트를 전달한다. listener가 등록되지 않았다면 아무 동작도 하지 않는다.
    fun testTriggerAlert() {
        listener?.invoke(
            SafetyEvent(
                type = DetectorType.FALL,
                status = DetectionStatus.ALERT,
                message = "낙상 감지됨 (테스트 이벤트)"
            )
        )
    }

    // 최신 센서값과 현재 단계를 비교해 다음 상태로 전환한다. 내부 상태와 SafetyEvent를 갱신한다.
    private fun evaluate(nowMillis: Long) {
        when (currentState) {
            State.NORMAL -> {
                if (accelerationMagnitude < thresholds.freeFallMax) {
                    transitionTo(State.FREE_FALL, nowMillis)
                }
            }

            State.FREE_FALL -> {
                when {
                    accelerationMagnitude >= thresholds.impactMin -> {
                        transitionTo(State.IMPACT, nowMillis)
                    }

                    nowMillis - stateEnteredAtMillis >
                        thresholds.freeFallToImpactTimeoutMillis -> {
                        transitionTo(State.NORMAL, nowMillis)
                    }
                }
            }

            State.IMPACT -> {
                when {
                    gyroscopeMagnitude >= thresholds.rotationMin -> {
                        transitionTo(State.ROTATION, nowMillis)
                    }

                    nowMillis - stateEnteredAtMillis >
                        thresholds.impactToRotationTimeoutMillis -> {
                        transitionTo(State.NORMAL, nowMillis)
                    }
                }
            }

            State.ROTATION -> evaluateStillness(nowMillis)

            State.FALL_DETECTED -> {
                // 같은 낙상으로 경고가 반복되지 않도록 잠시 기다린 뒤 재감지한다.
                if (nowMillis - stateEnteredAtMillis >= thresholds.alertCooldownMillis) {
                    transitionTo(State.NORMAL, nowMillis)
                }
            }
        }
    }

    // 큰 회전 이후 가속도와 각속도가 연속해서 안정적인지 검사한다. 조건을 만족하면 FALL_DETECTED 상태로 전환한다.
    private fun evaluateStillness(nowMillis: Long) {
        if (nowMillis - stateEnteredAtMillis > thresholds.rotationToStillnessTimeoutMillis) {
            transitionTo(State.NORMAL, nowMillis)
            return
        }

        val accelerationIsStill =
            abs(accelerationMagnitude - SensorManager.GRAVITY_EARTH) <=
                thresholds.stillAccelerationTolerance
        val rotationIsStill = gyroscopeMagnitude <= thresholds.stillGyroscopeMax

        if (accelerationIsStill && rotationIsStill) {
            val startedAt = stillnessStartedAtMillis
                ?: nowMillis.also { stillnessStartedAtMillis = it }

            if (nowMillis - startedAt >= thresholds.stillnessRequiredMillis) {
                transitionTo(State.FALL_DETECTED, nowMillis)
            }
        } else {
            // 정지 조건이 깨지면 정지 시간을 처음부터 다시 측정한다.
            stillnessStartedAtMillis = null
        }
    }

    // 상태를 변경하고 각 상태에 맞는 SafetyEvent를 전달한다.
    private fun transitionTo(newState: State, nowMillis: Long) {
        if (currentState == newState) return

        currentState = newState
        stateEnteredAtMillis = nowMillis
        if (newState != State.ROTATION) stillnessStartedAtMillis = null

        when (newState) {
            State.NORMAL -> publishNormal("낙상 감지 정상 작동 중")
            State.FREE_FALL -> publishNormal("자유낙하 후보 감지")
            State.IMPACT -> publishNormal("충격 감지, 회전 확인 중")
            State.ROTATION -> publishNormal("큰 회전 감지, 정지 상태 확인 중")
            State.FALL_DETECTED -> publishAlert()
        }
    }

    // 내부 시간값과 상태를 초기화한다.
    private fun reset(notify: Boolean) {
        currentState = State.NORMAL
        stateEnteredAtMillis = SystemClock.elapsedRealtime()
        stillnessStartedAtMillis = null

        if (notify) {
            publishNormal("낙상 감지 정상 작동 중")
        }
    }

    // NORMAL 상태 이벤트를 listener로 전달한다.
    private fun publishNormal(message: String) {
        listener?.invoke(
            SafetyEvent(
                type = DetectorType.FALL,
                status = DetectionStatus.NORMAL,
                message = message
            )
        )
    }

    // 최종 낙상 ALERT 이벤트를 listener로 전달한다.
    private fun publishAlert() {
        listener?.invoke(
            SafetyEvent(
                type = DetectorType.FALL,
                status = DetectionStatus.ALERT,
                message = "낙상 감지: 자유낙하, 충격, 큰 회전 및 정지 상태 확인"
            )
        )
    }

    // x, y, z 값을 이용해 sqrt(x² + y² + z²)를 계산해 반환한다.
    private fun calculateMagnitude(values: FloatArray): Float =
        sqrt(
            values[0] * values[0] +
                values[1] * values[1] +
                values[2] * values[2]
        )
}
