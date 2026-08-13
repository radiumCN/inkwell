package com.radium.inkwell.core.util

/** 小写十六进制。缓存 key、书 id、更新包 sha256、书源 digest 都走这一份，别各写 `%02x`。 */
fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
