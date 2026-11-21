package com.example.safetymonitor

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import com.example.safetymonitor.sos.SosSession
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.ui.WorkSafePage
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.watch.WatchDataRepository
import com.example.safetymonitor.watch.WatchTransport
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** Synthetic repository transport; this does not exercise GMS or a physical watch. */
object WatchLinkChecks {
    private fun session() = SosSession("http://127.0.0.1:1", 77, "synthetic", Instant.parse("2099-01-01T00:00:00Z"))
    private fun sample(start: Long, end: Long, id: String? = UUID.randomUUID().toString()) = mutableMapOf<String, Any?>(
        "window_start" to start, "timestamp" to end, "interval_minutes" to 5,
        "has_hr" to true, "hr_avg" to 78.5, "hr_count" to 2,
        "has_skin" to true, "skin_c" to 33.2, "skin_count" to 2,
        "has_ambient" to true, "ambient_c" to 22.0
    ).apply { if (id != null) put("summary_id", id) }
    private fun node(id: String): Node = Proxy.newProxyInstance(Node::class.java.classLoader, arrayOf(Node::class.java)) { proxy, method, args ->
        when (method.name) {
            "getId", "getDisplayName" -> id
            "isNearby" -> true
            "hashCode" -> id.hashCode()
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> id
            else -> null
        }
    } as Node
    private class Transport : WatchTransport {
        var nodes = setOf(node("watch"))
        var beforeLookup: () -> Unit = {}
        var beforeConsume: () -> Unit = {}
        var values: Map<String, Any?>? = null
        val receipts = mutableListOf<String>()
        override fun reachableNodes(): Set<Node> { beforeLookup(); return nodes }
        override fun readSummaries(node: String, consume: (Map<String, Any?>) -> Unit): Boolean {
            beforeConsume(); values?.let(consume); return true
        }
        override fun enqueueReceipt(node: String, path: String, payload: ByteArray): Task<Int> {
            check(node == "watch" && path == "/workersafety/summary/receipt")
            receipts.add(payload.toString(Charsets.UTF_8)); return Tasks.forResult(0)
        }
    }
    fun run(instrumentation: Instrumentation, results: StringBuilder) {
        fun bind(value: SosSession?) = instrumentation.runOnMainSync { SosStore.connect(value) }
        fun receive(t: Transport, values: Map<String, Any?>, source: String = "watch") =
            WatchDataRepository.receiveBlocking(t, WatchDataRepository.capture(), source, values)
        val t = Transport()
        try {
            bind(null)
            val start = System.currentTimeMillis() + 1000
            val first = sample(start, start + 1000)
            receive(t, first)
            val invalidSignedOut = first.toMutableMap().apply { remove("timestamp") }
            t.receipts.clear()
            receive(t, invalidSignedOut)
            check(t.receipts.single() == "${first["summary_id"]}|SIGN_IN_REQUIRED")
            t.receipts.clear()
            bind(session())
            t.nodes = emptySet(); receive(t, first); check(t.receipts.isEmpty())
            t.nodes = setOf(node("watch")); receive(t, first, "other"); check(t.receipts.isEmpty())
            receive(t, sample(start, start + 1000, "invalid")); check(t.receipts.isEmpty())
            receive(t, first); check(t.receipts.single() == "${first["summary_id"]}|ACCEPTED")
            check(WatchDataRepository.state.value.summary?.heartRate == 78.5)
            t.receipts.clear(); receive(t, first); check(t.receipts.single().endsWith("|ACCEPTED"))
            t.receipts.clear()
            val adjacent = sample(start + 1000, start + 2000)
            receive(t, adjacent); check(t.receipts.single().endsWith("|ACCEPTED"))
            t.receipts.clear()
            val changed = first.toMutableMap().apply { put("hr_avg", 79.0) }
            receive(t, changed); check(t.receipts.single().endsWith("|NEW_WINDOW_REQUIRED"))
            bind(session()); t.receipts.clear()
            receive(t, sample(start, start + 1000, null)); check(t.receipts.isEmpty())
            check(WatchDataRepository.state.value.summary != null)
            bind(session()); t.receipts.clear()
            t.beforeLookup = { bind(session()) }
            receive(t, first); check(t.receipts.isEmpty() && WatchDataRepository.state.value.summary == null)
            t.beforeLookup = {}; t.values = first
            t.beforeConsume = { bind(session()) }
            WatchDataRepository.refreshBlocking(t, WatchDataRepository.capture())
            check(t.receipts.isEmpty() && WatchDataRepository.state.value.summary == null)
            t.beforeConsume = {}
            val old = WatchDataRepository.capture(); bind(session())
            WatchDataRepository.refreshBlocking(t, old)
            check(t.receipts.isEmpty() && WatchDataRepository.state.value.summary == null)
            results.appendLine("PASS: synthetic ingress source, UUID, receipts, adjacent windows, legacy and session replacement during receive/refresh")
            bind(null)
            val context = instrumentation.targetContext
            val prefs = context.getSharedPreferences("worksafe_theme", 0)
            val original = prefs.getString("sos_server", null)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var model: WorkSafeViewModel? = null
            val evidence = File(checkNotNull(context.getExternalFilesDir(null)), "watch-link").apply { mkdirs() }
            try {
                instrumentation.runOnMainSync { model = ViewModelProvider(activity as MainActivity)[WorkSafeViewModel::class.java] }
                val m = checkNotNull(model)
                fun capture(name: String) {
                    instrumentation.waitForIdleSync(); Thread.sleep(500)
                    if (name != "login") {
                        val metrics = context.resources.displayMetrics
                        repeat(5) {
                            val x = metrics.widthPixels / 2
                            instrumentation.uiAutomation.executeShellCommand("input swipe $x ${metrics.heightPixels * 3 / 4} $x ${metrics.heightPixels / 3} 350").use { descriptor ->
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                capture("login")
                AccountIntegrationChecks.loginFixture(m)
                check(m.page == WorkSafePage.HOME && WatchDataRepository.state.value.summary == null)
                capture("waiting")
                val now = System.currentTimeMillis() + 1000
                t.receipts.clear(); receive(t, sample(now, now + 1000))
                check(t.receipts.single().endsWith("|ACCEPTED") && WatchDataRepository.state.value.summary?.heartRate == 78.5)
                capture("populated")
                runBlocking { m.logout() }; AccountIntegrationChecks.loginFixture(m)
                check(m.page == WorkSafePage.HOME && WatchDataRepository.state.value.summary == null)
                capture("relogin")
                results.appendLine("PASS: MainActivity login/waiting/populated/relogin screenshots and repository state: ${evidence.absolutePath}")
            } finally {
                runBlocking { model?.logout() }
                instrumentation.runOnMainSync { activity.finish() }
                prefs.edit().apply { if (original == null) remove("sos_server") else putString("sos_server", original) }.commit()
            }
        } finally { bind(null) }
    }
}
