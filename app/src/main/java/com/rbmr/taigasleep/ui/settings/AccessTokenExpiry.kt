package com.rbmr.taigasleep.ui.settings

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** How a token's row says what its expiry means. */
internal enum class TokenExpiry {
  /** The token never expires. */
  NEVER,
  /** The token stops working at a time that has not come. */
  ACTIVE,
  /** The lifetime has ended and the server refuses the token. */
  EXPIRED,
}

/** Which expiry a token has, from what the server answered about it. */
internal fun expiryKind(expiresAt: String?, expired: Boolean): TokenExpiry =
    when {
      expired -> TokenExpiry.EXPIRED
      expiresAt == null -> TokenExpiry.NEVER
      else -> TokenExpiry.ACTIVE
    }

/** The expiry time in the phone's zone, or nothing when the token has no expiry. */
internal fun expiryDate(expiresAt: String?): String? = expiresAt?.let { dateLabel(it) }

/** A server stamp in the phone's zone, or the stamp itself when it cannot be read. */
internal fun dateLabel(stamp: String): String =
    runCatching {
          OffsetDateTime.parse(stamp)
              .atZoneSameInstant(ZoneId.systemDefault())
              .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        .getOrDefault(stamp)
