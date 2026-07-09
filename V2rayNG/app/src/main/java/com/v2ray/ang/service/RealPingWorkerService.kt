package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val successLimit: Int = 0,
    private val validateStartupConfig: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = if (successLimit > 0) {
        SettingsManager.getRealPingConcurrency().coerceAtMost(8)
    } else {
        SettingsManager.getRealPingConcurrency()
    }
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val nextIndex = AtomicInteger(0)
    private val testedCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val stopRequested = AtomicBoolean(false)

    fun start() {
        val workers = (0 until concurrency).map {
            scope.launch {
                while (!stopRequested.get()) {
                    if (successLimit > 0 && successCount.get() >= successLimit) {
                        stopRequested.set(true)
                        break
                    }

                    val index = nextIndex.getAndIncrement()
                    if (index >= guids.size) {
                        break
                    }

                    val guid = guids[index]
                    try {
                        val result = startRealPing(guid)
                        val usableResult = if (result > 0L && validateStartupConfig && !canBuildStartupConfig(guid)) {
                            -1L
                        } else {
                            result
                        }
                        onEvent(RealPingEvent.Result(guid, usableResult))
                        if (successLimit > 0 && usableResult > 0L && successCount.incrementAndGet() >= successLimit) {
                            stopRequested.set(true)
                            break
                        }
                    } catch (_: Throwable) {
                        // ignore one node failure and keep testing the remaining candidates
                    } finally {
                        val tested = testedCount.incrementAndGet()
                        val left = (guids.size - tested).coerceAtLeast(0)
                        val found = successCount.get().coerceAtMost(successLimit.takeIf { it > 0 } ?: Int.MAX_VALUE)
                        val progress = if (successLimit > 0) "$found/$successLimit ok, $left left" else "$left"
                        onEvent(RealPingEvent.Progress(progress))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*workers.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: Throwable) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        stopRequested.set(true)
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }

    private fun canBuildStartupConfig(guid: String): Boolean {
        val configResult = CoreConfigManager.getV2rayConfig(context, guid)
        return configResult.status && configResult.content.isNotBlank()
    }
}
