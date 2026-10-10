package dev.ahmed.spotter.engine.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration1to2Test {
    private val dbName = "migration-1-2-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SpotterDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesAndCreatesBrowserTables() {
        // Version 1 database with real rows: they must survive the migration unchanged.
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO downloads (id,url,headersJson,filename,outputPath,state,size,downloaded,etag,lastModified,ranged,mimeType,error,createdAt) " +
                    "VALUES (7,'http://example.com/a.bin','{}','a.bin','/x/a.bin','PAUSED',1000,400,'\"e\"',NULL,1,'application/octet-stream',NULL,123)",
            )
            execSQL("INSERT INTO parts (id,downloadId,startByte,currentByte,endByte) VALUES (1,7,0,400,999)")
            execSQL("INSERT INTO settings (`key`,value) VALUES ('wifi_only','true')")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 2, true, SpotterDatabase.MIGRATION_1_2)
        migrated.query("SELECT url,state,downloaded,etag FROM downloads WHERE id=7").use { c ->
            assertTrue("download row must survive", c.moveToFirst())
            assertEquals("http://example.com/a.bin", c.getString(0))
            assertEquals("PAUSED", c.getString(1))
            assertEquals(400L, c.getLong(2))
            assertEquals("\"e\"", c.getString(3))
        }
        migrated.query("SELECT currentByte,endByte FROM parts WHERE downloadId=7").use { c ->
            assertTrue("part row must survive", c.moveToFirst())
            assertEquals(400L, c.getLong(0))
            assertEquals(999L, c.getLong(1))
        }
        migrated.query("SELECT value FROM settings WHERE `key`='wifi_only'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("true", c.getString(0))
        }
        migrated.close()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, SpotterDatabase::class.java, dbName)
            .addMigrations(SpotterDatabase.MIGRATION_1_2)
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                val names = mutableSetOf<String>()
                while (c.moveToNext()) names.add(c.getString(0))
                assertTrue(names.contains("browser_tabs"))
                assertTrue(names.contains("bookmarks"))
                assertTrue(names.contains("history"))
                assertTrue(names.contains("sniffer_cache"))
            }
        } finally {
            db.close()
        }
    }
}
