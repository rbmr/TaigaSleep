package com.rbmr.taigasleep.recording.store

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionZipTest {
  @Test
  fun zippingReportsProgressForEveryChunkAndEndsAtOne() {
    val root = Files.createTempDirectory("taiga-zip").toFile()
    val dir = File(root, "night").apply { mkdirs() }
    File(dir, "hr.log").writeBytes(ByteArray(200_000) { it.toByte() })
    File(dir, "meta.json").writeText("""{"version":1}""")
    val out = File(root, "night.zip")
    val progress = mutableListOf<Float>()

    SessionStore(root).zip("night", out) { progress += it }

    assertTrue(progress.isNotEmpty())
    assertTrue(progress.all { it in 0f..1f })
    assertEquals(1f, progress.last(), 0.0001f)
    val entries = mutableListOf<String>()
    ZipInputStream(out.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries += entry.name
      }
    }
    assertEquals(listOf("night/hr.log", "night/meta.json"), entries)
    root.deleteRecursively()
  }
}
