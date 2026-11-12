package com.rbmr.taigasleep.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrapLinkTest {
  private fun device(address: String, name: String?) = ListedDevice(address, name)

  @Test
  fun theAddressTheSensorKnowsWinsOverWhatThePhoneLists() {
    assertEquals(
        "AA:BB:CC:DD:EE:FF",
        strapDeviceAddress(
            knownAddress = "AA:BB:CC:DD:EE:FF",
            sensorId = "069A4737",
            connected = listOf(device("11:22:33:44:55:66", "Polar H10 069A4737")),
            bonded = listOf(device("77:88:99:AA:BB:CC", "Polar H10 069A4737")),
        ),
    )
  }

  @Test
  fun withoutAKnownAddressAConnectedDeviceIsFoundByItsName() {
    assertEquals(
        "11:22:33:44:55:66",
        strapDeviceAddress(
            knownAddress = null,
            sensorId = "069A4737",
            connected = listOf(
                device("00:00:00:00:00:01", "Phone"),
                device("11:22:33:44:55:66", "Polar H10 069A4737"),
            ),
            bonded = emptyList(),
        ),
    )
  }

  @Test
  fun aBondedDeviceIsSearchedWhenTheStrapIsNotConnected() {
    assertEquals(
        "77:88:99:AA:BB:CC",
        strapDeviceAddress(
            knownAddress = null,
            sensorId = "069A4737",
            connected = emptyList(),
            bonded = listOf(device("77:88:99:AA:BB:CC", "Polar H10 069A4737")),
        ),
    )
  }

  @Test
  fun aConnectedDeviceIsPreferredOverABondedOne() {
    assertEquals(
        "11:22:33:44:55:66",
        strapDeviceAddress(
            knownAddress = null,
            sensorId = "069A4737",
            connected = listOf(device("11:22:33:44:55:66", "Polar H10 069A4737")),
            bonded = listOf(device("77:88:99:AA:BB:CC", "Polar H10 069A4737")),
        ),
    )
  }

  @Test
  fun theNameMustEndWithTheSensorIdAndTheCaseDoesNotMatter() {
    assertEquals(
        "11:22:33:44:55:66",
        strapDeviceAddress(
            knownAddress = null,
            sensorId = "069A4737",
            connected = listOf(device("11:22:33:44:55:66", "polar h10 069a4737")),
            bonded = emptyList(),
        ),
    )
    assertNull(
        strapDeviceAddress(
            knownAddress = "",
            sensorId = "069A4737",
            connected = listOf(device("11:22:33:44:55:66", "Polar H10 069A47370")),
            bonded = listOf(device("77:88:99:AA:BB:CC", "Polar H10 12345678")),
        ),
    )
  }

  @Test
  fun noMatchingNameMeansNoAddress() {
    assertNull(
        strapDeviceAddress(
            knownAddress = null,
            sensorId = "069A4737",
            connected = listOf(device("00:00:00:00:00:01", "Phone")),
            bonded = listOf(device("77:88:99:AA:BB:CC", "Headphones")),
        ),
    )
  }
}
