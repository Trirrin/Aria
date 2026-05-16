package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        SceneEntity::class,
        RevisionSnapshotEntity::class,
        TokenUsageRecordEntity::class,
        ConversationSessionEntity::class,
        PendingApprovalEntity::class,
        ToolCallAuditEntity::class,
    ],
    version = 4,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        novelId TEXT,
                        messagesJson TEXT NOT NULL,
                        activeToolCallJson TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_sessions_novelId ON conversation_sessions(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_sessions_updatedAtEpochMillis ON conversation_sessions(updatedAtEpochMillis)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_approvals (
                        id TEXT NOT NULL PRIMARY KEY,
                        novelId TEXT,
                        targetType TEXT NOT NULL,
                        targetId TEXT,
                        actionName TEXT NOT NULL,
                        previewTitle TEXT NOT NULL,
                        previewText TEXT NOT NULL,
                        proposedPayloadJson TEXT NOT NULL,
                        riskLevel TEXT NOT NULL,
                        requiredBeforeCommit INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_novelId ON pending_approvals(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_targetId ON pending_approvals(targetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_approvals_createdAtEpochMillis ON pending_approvals(createdAtEpochMillis)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tool_call_audits (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        novelId TEXT,
                        functionName TEXT NOT NULL,
                        argumentSummary TEXT NOT NULL,
                        resultStatus TEXT NOT NULL,
                        resultMessage TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_audits_sessionId ON tool_call_audits(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_audits_novelId ON tool_call_audits(novelId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_audits_functionName ON tool_call_audits(functionName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_call_audits_createdAtEpochMillis ON tool_call_audits(createdAtEpochMillis)")
            }
        }

        fun create(context: Context): XiaoShuoDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                XiaoShuoDatabase::class.java,
                "xiao-shuo.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
