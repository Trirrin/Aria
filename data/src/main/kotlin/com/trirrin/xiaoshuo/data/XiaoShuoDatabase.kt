package com.trirrin.xiaoshuo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NovelEntity::class, ChapterEntity::class, SceneEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class XiaoShuoDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao

    companion object {
        fun create(context: Context): XiaoShuoDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                XiaoShuoDatabase::class.java,
                "xiao-shuo.db",
            ).build()
        }
    }
}
