package com.example.proxyshare.ui.screens

/** Port 1024-65535, or null when the text is not a valid port. */
fun validatePort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1024..65535 }

/** "192.168.1.0/24" or a bare "10.0.0.5" (implies /32). Anything else is invalid. */
fun validateCidr(text: String): Boolean {
    val ip = text.substringBefore('/')
    val bits = if ('/' in text) text.substringAfter('/') else "32"
    val octets = ip.split('.')
    if (octets.size != 4 || octets.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return false
    return (bits.toIntOrNull() ?: -1) in 0..32
}

/** A whole number inside [range], or null when not a number or out of range. */
fun validateIntInRange(text: String, range: IntRange): Int? =
    text.toIntOrNull()?.takeIf { it in range }
