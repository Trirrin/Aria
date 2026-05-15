package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NovelEntity::class, ChapterEntity::class, SceneEntity::class],
    version = 2,
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

        fun create(context: Context): XiaoShuoDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                XiaoShuoDatabase::class.java,
                "xiao-shuo.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
