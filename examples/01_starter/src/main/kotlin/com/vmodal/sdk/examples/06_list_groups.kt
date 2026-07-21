package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

fun printVideoGroups(sdk: Client) {
    val groups = sdk.collections.listGroups(mode = "vid_file")
    println("groups=${groups.total}")
    groups.data.forEach(::println)
}
