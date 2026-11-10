package com.rbmr.taigasleep.profile

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalDataStoreTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun `every value survives a new store and can be cleared`() {
    val store = PersonalDataStore(context)
    store.setDateOfBirth(LocalDate.of(1990, 6, 15))
    store.setSex(Sex.FEMALE)
    store.setWeightKg(72.5)

    val reloaded = PersonalDataStore(context)
    assertEquals(LocalDate.of(1990, 6, 15), reloaded.data.value.dateOfBirth)
    assertEquals(Sex.FEMALE, reloaded.data.value.sex)
    assertEquals(72.5, reloaded.data.value.weightKg ?: 0.0, 0.0)

    reloaded.setDateOfBirth(null)
    reloaded.setSex(null)
    reloaded.setWeightKg(null)

    val cleared = PersonalDataStore(context).data.value
    assertNull(cleared.dateOfBirth)
    assertNull(cleared.sex)
    assertNull(cleared.weightKg)
  }

  @Test
  fun `a value written by an older or broken version is ignored`() {
    context.getSharedPreferences("personal_data", Context.MODE_PRIVATE)
        .edit {
          putString("date_of_birth", "not-a-date")
          putString("sex", "other")
          putString("weight_kg", "heavy")
        }

    val data = PersonalDataStore(context).data.value

    assertNull(data.dateOfBirth)
    assertNull(data.sex)
    assertNull(data.weightKg)
  }
}
