package com.rbmr.taigasleep.notifications

/**
 * Android's answer, set by a test instead of the phone: the app's notifications as one switch and
 * the set of channels the user blocked, or its group with them.
 */
internal class FakeNotificationAccess(
    var appAllowed: Boolean = true,
    private val blocked: MutableSet<String> = mutableSetOf(),
) : NotificationAccess {
  /** Blocks [channelIds] as the phone's settings would. */
  fun block(vararg channelIds: String) {
    blocked += channelIds
  }

  /** Allows [channelId] again. */
  fun allow(channelId: String) {
    blocked -= channelId
  }

  override fun appAllowed(): Boolean = appAllowed

  override fun channelAllowed(channelId: String): Boolean = channelId !in blocked
}
