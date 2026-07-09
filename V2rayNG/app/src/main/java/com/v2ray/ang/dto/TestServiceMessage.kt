package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val successLimit: Int = 0,
    val pruneToSuccessLimit: Boolean = false,
    val validateStartupConfig: Boolean = false
) : Serializable

