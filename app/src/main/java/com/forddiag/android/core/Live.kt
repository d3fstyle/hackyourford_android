package com.forddiag.android.core

enum class LiveSource { DID, PID }

data class LiveSample(
    val tsMs: Long,
    val source: LiveSource,
    val id: Int,
    val rawHex: String,
    val decoded: String?,
    val moduleId: String?,
    val label: String?,
    val value: Double? = null,
    val unit: String? = null,
)

data class LiveSessionFile(
    val startedAtUnixMs: Long,
    val profileId: String?,
    val address: String,
    val samples: List<LiveSample>,
)

data class ModuleDtcGroup(
    val moduleId: String,
    val moduleName: String,
    val txId: Int,
    val bus: CanBus,
    val online: Boolean,
    val error: String?,
    val dtcs: List<Dtc>,
)

data class EcuDiscoverProgress(
    val bus: CanBus,
    val currentId: Int,
    val fromId: Int,
    val toId: Int,
    val found: Int,
)

data class DiscoveredEcu(
    val txId: Int,
    val rxId: Int,
    val bus: CanBus,
    val online: Boolean,
    val profileId: String?,
    val name: String?,
    val identity: List<DidValue>,
    val error: String?,
)

/** Pinned DID for live polling: moduleId, did, optional label. */
data class LiveDidPin(
    val moduleId: String,
    val did: Int,
    val label: String?,
)
