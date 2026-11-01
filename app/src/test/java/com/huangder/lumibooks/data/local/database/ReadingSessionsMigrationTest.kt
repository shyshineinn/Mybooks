package com.huangder.lumibooks.data.local.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.entity.ReadingSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingSessionsMigrationTest {
    @Test fun roomUpgradesV22WithoutReadingSessionsToCurrentSchema() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "reading-session-room-v22.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
                .also { it.openHelper.writableDatabase }
                .close()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { raw ->
                raw.execSQL("DROP TABLE IF EXISTS reading_sessions")
                raw.execSQL("PRAGMA user_version = 22")
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(
                    DatabaseMigrations.MIGRATION_22_23,
                    DatabaseMigrations.MIGRATION_23_24
                )
                .allowMainThreadQueries()
                .build()
            try {
                runBlocking {
                    val id = db.readingSessionDao().insertSession(
                        ReadingSessionEntity(
                            bookId = "book",
                            date = "2026-11-01",
                            startTime = 1_000L,
                            endTime = 31_000L,
                            duration = 30_000L,
                            updatedAt = 31_000L
                        )
                    )
                    assertEquals("", db.readingSessionDao().getSessionById(id)!!.sessionKey)
                }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun roomValidatesV23DatabaseAfterAddingSessionColumns() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "reading-session-room-v23.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
                .also { it.openHelper.writableDatabase }
                .close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { raw ->
                raw.execSQL("DROP TABLE IF EXISTS reading_sessions")
                raw.execSQL(
                    "CREATE TABLE reading_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, " +
                        "date TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER NOT NULL, " +
                        "duration INTEGER NOT NULL, startChapterIndex INTEGER NOT NULL, " +
                        "endChapterIndex INTEGER NOT NULL, startChapterTitle TEXT, endChapterTitle TEXT, " +
                        "startProgress REAL NOT NULL, endProgress REAL NOT NULL, mode TEXT NOT NULL, " +
                        "sourceDeviceId TEXT NOT NULL, note TEXT, mood TEXT, updatedAt INTEGER NOT NULL)"
                )
                raw.execSQL("CREATE INDEX index_reading_sessions_bookId_startTime ON reading_sessions (bookId, startTime)")
                raw.execSQL("CREATE INDEX index_reading_sessions_startTime ON reading_sessions (startTime)")
                raw.execSQL("CREATE INDEX index_reading_sessions_date ON reading_sessions (date)")
                raw.execSQL("PRAGMA user_version = 23")
            }

            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(DatabaseMigrations.MIGRATION_23_24)
                .allowMainThreadQueries()
                .build()
            try {
                runBlocking {
                    val id = db.readingSessionDao().insertSession(
                        ReadingSessionEntity(
                            bookId = "book",
                            date = "2026-11-01",
                            startTime = 1_000L,
                            endTime = 31_000L,
                            duration = 30_000L,
                            updatedAt = 31_000L
                        )
                    )
                    val restored = db.readingSessionDao().getSessionById(id)!!
                    assertEquals("", restored.sessionKey)
                    assertEquals(0L, restored.contributedDuration)
                    assertEquals("{}", restored.contributionsJson)
                }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun migration23To24AddsStableKeyAndContributionColumns() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DatabaseMigrations.MIGRATION_22_23.migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO reading_sessions " +
                    "(bookId,date,startTime,endTime,duration,startChapterIndex,endChapterIndex," +
                    "startProgress,endProgress,mode,sourceDeviceId,updatedAt) " +
                    "VALUES ('book-1','2026-11-01',100,200,30000,0,1,0.1,0.2,'slide','device',200)"
            )
            DatabaseMigrations.MIGRATION_23_24.migrate(db)
            db.query(
                "SELECT sessionKey, contributedDuration, contributionsJson FROM reading_sessions"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("", cursor.getString(0))
                assertEquals(30000L, cursor.getLong(1))
                assertEquals("{}", cursor.getString(2))
            }
            val indices = mutableSetOf<String>()
            db.query("PRAGMA index_list(`reading_sessions`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indices += cursor.getString(nameIndex)
            }
            assertEquals(true, indices.contains("index_reading_sessions_sessionKey"))
        }
    }

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
