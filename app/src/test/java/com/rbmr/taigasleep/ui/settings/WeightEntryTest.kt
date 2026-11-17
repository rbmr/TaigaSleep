package com.rbmr.taigasleep.ui.settings

import android.content.Context
import com.rbmr.taigasleep.insights.Units
import com.rbmr.taigasleep.profile.PersonalDataStore
import com.rbmr.taigasleep.testFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A weight is entered and shown in the unit the user chose and kept in kilograms. A value reads
 * the same when it is shown again after a save, whichever unit it was typed in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeightEntryTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun `a weight saved in pounds reads the same after a reload`() {
    val formats = testFormats(units = Units.IMPERIAL)
    val store = PersonalDataStore(context)

    store.setWeightKg(formats.parseWeight("154.3")!!)

    val reloaded = PersonalDataStore(context).data.value.weightKg!!
    assertEquals("154.3", formats.weightNumber(reloaded))
    assertEquals("154.3 lb", formats.weight(reloaded))
  }

  @Test
  fun `a weight saved in kilograms reads the same after a reload`() {
    val formats = testFormats(units = Units.METRIC)
    val store = PersonalDataStore(context)

    store.setWeightKg(formats.parseWeight("70.5")!!)

    val reloaded = PersonalDataStore(context).data.value.weightKg!!
    assertEquals(70.5, reloaded, 1e-9)
    assertEquals("70.5 kg", formats.weight(reloaded))
  }

  @Test
  fun `the stored weight is the one shown in pounds`() {
    val formats = testFormats(units = Units.IMPERIAL)
    val prefilled = formats.weightNumber(70.0)

    assertEquals("154.3", prefilled)
    assertEquals(70.0, weightToSave(70.0, prefilled, prefilled, formats)!!, 1e-9)
  }

  @Test
  fun `a weight typed in pounds is kept in kilograms`() {
    val formats = testFormats(units = Units.IMPERIAL)
    val saved = weightToSave(null, "", "160", formats)!!

    assertEquals("160 lb", formats.weight(saved))
  }

  @Test
  fun `a weight typed in kilograms is kept as it was typed`() {
    val formats = testFormats(units = Units.METRIC)

    assertEquals(70.5, weightToSave(null, "", "70,5", formats)!!, 1e-9)
  }

  @Test
  fun `a weight outside the range is refused`() {
    val formats = testFormats(units = Units.METRIC)

    assertNull(weightToSave(null, "", "0", formats))
    assertNull(weightToSave(null, "", "", formats))
    assertNull(weightToSave(null, "", "heavy", formats))
  }
}
