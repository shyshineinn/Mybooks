package com.huangder.lumibooks.data.local.database

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingSessionsMigrationTest {
    @Test fun createsSessionTableWithDetailColumnsAndIndices() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            DatabaseMigrations.MIGRATION_22_23.migrate(db)
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(bookId,date,startTime,endTime,duration,startChapterIndex,endChapterIndex," +
                    "startChapterTitle,endChapterTitle,startProgress,endProgress,mode,sourceDeviceId,updatedAt) " +
                    "VALUES ('book-1','2026-11-01',100,200,100,2,3,'第三章','第四章',0.3,0.36,'','device',200)"
            )
            db.query(
                "SELECT duration,startChapterIndex,endChapterIndex,startProgress,endProgress,mode,note " +
                    "FROM reading_sessions WHERE bookId='book-1'"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(100L, cursor.getLong(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals(3, cursor.getInt(2))
                assertEquals(0.3f, cursor.getFloat(3), 0.0001f)
                assertEquals(0.36f, cursor.getFloat(4), 0.0001f)
                assertEquals("", cursor.getString(5))
                assertEquals(true, cursor.isNull(6))
            }
            val indices = mutableSetOf<String>()
            db.query("PRAGMA index_list(`reading_sessions`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indices += cursor.getString(nameIndex)
            }
            assertEquals(
                setOf(
                    "index_reading_sessions_bookId_startTime",
                    "index_reading_sessions_startTime",
                    "index_reading_sessions_date"
                ),
                indices.filterNot { it.startsWith("sqlite_autoindex_") }.toSet()
            )
        }
    }
}
