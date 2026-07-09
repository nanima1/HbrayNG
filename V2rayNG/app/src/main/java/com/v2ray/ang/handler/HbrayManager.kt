package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

object HbrayManager {
    const val SUBSCRIPTION_ID = "hbray_builtin_sources"

    private const val WORK_NAME = "hbray_builtin_hourly_update"
    private const val PREF_BOOTSTRAPPED = "hbray_bootstrapped"
    private const val PREF_TG_TOKEN = "hbray_tg_token"
    private const val PREF_TG_CHAT_ID = "hbray_tg_chat_id"

    private val builtInSources = listOf(
        "https://raw.githubusercontent.com/4n0nymou3/multi-proxy-config-fetcher/refs/heads/main/configs/proxy_configs.txt",
        "https://raw.githubusercontent.com/hiddify/hiddify-app/refs/heads/main/test.configs/mahsa"
    )

    fun bootstrap(context: Context) {
        ensureSubscription()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        SubscriptionUpdater.cancelOne(context, SUBSCRIPTION_ID)
        if (!MmkvManager.decodeSettingsBool(PREF_BOOTSTRAPPED, false)) {
            MmkvManager.encodeSettings(PREF_BOOTSTRAPPED, true)
            MmkvManager.encodeSettings(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, true)
            MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)
        }
    }

    fun needsInitialUpdate(): Boolean {
        return MmkvManager.decodeServerList(SUBSCRIPTION_ID).isEmpty()
    }

    fun saveTelegram(token: String, chatId: String) {
        if (token.isNotBlank()) {
            MmkvManager.encodeSettings(PREF_TG_TOKEN, token.trim())
        }
        if (chatId.isNotBlank()) {
            MmkvManager.encodeSettings(PREF_TG_CHAT_ID, chatId.trim())
        }
    }

    fun hasTelegram(): Boolean {
        return !MmkvManager.decodeSettingsString(PREF_TG_TOKEN, "").isNullOrBlank()
                && !MmkvManager.decodeSettingsString(PREF_TG_CHAT_ID, "").isNullOrBlank()
    }

    fun updateBuiltInSources(): Int {
        ensureSubscription()
        val links = linkedSetOf<String>()
        val visited = linkedSetOf<String>()
        builtInSources.forEach { fetchRecursive(it, links, visited, 0) }
        if (links.isEmpty()) {
            MmkvManager.removeServerViaSubid(SUBSCRIPTION_ID)
            touchSubscriptionUpdatedAt()
            return 0
        }
        MmkvManager.removeServerViaSubid(SUBSCRIPTION_ID)
        val imported = AngConfigManager.importBatchConfig(links.joinToString("\n"), SUBSCRIPTION_ID, true).first
        renameByRegionAndProtocol()
        touchSubscriptionUpdatedAt()
        return imported
    }

    fun availableGuidList(): List<String> {
        return MmkvManager.decodeServerList(SUBSCRIPTION_ID)
            .filter { guid -> (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) > 0L }
            .sortedBy { guid -> MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE }
    }

    fun exportAvailableSubscription(): String {
        val available = availableGuidList()
        return AngConfigManager.exportNonCustomConfigs(available)
    }

    fun isHbrayGuid(guid: String?): Boolean {
        if (guid.isNullOrBlank()) {
            return false
        }
        return MmkvManager.decodeServerConfig(guid)?.subscriptionId == SUBSCRIPTION_ID
    }

    fun markStartupFailed(guid: String?) {
        if (guid.isNullOrBlank()) {
            return
        }
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        if (profile.subscriptionId == SUBSCRIPTION_ID) {
            MmkvManager.encodeServerTestDelayMillis(guid, -1)
        }
    }

    fun selectBestAvailableForStart(context: Context): Boolean {
        val current = MmkvManager.getSelectServer()
        val currentConfig = current?.let { MmkvManager.decodeServerConfig(it) }
        if (currentConfig != null && currentConfig.subscriptionId != SUBSCRIPTION_ID) {
            return true
        }
        val best = availableGuidList().firstOrNull { canBuildStartupConfig(context, it) }
        if (best != null) {
            MmkvManager.setSelectServer(best)
            return true
        }
        if (currentConfig?.subscriptionId == SUBSCRIPTION_ID) {
            return false
        }
        val fallback = MmkvManager.decodeAllServerList().firstOrNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.subscriptionId != SUBSCRIPTION_ID
        } ?: return false
        MmkvManager.setSelectServer(fallback)
        return true
    }

    fun keepTopAvailableAndSelect(context: Context, limit: Int): List<String> {
        if (limit <= 0) {
            return emptyList()
        }
        val keep = availableGuidList()
            .filter { canBuildStartupConfig(context, it) }
            .take(limit)
        val keepSet = keep.toSet()
        MmkvManager.decodeServerList(SUBSCRIPTION_ID)
            .filterNot { keepSet.contains(it) }
            .forEach { MmkvManager.removeServer(it) }
        MmkvManager.encodeServerList(keep.toMutableList(), SUBSCRIPTION_ID)
        if (keep.isNotEmpty()) {
            MmkvManager.setSelectServer(keep.first())
            renameByRegionAndProtocol()
        }
        return keep
    }

    fun pushAvailableToTelegram(): String {
        val token = MmkvManager.decodeSettingsString(PREF_TG_TOKEN, "").orEmpty()
        val chatId = MmkvManager.decodeSettingsString(PREF_TG_CHAT_ID, "").orEmpty()
        if (token.isBlank() || chatId.isBlank()) {
            throw IllegalStateException("Telegram bot token/chat id is empty")
        }
        val content = exportAvailableSubscription()
        if (content.isBlank()) {
            throw IllegalStateException("No Hbray nodes to push")
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val fileBody = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", "HbrayNG available nodes: ${availableGuidList().size}")
            .addFormDataPart("document", "hbray-available.txt", fileBody)
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendDocument")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Telegram API ${response.code}: $text")
            }
            return text
        }
    }

    private fun ensureSubscription() {
        val existing = MmkvManager.decodeSubscription(SUBSCRIPTION_ID)
        if (existing != null) {
            if (existing.autoUpdate || existing.updateInterval != 1440L) {
                existing.autoUpdate = false
                existing.updateInterval = 1440
                MmkvManager.encodeSubscription(SUBSCRIPTION_ID, existing)
            }
            return
        }
        MmkvManager.encodeSubscription(
            SUBSCRIPTION_ID,
            SubscriptionItem(
                remarks = "Hbray Built-in",
                url = builtInSources.joinToString("\n"),
                enabled = true,
                autoUpdate = false,
                updateInterval = 1440,
                allowInsecureUrl = false,
                userAgent = "HbrayNG"
            )
        )
    }

    private fun touchSubscriptionUpdatedAt() {
        MmkvManager.decodeSubscription(SUBSCRIPTION_ID)?.let {
            it.lastUpdated = System.currentTimeMillis()
            MmkvManager.encodeSubscription(SUBSCRIPTION_ID, it)
        }
    }

    private fun fetchRecursive(url: String, links: LinkedHashSet<String>, visited: LinkedHashSet<String>, depth: Int) {
        if (depth > 2 || url.isBlank() || visited.contains(url)) {
            return
        }
        visited.add(url)
        val text = try {
            HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(
                    url = url,
                    timeout = 25000,
                    userAgent = "HbrayNG/0.2"
                )
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Hbray fetch failed: $url", e)
            ""
        }
        if (text.isBlank()) {
            return
        }
        val decoded = decodePossibleSubscription(text)
        decoded.lineSequence()
            .map { it.trim() }
            .filter { isProxyUri(it) }
            .forEach { links.add(it) }
        if (depth < 2) {
            text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .filterNot { isProxyUri(it) }
                .forEach { fetchRecursive(it, links, visited, depth + 1) }
        }
    }

    private fun renameByRegionAndProtocol() {
        val list = MmkvManager.decodeServerList(SUBSCRIPTION_ID)
        list.forEachIndexed { index, guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEachIndexed
            val code = inferCountryCode(profile.remarks).ifBlank { "ZZ" }
            val flag = countryFlag(code)
            val protocol = profile.configType.name.uppercase(Locale.ROOT)
            val port = profile.serverPort.orEmpty().ifBlank { "0" }
            profile.remarks = "$flag $code | $protocol | $port | ${"%03d".format(index + 1)}"
            profile.description = AngConfigManager.generateDescription(profile)
            MmkvManager.encodeProfileDirect(guid, JsonUtil.toJson(profile))
        }
    }

    private fun inferCountryCode(name: String?): String {
        val text = name.orEmpty()
        countryFromFlag(text)?.let { return it }
        Regex("(?i)(?:^|\\s|-|_)([A-Z]{2})(?:\\s|-|_|$)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
            ?.let { return it }
        return ""
    }

    private fun countryFromFlag(text: String): String? {
        val cps = text.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]
            val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                return "${('A'.code + a - 0x1F1E6).toChar()}${('A'.code + b - 0x1F1E6).toChar()}"
            }
        }
        return null
    }

    private fun countryFlag(code: String): String {
        if (code.length != 2 || code == "ZZ") {
            return "\uD83C\uDFF3"
        }
        val upper = code.uppercase(Locale.ROOT)
        val first = Character.toChars(upper[0].code - 'A'.code + 0x1F1E6)
        val second = Character.toChars(upper[1].code - 'A'.code + 0x1F1E6)
        return String(first) + String(second)
    }

    private fun decodePossibleSubscription(text: String): String {
        if (text.contains("://")) {
            return text
        }
        return try {
            val cleaned = text.trim().replace("\\s+".toRegex(), "")
            val decoded = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.contains("://")) decoded else text
        } catch (_: Exception) {
            text
        }
    }

    private fun isProxyUri(line: String): Boolean {
        val value = line.lowercase(Locale.ROOT)
        return value.startsWith(EConfigType.VMESS.protocolScheme)
                || value.startsWith(EConfigType.VLESS.protocolScheme)
                || value.startsWith(EConfigType.TROJAN.protocolScheme)
                || value.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)
                || value.startsWith(EConfigType.SOCKS.protocolScheme)
                || value.startsWith(EConfigType.HYSTERIA2.protocolScheme)
                || value.startsWith(AppConfig.HY2)
    }

    private fun canBuildStartupConfig(context: Context?, guid: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        if (profile.subscriptionId != SUBSCRIPTION_ID) {
            return true
        }
        if (context == null) {
            return true
        }
        val result = CoreConfigManager.getV2rayConfig(context, guid)
        if (!result.status) {
            LogUtil.e(AppConfig.TAG, "Hbray startup config rejected: ${profile.remarks}: ${result.errorMessage}")
        }
        return result.status && result.content.isNotBlank()
    }
}
