/*
 * PC接続先URLとAPIパスの共通検証を行う。
 */
package com.example.profilescouter.core.network

import java.net.URI

internal fun normalizeEndpointPath(value: String): String {
    val path = "/" + value.trim().trimStart('/')
    val uri = URI(path)
    require(!value.trim().startsWith("//") && !path.contains("://") && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath == path && !path.any { it.isWhitespace() }) {
        "/hello や /image のようなパスを入力してください"
    }
    return path
}

internal fun normalizeServerUrl(value: String): String {
    val input = value.trim().trimEnd('/')
    require(input.isNotBlank()) { "PCのIPアドレスとポートを入力してください" }
    val completed = if (input.contains("://")) input else "http://$input"
    val uri = try { URI(completed) } catch (_: Exception) {
        throw IllegalArgumentException("URLの形式を確認してください")
    }
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.path.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)) {
        "192.168.1.10:8000 のように入力してください（/recognizeは不要）"
    }
    return uri.toString()
}
