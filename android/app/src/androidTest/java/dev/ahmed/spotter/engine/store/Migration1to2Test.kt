package dev.ahmed.spotter.engine.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, SpotterDatabase.MIGRATION_1_2)
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
