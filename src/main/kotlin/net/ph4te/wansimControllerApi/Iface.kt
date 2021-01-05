package net.ph4te.wansimControllerApi

data class Iface(
    val name: String,
    var loss: Int = 0,
    var delay: Int = 0
)