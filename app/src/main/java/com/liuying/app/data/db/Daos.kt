package com.liuying.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE manifestUrl = :url LIMIT 1")
    suspend fun getByUrl(url: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE manifestId = :manifestId LIMIT 1")
    suspend fun getByManifestId(manifestId: String): SourceEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sources")
    suspend fun maxOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SourceEntity): Long

    @Update
    suspend fun update(entity: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE sources SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("UPDATE sources SET lastError = :error, lastErrorAt = :at WHERE id = :id")
    suspend fun setError(id: Long, error: String?, at: Long?)

    @Query("UPDATE sources SET nameOverride = :name WHERE id = :id")
    suspend fun setNameOverride(id: Long, name: String?)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sources")
    suspend fun deleteAll()

    // catalog prefs
    @Query("SELECT * FROM catalog_prefs")
    fun observePrefs(): Flow<List<CatalogPrefEntity>>

    @Query("SELECT * FROM catalog_prefs WHERE sourceId = :sourceId")
    suspend fun getPrefs(sourceId: Long): List<CatalogPrefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPref(pref: CatalogPrefEntity)

    @Query("DELETE FROM catalog_prefs WHERE sourceId = :sourceId AND type = :type AND catalogId = :catalogId")
    suspend fun deletePref(sourceId: Long, type: String, catalogId: String)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM library WHERE type = :type AND id = :id)")
    fun observeContains(type: String, id: String): Flow<Boolean>

    @Query("SELECT * FROM library WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LibraryEntity)

    @Query("DELETE FROM library WHERE type = :type AND id = :id")
    suspend fun delete(type: String, id: String)

    @Query("DELETE FROM library WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM library")
    suspend fun getAll(): List<LibraryEntity>

    @Query("DELETE FROM library")
    suspend fun deleteAll()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE type = :type AND titleId = :titleId")
    fun observeForTitle(type: String, titleId: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE type = :type AND titleId = :titleId AND season = :season AND episode = :episode")
    suspend fun get(type: String, titleId: String, season: Int, episode: Int): ProgressEntity?

    @Query("SELECT * FROM progress WHERE type = :type AND titleId = :titleId")
    suspend fun getForTitle(type: String, titleId: String): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE type = :type AND titleId = :titleId AND season = :season AND episode = :episode")
    suspend fun delete(type: String, titleId: String, season: Int, episode: Int)

    @Query("DELETE FROM progress WHERE type = :type AND titleId = :titleId")
    suspend fun deleteTitle(type: String, titleId: String)

    @Query("DELETE FROM progress")
    suspend fun deleteAll()

    @Query("SELECT * FROM dismissed_continue")
    fun observeDismissed(): Flow<List<DismissedEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDismissed(entity: DismissedEntity)

    @Query("DELETE FROM dismissed_continue")
    suspend fun deleteAllDismissed()
}

@Dao
interface MetaCacheDao {
    @Query("SELECT * FROM meta_cache WHERE type = :type AND id = :id")
    suspend fun get(type: String, id: String): MetaCacheEntity?

    @Query("SELECT * FROM meta_cache")
    suspend fun getAll(): List<MetaCacheEntity>

    /** Emits whenever the cache changes; the badge logic needs to know when a cached episode list arrives. */
    @Query("SELECT count(*) FROM meta_cache")
    fun observeCount(): Flow<Int>

    /** Drops everything older than the [keep] newest rows (no row-value syntax: API 24 ships SQLite 3.9). */
    @Query("DELETE FROM meta_cache WHERE fetchedAt < (SELECT fetchedAt FROM meta_cache ORDER BY fetchedAt DESC LIMIT 1 OFFSET :keep)")
    suspend fun prune(keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetaCacheEntity)

    @Query("DELETE FROM meta_cache")
    suspend fun deleteAll()
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE text = :text")
    suspend fun delete(text: String)

    @Query("DELETE FROM recent_searches")
    suspend fun deleteAll()

    @Query("DELETE FROM recent_searches WHERE text NOT IN (SELECT text FROM recent_searches ORDER BY searchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugin_repos ORDER BY id ASC")
    fun observeRepos(): Flow<List<PluginRepoEntity>>

    @Query("SELECT * FROM plugins ORDER BY name COLLATE NOCASE ASC")
    fun observePlugins(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugin_repos WHERE id = :id")
    suspend fun getRepo(id: Long): PluginRepoEntity?

    @Query("SELECT * FROM plugin_repos WHERE manifestUrl = :url LIMIT 1")
    suspend fun getRepoByUrl(url: String): PluginRepoEntity?

    @Query("SELECT * FROM plugins WHERE repoId = :repoId")
    suspend fun getPlugins(repoId: Long): List<PluginEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRepo(e: PluginRepoEntity): Long

    @Update
    suspend fun updateRepo(e: PluginRepoEntity)

    @Query("UPDATE plugin_repos SET lastError = :error WHERE id = :id")
    suspend fun setRepoError(id: Long, error: String?)

    @Query("UPDATE plugin_repos SET consentedAt = :at WHERE id = :id")
    suspend fun setConsent(id: Long, at: Long?)

    @Query("DELETE FROM plugin_repos WHERE id = :id")
    suspend fun deleteRepo(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlugin(e: PluginEntity)

    @Query("DELETE FROM plugins WHERE repoId = :repoId AND pluginId = :pluginId")
    suspend fun deletePlugin(repoId: Long, pluginId: String)

    @Query("UPDATE plugins SET enabled = :enabled WHERE repoId = :repoId AND pluginId = :pluginId")
    suspend fun setPluginEnabled(repoId: Long, pluginId: String, enabled: Boolean)

    @Query("UPDATE plugins SET enabled = 0 WHERE repoId = :repoId")
    suspend fun disableAll(repoId: Long)
}
