package com.originpods.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OppoModelSpec(
    val id: String,
    val name: String,
    val brand: String,
    val supportSpp: Boolean,
    val uuid: String?,
    val aliases: List<String>,
    val hasFunction: Boolean,
    val modeToBit: Map<NoiseMode, Int>,
    val bitToMode: Map<Int, NoiseMode>,
) {
    val supportedNoiseModes: Set<NoiseMode> get() = modeToBit.keys
}

/**
 * Loads the bundled HeyMelody device whitelist (`device_models.json`) and maps each
 * OPPO / OnePlus / realme model's `productId` and `noiseReductionMode.protocolIndex`
 * to OriginPods [NoiseMode]s.
 */
object OppoModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    private val entries: List<OppoModelSpec> by lazy { loadEntries() }
    private val byId: Map<String, OppoModelSpec> by lazy {
        buildMap {
            for (spec in this@OppoModelCatalog.entries) {
                val id = spec.id.uppercase()
                if (id.isBlank()) continue
                val prev = get(id)
                if (prev != null && prev.hasFunction && !spec.hasFunction) continue
                put(id, spec)
            }
        }
    }

    fun allModels(): List<OppoModelSpec> = entries

    fun byProductId(productId: String): OppoModelSpec? {
        if (productId.isBlank()) return null
        return byId[productId.trim().uppercase()]
    }

    fun byDeviceName(deviceName: String?): OppoModelSpec? {
        if (deviceName.isNullOrBlank()) return null
        val target = normalize(deviceName)
        if (target.isEmpty()) return null

        return matchPreferringFunction { spec ->
            spec.aliases.any { it.isNotEmpty() && it == target }
        } ?: matchPreferringFunction { spec ->
            spec.aliases.any { it.length >= 5 && target.contains(it) }
        } ?: matchPreferringFunction { spec ->
            spec.aliases.any { target.length >= 5 && it.contains(target) }
        }
    }

    private fun matchPreferringFunction(predicate: (OppoModelSpec) -> Boolean): OppoModelSpec? {
        var fallback: OppoModelSpec? = null
        for (spec in entries) {
            if (!predicate(spec)) continue
            if (spec.hasFunction) return spec
            if (fallback == null) fallback = spec
        }
        return fallback
    }

    private fun loadEntries(): List<OppoModelSpec> {
        val text = OppoModelCatalog::class.java.classLoader
            ?.getResourceAsStream("device_models.json")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val array = root["whiteList"]?.jsonArray ?: JsonArray(emptyList())
            array.mapNotNull { element ->
                runCatching { parseEntry(element.jsonObject) }.getOrNull()
            }.sortedByDescending { it.name.length }
        }.getOrDefault(emptyList())
    }

    private fun parseEntry(obj: JsonObject): OppoModelSpec? {
        val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
        val brand = obj["brand"]?.jsonPrimitive?.content.orEmpty()
        val supportSpp = obj["supportSpp"]?.jsonPrimitive?.booleanOrNull ?: true
        val uuid = obj["uuid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val aliases = buildList {
            add(normalize(name))
            obj["children"]?.jsonArray?.forEach { childEl ->
                childEl.jsonObject["name"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(normalize(it)) }
            }
        }

        val func = obj["function"] as? JsonObject
        val modeToBit = LinkedHashMap<NoiseMode, Int>()
        val bitToMode = LinkedHashMap<Int, NoiseMode>()

        val nrm = func?.get("noiseReductionMode") as? JsonArray
        nrm?.forEach { modeEl ->
            val modeObj = modeEl as? JsonObject ?: return@forEach
            val type = modeObj["modeType"]?.jsonPrimitive?.intOrNull ?: return@forEach
            val ownIndex = modeObj["protocolIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val mainMode = modeTypeToNoiseMode(type)
            if (mainMode != null) {
                bitToMode[ownIndex] = mainMode
                modeToBit[mainMode] = ownIndex
            }
            val children = modeObj["childrenMode"] as? JsonArray
            children?.forEach { childEl ->
                val childObj = childEl as? JsonObject ?: return@forEach
                val childType = childObj["modeType"]?.jsonPrimitive?.intOrNull ?: type
                val childIndex = childObj["protocolIndex"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val childMode = childModeToNoiseMode(type, childType) ?: return@forEach
                bitToMode[childIndex] = childMode
                if (childMode != NoiseMode.ANC && childMode != NoiseMode.TRANSPARENCY && childMode != NoiseMode.OFF) {
                    modeToBit.putIfAbsent(childMode, childIndex)
                }
            }
        }

        return OppoModelSpec(
            id = id,
            name = name,
            brand = brand,
            supportSpp = supportSpp,
            uuid = uuid,
            aliases = aliases,
            hasFunction = func != null,
            modeToBit = modeToBit,
            bitToMode = bitToMode,
        )
    }

    private fun modeTypeToNoiseMode(type: Int): NoiseMode? = when (type) {
        1 -> NoiseMode.OFF
        2, 6 -> NoiseMode.TRANSPARENCY
        3 -> NoiseMode.ANC_LIGHT
        4 -> NoiseMode.ANC_DEEP
        5 -> NoiseMode.ANC
        7, 10 -> NoiseMode.ANC_ADAPTIVE
        8 -> NoiseMode.ANC_MEDIUM
        else -> null
    }

    private fun childModeToNoiseMode(parentType: Int, childType: Int): NoiseMode? =
        if (parentType == 2 && childType == 6) NoiseMode.TRANSPARENCY else modeTypeToNoiseMode(childType)

    private fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }
}
