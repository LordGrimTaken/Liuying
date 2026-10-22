package com.liuying.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        SourceEntity::class, CatalogPrefEntity::class, LibraryEntity::class, ProgressEntity::class,
        DismissedEntity::class, MetaCacheEntity::class, RecentSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LiuyingDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun libraryDao(): LibraryDao
    abstract fun progressDao(): ProgressDao
    abstract fun metaCacheDao(): MetaCacheDao
    abstract fun recentSearchDao(): RecentSearchDao

    companion object {
        const val NAME = "liuying.db"

        /** Add future migrations here; never fall back to destructive migration (it would erase progress). */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): LiuyingDatabase =
            Room.databaseBuilder(context.applicationContext, LiuyingDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
