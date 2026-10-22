package com.liuying.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/** Adds scraper plugin repositories and their plugins. Purely additive. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `plugin_repos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `manifestUrl` TEXT NOT NULL, `repoId` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `description` TEXT NOT NULL, `author` TEXT, `fetchedAt` INTEGER NOT NULL, `consentedAt` INTEGER, `lastError` TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_plugin_repos_manifestUrl` ON `plugin_repos` (`manifestUrl`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `plugins` (`repoId` INTEGER NOT NULL, `pluginId` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `description` TEXT NOT NULL, `scriptUrl` TEXT NOT NULL, `types` TEXT NOT NULL, `permissions` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`repoId`, `pluginId`), FOREIGN KEY(`repoId`) REFERENCES `plugin_repos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    }
}

@Database(
    entities = [
        SourceEntity::class, CatalogPrefEntity::class, LibraryEntity::class, ProgressEntity::class,
        DismissedEntity::class, MetaCacheEntity::class, RecentSearchEntity::class,
        PluginRepoEntity::class, PluginEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LiuyingDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun libraryDao(): LibraryDao
    abstract fun progressDao(): ProgressDao
    abstract fun metaCacheDao(): MetaCacheDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun pluginDao(): PluginDao

    companion object {
        const val NAME = "liuying.db"

        /** Add future migrations here; never fall back to destructive migration (it would erase progress). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): LiuyingDatabase =
            Room.databaseBuilder(context.applicationContext, LiuyingDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
