package com.rbmr.taigasleep.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one row of settings → Notifications says: on only when Android allows the app and every
 * channel the type posts on. Nothing is stored, so the phone's settings are the only source.
 */
class NotificationTypeStateTest {
  @Test
  fun `a row is on when Android allows the app and the channel`() {
    assertTrue(notificationTypeState(NotificationType.UPLOADS, FakeNotificationAccess()).on)
  }

  @Test
  fun `a row is off when the app's notifications are off`() {
    val access = FakeNotificationAccess(appAllowed = false)

    assertFalse(notificationTypeState(NotificationType.UPLOADS, access).on)
  }

  @Test
  fun `a row is off when its channel is blocked`() {
    val access = FakeNotificationAccess()
    access.block(Channels.UPLOADS)

    assertFalse(notificationTypeState(NotificationType.UPLOADS, access).on)
  }

  @Test
  fun `a row with two channels is off when one of them is blocked`() {
    val access = FakeNotificationAccess()
    access.block(Channels.WAKE_ALARM_CONTROL)

    assertFalse(notificationTypeState(NotificationType.WAKE_ALARM, access).on)
    assertTrue(access.channelAllowed(Channels.WAKE_ALARM))
  }

  @Test
  fun `a row with two channels is on when both are allowed`() {
    assertTrue(notificationTypeState(NotificationType.WAKE_ALARM, FakeNotificationAccess()).on)
  }

  @Test
  fun `an app block turns every row off, whatever its channels say`() {
    val access = FakeNotificationAccess(appAllowed = false)

    for (type in NotificationType.entries) {
      assertFalse(type.name, notificationTypeState(type, access).on)
    }
  }

  @Test
  fun `every type posts on at least one channel`() {
    for (type in NotificationType.entries) {
      assertTrue(type.name, type.channelIds.isNotEmpty())
    }
  }

  @Test
  fun `only the smart alarm posts on more than one channel`() {
    assertEquals(
        listOf(NotificationType.WAKE_ALARM),
        NotificationType.entries.filter { it.channelIds.size > 1 },
    )
  }

  @Test
  fun `upload results and analysis results have a channel each`() {
    assertEquals(listOf(Channels.UPLOADS), NotificationType.UPLOADS.channelIds)
    assertEquals(listOf(Channels.ANALYSES), NotificationType.ANALYSES.channelIds)
  }

  @Test
  fun `the screen lists every type in this order`() {
    assertEquals(
        listOf(
            NotificationType.RECORDING,
            NotificationType.WAKE_ALARM,
            NotificationType.UPLOADS,
            NotificationType.ANALYSES,
            NotificationType.TRACKER_BATTERY,
            NotificationType.PHONE_STORAGE,
        ),
        NotificationType.entries,
    )
  }
}
