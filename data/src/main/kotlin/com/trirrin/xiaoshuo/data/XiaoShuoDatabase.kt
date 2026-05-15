package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NovelEntity::class, ChapterEntity::class, SceneEntity::class, RevisionSnapshotEntity::class, TokenUsageRecordEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class XiaoShuoDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN reviewReportJson TEXT")
                db.execSQL("ALTER TABLE scenes ADD COLUMN reviewReportJson TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scenes ADD COLUMN textSource TEXT NOT NULL DEFAULT 'EMPTY'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS revision_snapshots (
                        id TEXT NOT NULL PRIMARY KEY,
                        novelId TEXT NOT NULL,
                        targetType TEXT NOT NULL,
                        targetId TEXT NOT NULL,
                        label TEXT NOT NULL,
                        contentJson TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(novelId) REFERENCES novels(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_revision_snapshots_novelId ON revision_snapshots(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_revision_snapshots_targetId ON revision_snapshots(targetId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS token_usage_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        novelId TEXT NOT NULL,
                        agentName TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        model TEXT NOT NULL,
                        inputTokens INTEGER NOT NULL,
                        outputTokens INTEGER NOT NULL,
                        estimatedCostUsd REAL NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        FOREIGN KEY(novelId) REFERENCES novels(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_token_usage_records_novelId ON token_usage_records(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_token_usage_records_agentName ON token_usage_records(agentName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_token_usage_records_model ON token_usage_records(model)")
            }
        }

        fun create(context: Context): XiaoShuoDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                XiaoShuoDatabase::class.java,
                "xiao-shuo.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
