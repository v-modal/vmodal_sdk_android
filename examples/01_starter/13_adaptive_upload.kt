package com.vmodal.sdk.examples

import com.vmodal.sdk.AdaptiveUploadPolicy
import com.vmodal.sdk.UploadConditions
import com.vmodal.sdk.UploadDeviceMemory
import com.vmodal.sdk.UploadNetworkSpeed
import com.vmodal.sdk.UploadNetworkType
import com.vmodal.sdk.VideoUploadOptions

fun adaptiveOptions(fileSize: Long): VideoUploadOptions {
    val conditions = UploadConditions(
        networkType = UploadNetworkType.WIFI,
        networkSpeed = UploadNetworkSpeed.FAST,
        deviceMemory = UploadDeviceMemory.HIGH,
    )
    val preset = AdaptiveUploadPolicy.select(fileSize, conditions)
    println("preset=${preset.name} concurrency=${preset.maxConcurrency}")
    return VideoUploadOptions(adaptiveConditions = conditions)
}
