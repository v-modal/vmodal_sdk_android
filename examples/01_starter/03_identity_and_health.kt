package com.vmodal.sdk.examples

import com.vmodal.sdk.Client

fun printIdentityAndHealth(sdk: Client) {
    val me = sdk.auth.me()
    val health = sdk.health()
    println("user=${me.userId} email=${me.email}")
    println("status=${health.status} version=${health.version}")
}
