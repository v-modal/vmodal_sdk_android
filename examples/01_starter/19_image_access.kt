package com.vmodal.sdk.examples

import com.vmodal.sdk.Client
import com.vmodal.sdk.ImageUrlRecord

data class DownloadedImage(val path: String, val bytes: ByteArray)

fun downloadImage(sdk: Client): DownloadedImage {
    val image = sdk.images.getUrl(
        mode = "img_file",
        groupName = "product-images",
        modality = "image",
        filename = "shoe-001.jpg",
    )
    check(image.found) { image.error }
    return DownloadedImage(
        path = image.fullPath,
        bytes = sdk.images.getImageFromUrl(image.urlPreSigned),
    )
}

fun resolveImageBatch(sdk: Client) = sdk.images.getUrlBulk(
    records = listOf(
        ImageUrlRecord(
            mode = "img_file",
            groupName = "product-images",
            modality = "image",
            filename = "shoe-001.jpg",
        ).toMap()
    )
)
