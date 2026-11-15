package com.rbmr.taigasleep.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The part of a token's row that says whether it still works, and until when. */
class AccessTokenExpiryTest {
  @Test
  fun `a token without an expiry never expires`() {
    assertEquals(TokenExpiry.NEVER, expiryKind(expiresAt = null, expired = false))
    assertNull(expiryDate(null))
  }

  @Test
  fun `a token with a later expiry is active`() {
    assertEquals(
        TokenExpiry.ACTIVE,
        expiryKind(expiresAt = "2027-01-10T20:00:00Z", expired = false),
    )
    val shown = expiryDate("2027-01-10T20:00:00Z")
    assertFalse(shown == null || shown.contains("T"))
  }

  @Test
  fun `the server marking a token expired wins over its stamp`() {
    assertEquals(
        TokenExpiry.EXPIRED,
        expiryKind(expiresAt = "2027-01-10T20:00:00Z", expired = true),
    )
  }

  @Test
  fun `an expiry is shown as a date`() {
    val shown = dateLabel("2027-01-10T20:00:00Z")

    assertFalse(shown.contains("T"))
    assertFalse(shown == "2027-01-10T20:00:00Z")
  }

  @Test
  fun `a stamp that cannot be read is shown as it came`() {
    assertEquals("not a date", dateLabel("not a date"))
  }
}
