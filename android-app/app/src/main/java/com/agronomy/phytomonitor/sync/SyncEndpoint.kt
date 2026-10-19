package com.agronomy.phytomonitor.sync

import java.net.URI
import java.util.Locale

object SyncEndpoint {
    fun normalize(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Неверный адрес сервера.") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        require(scheme in setOf("https", "http") && !host.isNullOrEmpty() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535)) {
            "Нужен адрес вида https://server:порт без пароля, пути и параметров."
        }
        require(scheme == "https" || isLocal(host!!)) {
            "HTTP разрешён только для локального IP. Для других серверов нужен HTTPS."
        }
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    private fun isLocal(host: String): Boolean {
        if (host == "localhost" || host == "[::1]") return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 127 || octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)
    }
}
