package com.rbmr.taigasleep.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadConditionsTest {
  private val strict = AutoUploadSettings(enabled = true, wifiOnly = true, chargingOnly = true)

  @Test
  fun automaticUploadsFollowTheWifiAndChargingChoices() {
    val conditions = UploadConditions.automatic(strict)
    assertEquals(NetworkType.UNMETERED, conditions.network)
    assertTrue(conditions.requiresCharging)
    assertFalse(conditions.expedited)
  }

  @Test
  fun automaticUploadsNeedNoNetworkWhenWifiOnlyIsOff() {
    val conditions = UploadConditions.automatic(strict.copy(wifiOnly = false, chargingOnly = false))
    assertEquals(NetworkType.NOT_REQUIRED, conditions.network)
    assertFalse(conditions.requiresCharging)
    assertEquals(NetworkType.NOT_REQUIRED, conditions.toConstraints().requiredNetworkType)
  }

  @Test
  fun aManualUploadIgnoresTheAutomaticConditionsAndStartsAtOnce() {
    val conditions = UploadConditions.manual()
    assertEquals(NetworkType.NOT_REQUIRED, conditions.network)
    assertFalse(conditions.requiresCharging)
    assertTrue(conditions.expedited)
    assertEquals(NetworkType.NOT_REQUIRED, conditions.toConstraints().requiredNetworkType)
  }
}
