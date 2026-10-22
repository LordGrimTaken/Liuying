package com.liuying.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sources", indices = [Index(value = ["manifestUrl"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manifestUrl: String,
    val manifestId: String,
    /** Last fetched manifest, serialized with StremioJson. */
    val manifestJson: String,
    val fetchedAt: Long,
    val enabled: Boolean,
    val sortOrder: Int,
    val installedAt: Long,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val nameOverride: String? = null,
)

@Entity(
    tableName = "catalog_prefs",
    primaryKeys = ["sourceId", "type", "catalogId"],
    foreignKeys = [ForeignKey(SourceEntity::class, ["id"], ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class CatalogPrefEntity(
    val sourceId: Long,
    val type: String,
    val catalogId: String,
    val showOnHome: Boolean,
    val showInBrowse: Boolean,
)

@Entity(tableName = "library", primaryKeys = ["type", "id"])
data class LibraryEntity(
    val type: String,
    val id: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val year: String?,
    /** Joined with '|'. */
    val genres: String,
    val addedAt: Long,
)

@Entity(tableName = "progress", primaryKeys = ["type", "titleId", "season", "episode"])
data class ProgressEntity(
    val type: String,
    val titleId: String,
    val season: Int,
    val episode: Int,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedAt: Long,
    val streamHint: String? = null,
)

@Entity(tableName = "dismissed_continue", primaryKeys = ["type", "titleId"])
data class DismissedEntity(val type: String, val titleId: String, val dismissedAt: Long)

@Entity(tableName = "meta_cache", primaryKeys = ["type", "id"])
data class MetaCacheEntity(val type: String, val id: String, val json: String, val fetchedAt: Long)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(@PrimaryKey val text: String, val searchedAt: Long)
