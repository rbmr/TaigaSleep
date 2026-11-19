package com.rbmr.taigasleep.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.provider.Settings
import com.rbmr.taigasleep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the phone's notification settings show after the app has started: one group per purpose,
 * and one channel per notification type, with the ids that keep a user's choice.
 *
 * The app's start is one step, and it is that step this test runs; a unit test has no running
 * process to start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelsTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val manager = context.getSystemService(NotificationManager::class.java)

  @Before
  fun appStart() {
    Channels.create(context)
  }

  @Test
  fun `app start keeps every channel id in its group`() {
    val channels = manager.notificationChannels.associateBy { it.id }

    assertEquals(
        setOf(
            "recording",
            "wake_alarm",
            "wake_alarm_control",
            "uploads",
            "analyses",
            "tracker_battery",
            "phone_storage",
        ),
        channels.keys,
    )
    assertEquals(Channels.GROUP_RECORDING, channels.getValue("recording").group)
    assertEquals(Channels.GROUP_ALARM, channels.getValue("wake_alarm").group)
    assertEquals(Channels.GROUP_ALARM, channels.getValue("wake_alarm_control").group)
    assertEquals(Channels.GROUP_UPLOAD_AND_ANALYSIS, channels.getValue("uploads").group)
    assertEquals(Channels.GROUP_UPLOAD_AND_ANALYSIS, channels.getValue("analyses").group)
    assertEquals(Channels.GROUP_DEVICE_HEALTH, channels.getValue("tracker_battery").group)
    assertEquals(Channels.GROUP_DEVICE_HEALTH, channels.getValue("phone_storage").group)
  }

  @Test
  fun `the channels keep the importance they had`() {
    val channels = manager.notificationChannels.associateBy { it.id }

    assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        channels.getValue("recording").importance,
    )
    assertEquals(
        NotificationManager.IMPORTANCE_HIGH,
        channels.getValue("wake_alarm").importance,
    )
    assertEquals(
        NotificationManager.IMPORTANCE_HIGH,
        channels.getValue("wake_alarm_control").importance,
    )
    assertEquals(NotificationManager.IMPORTANCE_LOW, channels.getValue("uploads").importance)
    assertEquals(NotificationManager.IMPORTANCE_LOW, channels.getValue("analyses").importance)
    assertEquals(
        NotificationManager.IMPORTANCE_DEFAULT,
        channels.getValue("tracker_battery").importance,
    )
    assertEquals(
        NotificationManager.IMPORTANCE_DEFAULT,
        channels.getValue("phone_storage").importance,
    )
  }

  @Test
  fun `the device health channels carry the names of the types they serve`() {
    val channels = manager.notificationChannels.associateBy { it.id }

    assertEquals(
        context.getString(R.string.notification_channel_tracker_battery),
        channels.getValue("tracker_battery").name,
    )
    assertEquals(
        context.getString(R.string.notification_channel_phone_storage),
        channels.getValue("phone_storage").name,
    )
  }

  @Test
  fun `the results channels carry a name each`() {
    val channels = manager.notificationChannels.associateBy { it.id }

    assertEquals(
        context.getString(R.string.upload_notification_channel),
        channels.getValue("uploads").name,
    )
    assertEquals(
        context.getString(R.string.notification_channel_analyses),
        channels.getValue("analyses").name,
    )
  }

  @Test
  fun `app start creates one group per purpose with its name`() {
    val groups = manager.notificationChannelGroups.associateBy { it.id }

    assertEquals(
        setOf(
            Channels.GROUP_RECORDING,
            Channels.GROUP_ALARM,
            Channels.GROUP_UPLOAD_AND_ANALYSIS,
            Channels.GROUP_DEVICE_HEALTH,
        ),
        groups.keys,
    )
    assertEquals(
        context.getString(R.string.notification_group_recording),
        groups.getValue(Channels.GROUP_RECORDING).name,
    )
    assertEquals(
        context.getString(R.string.notification_group_alarm),
        groups.getValue(Channels.GROUP_ALARM).name,
    )
    assertEquals(
        context.getString(R.string.notification_group_uploads_analyses),
        groups.getValue(Channels.GROUP_UPLOAD_AND_ANALYSIS).name,
    )
    assertEquals(
        context.getString(R.string.notification_group_device_health),
        groups.getValue(Channels.GROUP_DEVICE_HEALTH).name,
    )
  }

  @Test
  fun `the alarm channel rings on the alarm sound and vibrates`() {
    val alarm = manager.getNotificationChannel(Channels.WAKE_ALARM)

    assertEquals(
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        alarm.sound,
    )
    assertEquals(
        AudioAttributes.USAGE_ALARM,
        alarm.audioAttributes!!.usage,
    )
    assertTrue("the alarm vibrates", alarm.shouldVibrate())
    assertEquals(
        context.getString(R.string.wake_channel_alarm),
        alarm.name,
    )
  }

  @Test
  fun `the alarm control channel is visible but silent`() {
    val control = manager.getNotificationChannel(Channels.WAKE_ALARM_CONTROL)

    assertEquals(
        context.getString(R.string.wake_channel_alarm_control),
        control.name,
    )
    assertNull("the ringing service owns the sound", control.sound)
    assertTrue("the ringing service owns the vibration", control.shouldVibrate().not())
  }

  @Test
  fun `the recording and results channels keep the default sound and no vibration`() {
    val channels: List<NotificationChannel> = manager.notificationChannels
    val recording = channels.single { it.id == Channels.RECORDING }
    val uploads = channels.single { it.id == Channels.UPLOADS }
    val analyses = channels.single { it.id == Channels.ANALYSES }

    // A channel that names no sound has the phone's notification sound, which is what these had.
    assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, recording.sound)
    assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, uploads.sound)
    assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, analyses.sound)
    assertTrue("the recording channel does not vibrate", recording.shouldVibrate().not())
    assertTrue("the upload channel does not vibrate", uploads.shouldVibrate().not())
    assertTrue("the analysis channel does not vibrate", analyses.shouldVibrate().not())
  }
}
