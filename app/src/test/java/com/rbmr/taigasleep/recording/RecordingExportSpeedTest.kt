package com.rbmr.taigasleep.recording

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures what an export of a large night costs on the JVM, so the choice of stored stream logs
 * over deflated ones is backed by numbers instead of an assumption. The output is printed; the
 * assertions only check the archive the fast path writes.
 *
 * It writes about 160 MB per run, so it is opt-in and skipped by the normal suite. Run it with:
 *
 * ```sh
 * ./gradlew :app:testDebugUnitTest --tests '*RecordingExportSpeedTest' -Dtaiga.benchmark=1
 * ```
 *
 * The result is printed in `app/build/test-results/testDebugUnitTest/`.
 */
class RecordingExportSpeedTest {
  private val root = Files.createTempDirectory("taiga-export-speed").toFile()
  private val dir = File(root, "night").apply { mkdirs() }

  @After
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `a large night is written and the two strategies are measured`() {
    assumeTrue(System.getProperty("taiga.benchmark") != null)

    // About 80 MB of sensor-like data: mostly incompressible, as ECG and ACC samples are.
    writeNoise(File(dir, "acc.log"), 60L * 1024 * 1024)
    writeNoise(File(dir, "ecg.log"), 18L * 1024 * 1024)
    writeNoise(File(dir, "hr.log"), 2L * 1024 * 1024)
    File(dir, "meta.json").writeText("""{"version":1,"sensor":"Polar H10"}""")
    File(dir, "events.jsonl").writeText("""{"event":"start"}""" + "\n")

    // Warm up both paths so the first measurement is not the JIT's.
    zipTheOldWay(File(root, "warmup.zip"))
    RecordingExport.write("night", dir, File(root, "warmup-fast.zip"), ExportContents())
    File(root, "warmup.zip").delete()
    File(root, "warmup-fast.zip").delete()

    val deflated = bestOf { zipTheOldWay(File(root, "deflated.zip")) }
    val stored =
        bestOf {
          File(root, "stored.zip").also {
            RecordingExport.write("night", dir, it, ExportContents())
          }
        }

    val megabytes = files().sumOf { it.length() }.toDouble() / (1024 * 1024)
    println(
        "export of %.1f MB: deflated %.0f ms (%d bytes), stored %.0f ms (%d bytes), %.1fx faster"
            .format(
                megabytes,
                deflated.first / 1_000_000.0,
                deflated.second.length(),
                stored.first / 1_000_000.0,
                stored.second.length(),
                deflated.first.toDouble() / stored.first.toDouble().coerceAtLeast(1.0),
            )
    )

    assertTrue("the stored archive is larger than its contents", stored.second.length() > 0)
    ZipFile(stored.second).use { zip ->
      assertEquals(ZipEntry.STORED, zip.getEntry("night/acc.log").method)
      assertEquals(60L * 1024 * 1024, zip.getEntry("night/acc.log").size)
      assertEquals(5, zip.entries().toList().size)
    }
  }

  /** The previous export wrote every entry through the deflater. */
  private fun zipTheOldWay(out: File): File {
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
      for (file in files()) {
        zip.putNextEntry(ZipEntry("night/${file.name}"))
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            zip.write(buffer, 0, read)
          }
        }
        zip.closeEntry()
      }
    }
    return out
  }

  private fun bestOf(block: () -> File): Pair<Long, File> =
      generateSequence {
            val started = System.nanoTime()
            val file = block()
            (System.nanoTime() - started) to file
          }
          .take(2)
          .minBy { it.first }

  private fun files(): List<File> =
      dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name }

  private fun writeNoise(file: File, bytes: Long) {
    val random = java.util.Random(42)
    val buffer = ByteArray(1024 * 1024)
    file.outputStream().buffered().use { out ->
      var left = bytes
      while (left > 0) {
        random.nextBytes(buffer)
        val count = minOf(buffer.size.toLong(), left).toInt()
        out.write(buffer, 0, count)
        left -= count
      }
    }
  }
}
