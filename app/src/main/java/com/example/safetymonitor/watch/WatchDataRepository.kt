package com.example.safetymonitor.watch

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.safetymonitor.sos.SosSession
import com.example.workersafety.link.WatchLinkProtocol
import com.example.workersafety.link.WatchLinkProtocol.Result
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class WatchSnapshot(
    val connection: String = "로그인 후 연결 확인",
    val summary: WatchSummary? = null,
    val receivedAt: Long? = null,
    val notice: String = "워치에서 측정을 시작해주세요."
)

internal interface WatchTransport {
    fun reachableNodes(): Set<Node>
    fun readSummaries(node: String, consume: (Map<String, Any?>) -> Unit): Boolean

    // Enqueue only: callers order this with bind(), never wait for delivery here.
    fun enqueueReceipt(node: String, path: String, payload: ByteArray): Task<Int>
}

internal class WearWatchTransport(private val context: Context) : WatchTransport {
    private val messages = Wearable.getMessageClient(context)

    override fun reachableNodes(): Set<Node> = Tasks.await(
        Wearable.getCapabilityClient(context)
            .getCapability(WatchSummary.CAPABILITY, CapabilityClient.FILTER_REACHABLE),
        5, TimeUnit.SECONDS
    ).nodes

    override fun readSummaries(node: String, consume: (Map<String, Any?>) -> Unit): Boolean {
        val uri = Uri.Builder().scheme("wear").authority(node).path(WatchSummary.PATH).build()
        val items = Tasks.await(Wearable.getDataClient(context).getDataItems(uri), 5, TimeUnit.SECONDS)
        try {
            for (item in items) {
                if (item.uri.watchOrigin() != node) continue
                item.readSummaryValues()?.let(consume)
            }
            return items.count != 0
        } finally {
            items.release()
        }
    }

    override fun enqueueReceipt(node: String, path: String, payload: ByteArray): Task<Int> =
        messages.sendMessage(node, path, payload)
}

internal fun Uri.watchOrigin(): String? {
    val node = host?.takeIf { it.isNotBlank() } ?: return null
    return node.takeIf {
        scheme == "wear" && encodedAuthority == node && encodedPath == WatchSummary.PATH &&
            encodedQuery == null && encodedFragment == null
    }
}

internal fun DataItem.readSummaryValues(): Map<String, Any?>? = try {
    val map = DataMapItem.fromDataItem(this).dataMap
    map.keySet().associateWith { map.get<Any>(it) }
} catch (_: RuntimeException) {
    null
}

/** Latest reading only, in memory, with exactly the same lifetime as the server session. */
object WatchDataRepository {
    internal class Capture

    private const val TAG = "WatchData"
    private val mutable = MutableStateFlow(WatchSnapshot())
    val state = mutable.asStateFlow()
    private var session: SosSession? = null
    private var binding = Capture()
    private var readings = WatchReadingWindow(Long.MAX_VALUE)
    // Serialize callbacks and refreshes; never drop a data event during an in-flight refresh.
    private val ioLock = ReentrantLock()

    @Synchronized fun bind(value: SosSession?) {
        if (session === value) return
        session = value
        binding = Capture()
        readings = WatchReadingWindow(System.currentTimeMillis())
        mutable.value = WatchSnapshot(connection = if (value == null) "로그인 후 연결 확인" else "워치 연결 확인 중")
    }

    /** Capture at the event boundary, before dispatching to a worker or waiting behind other I/O. */
    @Synchronized internal fun capture(): Capture = binding

    @Synchronized private fun isBound(target: Capture): Boolean = binding === target

    @Synchronized private fun canRefresh(target: Capture): Boolean =
        binding === target && session?.valid() == true

    @Synchronized private fun update(target: Capture, change: (WatchSnapshot) -> WatchSnapshot) {
        if (binding === target && session?.valid() == true) mutable.value = change(mutable.value)
    }

    private fun reachableWatch(transport: WatchTransport, target: Capture): Node? {
        val nodes = transport.reachableNodes()
        val node = nodes.singleOrNull()
        if (node == null) {
            update(target) { it.copy(connection = if (nodes.isEmpty())
                "미연결 · 워치 앱·페어링 확인" else "여러 워치 연결됨 · 한 대만 연결해주세요") }
            return null
        }
        update(target) { it.copy(connection = when {
            readings.source != null && readings.source != node.id -> "워치 변경됨 · 다시 로그인해주세요"
            node.isNearby -> "연결됨 · 가까운 기기"
            else -> "연결됨 · 원격 경로"
        }) }
        return node
    }

    private fun consume(transport: WatchTransport, target: Capture, node: String, values: Map<String, Any?>) {
        val delivery = synchronized(this) {
            if (binding !== target) return
            val id = if (WatchLinkProtocol.SUMMARY_ID in values) {
                (values[WatchLinkProtocol.SUMMARY_ID] as? String)
                    ?.takeIf(WatchLinkProtocol::validId) ?: return
            } else null

            var snapshot = mutable.value
            val owner = session
            val result = if (owner?.valid() != true) {
                Result.SIGN_IN_REQUIRED
            } else {
                val now = System.currentTimeMillis()
                val previous = readings.latest
                val sample = WatchSummary.parse(values, now)
                val verdict = if (sample == null) Result.INVALID_DATA else readings.admit(node, sample, id)
                snapshot = when {
                    sample == null -> snapshot.copy(notice = "워치 데이터 형식 또는 기기 시각을 확인해주세요.")
                    verdict == Result.NEW_WINDOW_REQUIRED -> snapshot.copy(notice =
                        if (sample.windowStart < readings.signedInAt) "로그인 이후의 새 측정 구간을 기다리고 있습니다."
                        else "이전 구간과 겹치지 않는 새 측정 구간을 기다리고 있습니다.")
                    verdict == Result.ACCEPTED && readings.latest !== previous -> snapshot.copy(
                        summary = sample, receivedAt = now,
                        notice = "${sample.intervalMinutes}분 단위 측정 요약 · 실시간 값이 아닙니다.")
                    else -> snapshot
                }
                verdict
            }

            if (owner?.valid() == true) mutable.value = snapshot
            if (binding !== target || id == null) return
            // The non-blocking enqueue and bind/clear share this monitor; delivery does not.
            val verdict = if (owner?.valid() == true) result else Result.SIGN_IN_REQUIRED
            try {
                transport.enqueueReceipt(node, WatchLinkProtocol.RECEIPT_PATH,
                    WatchLinkProtocol.encode(WatchLinkProtocol.Receipt(id, verdict)))
            } catch (_: Exception) {
                Log.w(TAG, "Receipt could not be enqueued")
                null
            }
        }
        delivery?.addOnFailureListener { Log.w(TAG, "Receipt could not be delivered") }
    }

    /** Called only on the WearableListenerService worker thread, including while logged out. */
    internal fun receiveBlocking(
        transport: WatchTransport, target: Capture, node: String, values: Map<String, Any?>
    ): Unit = ioLock.withLock {
        if (node.isBlank() || !isBound(target)) return@withLock
        try {
            if (reachableWatch(transport, target)?.id == node) consume(transport, target, node, values)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            Log.w(TAG, "Unable to verify watch source")
        }
    }

    /** Call on IO or a WearableListenerService callback thread, never on the main thread. */
    internal fun refreshBlocking(context: Context, target: Capture) {
        if (canRefresh(target)) refreshBlocking(WearWatchTransport(context), target)
    }

    internal fun refreshBlocking(transport: WatchTransport, target: Capture): Unit = ioLock.withLock {
        if (!canRefresh(target)) return@withLock
        try {
            val node = reachableWatch(transport, target)?.id ?: return@withLock
            if (!canRefresh(target)) return@withLock
            val hasItems = transport.readSummaries(node) { consume(transport, target, node, it) }
            if (!hasItems) update(target) { it.copy(
                notice = "워치에서 측정을 시작한 후 ‘지금 전송’을 눌러주세요.") }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            Log.w(TAG, "Refresh failed")
            update(target) { it.copy(connection = "연결 확인 실패 · Google Play 서비스와 워치 설정 확인") }
        }
    }
}
