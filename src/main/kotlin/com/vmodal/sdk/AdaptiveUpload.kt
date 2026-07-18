package com.vmodal.sdk

/** Caller-observed network transport used by adaptive upload policy. */
enum class UploadNetworkType { WIFI, CELLULAR, UNKNOWN }

/** Coarse caller-observed network speed. */
enum class UploadNetworkSpeed { SLOW, STANDARD, FAST, UNKNOWN }

/** Coarse memory capacity used to limit concurrent upload work. */
enum class UploadDeviceMemory { LOW, STANDARD, HIGH }

/**
 * Platform observations supplied to [AdaptiveUploadPolicy].
 *
 * @property networkType active connection type
 * @property networkSpeed observed connection speed
 * @property deviceMemory device memory tier
 */
data class UploadConditions(
    val networkType: UploadNetworkType = UploadNetworkType.UNKNOWN,
    val networkSpeed: UploadNetworkSpeed = UploadNetworkSpeed.UNKNOWN,
    val deviceMemory: UploadDeviceMemory = UploadDeviceMemory.STANDARD,
)

/**
 * Resolved immutable multipart tuning values.
 *
 * @property name stable preset name
 * @property partSizeBytes target bytes per part
 * @property maxConcurrency maximum parallel parts
 * @property maxPartAttempts maximum attempts per part
 * @property partTimeoutSeconds timeout for each part
 */
data class AdaptiveUploadPreset(
    val name: String,
    val partSizeBytes: Long,
    val maxConcurrency: Int,
    val maxPartAttempts: Int,
    val partTimeoutSeconds: Long,
)

/** Pure policy: callers translate Android observations into [UploadConditions]. */
object AdaptiveUploadPolicy {
    /** Selects a deterministic preset for [sizeBytes] and [conditions]. */
    fun select(sizeBytes: Long, conditions: UploadConditions): AdaptiveUploadPreset {
        if (sizeBytes < 0) throw ValidationFailed("size_bytes must not be negative")
        val tier = when {
            sizeBytes < 100L * MIB -> "small"
            sizeBytes < GIB -> "medium"
            sizeBytes < 8L * GIB -> "large"
            else -> "huge"
        }
        val profile = when {
            conditions.deviceMemory == UploadDeviceMemory.LOW -> "conservative"
            conditions.networkType == UploadNetworkType.CELLULAR && conditions.networkSpeed == UploadNetworkSpeed.SLOW -> "conservative"
            conditions.networkType == UploadNetworkType.CELLULAR -> "cellular"
            conditions.networkType == UploadNetworkType.WIFI && conditions.networkSpeed == UploadNetworkSpeed.SLOW -> "conservative"
            conditions.networkType == UploadNetworkType.WIFI &&
                conditions.networkSpeed == UploadNetworkSpeed.FAST &&
                conditions.deviceMemory == UploadDeviceMemory.HIGH -> "fast"
            conditions.networkType == UploadNetworkType.WIFI -> "balanced"
            else -> "conservative"
        }
        val values = PRESETS.getValue(tier).getValue(profile)
        val partSize = maxOf(values[0] * MIB, minPartSize(sizeBytes))
        return AdaptiveUploadPreset(
            name = "${tier}_$profile",
            partSizeBytes = partSize,
            maxConcurrency = values[1].toInt(),
            maxPartAttempts = values[2].toInt(),
            partTimeoutSeconds = values[3],
        )
    }

    private fun minPartSize(sizeBytes: Long): Long {
        if (sizeBytes <= 0) return 5L * MIB
        val bytes = 1 + (sizeBytes - 1) / MAX_PARTS
        return maxOf(5L * MIB, ((bytes + MIB - 1) / MIB) * MIB)
    }

    private val PRESETS = mapOf(
        "small" to mapOf(
            "conservative" to longArrayOf(5, 1, 6, 360),
            "cellular" to longArrayOf(8, 2, 5, 300),
            "balanced" to longArrayOf(16, 3, 4, 240),
            "fast" to longArrayOf(32, 4, 3, 180),
        ),
        "medium" to mapOf(
            "conservative" to longArrayOf(8, 1, 6, 480),
            "cellular" to longArrayOf(16, 2, 5, 360),
            "balanced" to longArrayOf(32, 3, 4, 300),
            "fast" to longArrayOf(64, 4, 3, 240),
        ),
        "large" to mapOf(
            "conservative" to longArrayOf(16, 1, 7, 720),
            "cellular" to longArrayOf(32, 2, 6, 600),
            "balanced" to longArrayOf(64, 3, 5, 480),
            "fast" to longArrayOf(128, 4, 4, 360),
        ),
        "huge" to mapOf(
            "conservative" to longArrayOf(32, 1, 8, 900),
            "cellular" to longArrayOf(64, 2, 7, 720),
            "balanced" to longArrayOf(128, 3, 6, 600),
            "fast" to longArrayOf(256, 4, 5, 480),
        ),
    )

    private const val MIB = 1024L * 1024
    private const val GIB = 1024L * MIB
    private const val MAX_PARTS = 10_000L
}
