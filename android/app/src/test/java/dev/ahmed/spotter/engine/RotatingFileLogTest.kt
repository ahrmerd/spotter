package dev.ahmed.spotter.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingFileLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun redactsSecretsAndRotates() {
        val dir = tmp.newFolder("logs")
        val log = RotatingFileLog(dir, maxBytes = 80, generations = 3)
        log.info("hello Cookie: sid=SECRETCOOKIE")
        log.warn("token=SECRETTOKEN on https://x.test/a?token=SECRETTOKEN")
        val early = log.readAll()
        assertFalse(early.contains("SECRETCOOKIE"))
        assertFalse(early.contains("SECRETTOKEN"))
        assertTrue(early.contains("hello"))
        repeat(30) { i -> log.info("line $i padding padding padding padding") }
        val all = log.readAll()
        assertFalse(all.contains("SECRETCOOKIE"))
        assertFalse(all.contains("SECRETTOKEN"))
        val files = dir.list()?.sorted().orEmpty()
        assertTrue("rotated files=$files", files.size >= 2)
    }
}
