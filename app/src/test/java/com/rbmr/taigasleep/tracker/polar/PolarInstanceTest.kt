package com.rbmr.taigasleep.tracker.polar

import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The one SDK instance is made for the feature set of the driver that asks for it: another set
 * replaces it, the same set keeps it, and a replacement is made for the same set.
 */
class PolarInstanceTest {
  private val created = ArrayList<Set<PolarBleSdkFeature>>()
  private val shutDown = ArrayList<Any>()
  private val instance =
      PolarInstance(
          create = { features ->
            Any().also { created += features }
          },
          shutDown = { shutDown += it },
      )

  @Test
  fun `switching from the h10 to the verity replaces the instance with the verity set`() {
    val h10 = instance.forFeatures(H10_FEATURES)

    val verity = instance.forFeatures(VERITY_FEATURES)

    assertNotSame(h10, verity)
    assertEquals(listOf(H10_FEATURES, VERITY_FEATURES), created)
    assertSame(h10, shutDown.single())
  }

  @Test
  fun `asking twice for the same set keeps the one instance`() {
    val first = instance.forFeatures(H10_FEATURES)

    val second = instance.forFeatures(H10_FEATURES)

    assertSame(first, second)
    assertEquals(listOf(H10_FEATURES), created)
    assertEquals(emptyList<Any>(), shutDown)
  }

  @Test
  fun `recreate makes a new instance for the set the old one had`() {
    val before = instance.forFeatures(H10_FEATURES)

    instance.recreate()

    val after = instance.forFeatures(H10_FEATURES)
    assertNotSame(before, after)
    assertEquals(listOf(H10_FEATURES, H10_FEATURES), created)
    assertSame(before, shutDown.single())
  }

  @Test
  fun `scanning with no live instance makes one for the scan set`() {
    instance.live(SCAN_FEATURES)

    assertEquals(listOf(SCAN_FEATURES), created)
  }

  @Test
  fun `scanning keeps the instance the driver asked for`() {
    val driver = instance.forFeatures(H10_FEATURES)

    val scanned = instance.live(SCAN_FEATURES)

    assertSame(driver, scanned)
    assertEquals(listOf(H10_FEATURES), created)
  }
}
