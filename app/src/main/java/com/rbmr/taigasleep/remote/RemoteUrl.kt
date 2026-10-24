package com.rbmr.taigasleep.remote

/**
 * Validation and normalization of the server address typed in Settings.
 *
 * A bare host is accepted (`nas:8090`); plain HTTP is prepended for local or private addresses and
 * HTTPS everywhere else. An explicit `http://` is only accepted for local or private addresses,
 * because the password and the token would otherwise travel in clear text over the internet.
 */
data class RemoteAddress(val baseUrl: String, val insecure: Boolean)

object RemoteUrl {
  fun parse(raw: String): Result<RemoteAddress> {
    var text = raw.trim()
    if (text.isEmpty()) return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))

    if (!text.contains("://")) {
      val host = bareHost(text)
      text = if (isLocalOrPrivate(host)) "http://$text" else "https://$text"
    }

    val uri =
        try {
          java.net.URI(text)
        } catch (_: Exception) {
          return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
        }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
      return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
    }
    val host =
        uri.host?.lowercase()?.removeSuffix(".")?.removePrefix("[")?.removeSuffix("]")
            ?: return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
    if (host.isBlank()) return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
    // Credentials in the URL would end up in logs and in the saved configuration.
    if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
      return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
    }
    if (uri.port != -1 && uri.port !in 1..65535) {
      return Result.failure(RemoteUrlException(RemoteError.InvalidUrl))
    }

    val insecure = scheme == "http"
    if (insecure && !isLocalOrPrivate(host)) {
      return Result.failure(RemoteUrlException(RemoteError.InsecureUrl))
    }

    val path = uri.rawPath.orEmpty().trimEnd('/')
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val authority = if (host.contains(':')) "[$host]" else host
    val baseUrl = "${scheme}://$authority$port$path"
    return Result.success(RemoteAddress(baseUrl, insecure))
  }

  private fun bareHost(text: String): String {
    val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
    return if (authority.startsWith("[")) {
      authority.substringAfter('[').substringBefore(']')
    } else {
      authority.substringBefore(':').lowercase()
    }
  }

  /** True for loopback, RFC 1918, CGNAT/Tailscale, link-local and local-only host names. */
  fun isLocalOrPrivate(host: String): Boolean {
    val h = host.lowercase().removeSuffix(".").removePrefix("[").removeSuffix("]")
    if (h == "localhost" || h.endsWith(".localhost")) return true
    if (!h.contains(':') && !h.contains('.')) return true // single-label names like `nas`
    if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
    if (h.endsWith(".ts.net")) return true

    if (h.contains(':')) {
      return h == "::1" ||
          h == "0:0:0:0:0:0:0:1" ||
          h.startsWith("fe80:") ||
          h.startsWith("fc") ||
          h.startsWith("fd")
    }

    val octets = h.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b, _, _) = octets
    return when {
      a == 127 -> true
      a == 10 -> true
      a == 172 && b in 16..31 -> true
      a == 192 && b == 168 -> true
      a == 169 && b == 254 -> true
      a == 100 && b in 64..127 -> true
      a == 0 -> true
      else -> false
    }
  }
}

class RemoteUrlException(val error: RemoteError) : IllegalArgumentException(error.label)
