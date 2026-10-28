package com.huangder.lumibooks.data.backup

import com.huangder.lumibooks.domain.model.SyncContentSelection
import java.security.MessageDigest

/** Shared data selection and merge rules. Network protocols and credentials are not part of these rules. */
object PortableSyncRules {
    fun filterBundle(bundle: PortableSnapshotBundle, selection: SyncContentSelection): PortableSnapshotBundle {
        val assets = bundle.snapshot.assets.filter { it.kind != "book" }
        val ids = assets.mapTo(mutableSetOf()) { it.id }
        return bundle.copy(snapshot = stripRuleLibrary(bundle.snapshot.copy(books = bundle.snapshot.books.map { it.copy(bodyAssetId = null) }, assets = assets)),
            assetSources = bundle.assetSources.filterKeys { it in ids })
    }
    fun forRemote(local: PortableSnapshot, remote: PortableSnapshot, merged: PortableSnapshot, selection: SyncContentSelection): PortableSnapshot {
        val readingNamespaces = setOf("bookmark", "note")
        val libraryNamespaces = setOf("book", "folder", "tag", "book_folder", "book_tag")
        val selected = merged.copy(
            preferences = if (selection.profileAndSettings) merged.preferences else remote.preferences,
            books = chooseBooks(local.books, remote.books, merged.books, selection, false),
            folders = if (selection.libraryOrganization) merged.folders else remote.folders,
            bookFolderLinks = if (selection.libraryOrganization) merged.bookFolderLinks else remote.bookFolderLinks,
            tags = if (selection.libraryOrganization) merged.tags else remote.tags,
            annotationTags = if (selection.readingData) merged.annotationTags else remote.annotationTags,
            bookTagLinks = if (selection.libraryOrganization) merged.bookTagLinks else remote.bookTagLinks,
            readingRecords = if (selection.readingData) merged.readingRecords else remote.readingRecords,
            bookmarks = if (selection.readingData) merged.bookmarks else remote.bookmarks,
            notes = if (selection.readingData) merged.notes else remote.notes,
            tombstones = merged.tombstones.filter { when (it.namespace) {
                in readingNamespaces -> selection.readingData
                in libraryNamespaces -> selection.libraryOrganization
                else -> true
            } } + remote.tombstones.filter { (it.namespace in readingNamespaces && !selection.readingData) ||
                (it.namespace in libraryNamespaces && !selection.libraryOrganization) },
            assets = (remote.assets + local.assets).associateBy { it.id }.values.toList()
        )
        return stripRuleLibrary(selected.copy(assets = selected.assets.filter { referenced(selected, it.id) }))
    }
    fun forLocal(local: PortableSnapshot, remote: PortableSnapshot, selection: SyncContentSelection): PortableSnapshot = remote.copy(
        preferences = if (selection.profileAndSettings) remote.preferences else local.preferences,
        books = chooseBooks(local.books, remote.books, remote.books, selection, true),
        folders = if (selection.libraryOrganization) remote.folders else local.folders,
        bookFolderLinks = if (selection.libraryOrganization) remote.bookFolderLinks else local.bookFolderLinks,
        tags = if (selection.libraryOrganization) remote.tags else local.tags,
        annotationTags = if (selection.readingData) remote.annotationTags else local.annotationTags,
        bookTagLinks = if (selection.libraryOrganization) remote.bookTagLinks else local.bookTagLinks,
        readingRecords = if (selection.readingData) remote.readingRecords else local.readingRecords,
        bookmarks = if (selection.readingData) remote.bookmarks else local.bookmarks,
        notes = if (selection.readingData) remote.notes else local.notes,
        tombstones = (local.tombstones + remote.tombstones).associateBy { it.namespace + ":" + it.itemId }.values.toList(),
        assets = (local.assets + remote.assets).associateBy { it.id }.values.toList()
    )
    fun chooseBooks(local: List<PortableBook>, remote: List<PortableBook>, merged: List<PortableBook>, selection: SyncContentSelection, forLocal: Boolean): List<PortableBook> {
        val localMap = local.associateBy { it.id }; val remoteMap = remote.associateBy { it.id }; val mergedMap = merged.associateBy { it.id }
        val fallback = if (forLocal) localMap else remoteMap
        return (fallback.keys + mergedMap.keys).mapNotNull { id ->
            val base = if (selection.libraryOrganization) mergedMap[id] else fallback[id]
            val progress = if (selection.readingData) mergedMap[id] else fallback[id]
            base?.copy(lastReadTime = progress?.lastReadTime ?: base.lastReadTime,
                readingProgress = progress?.readingProgress ?: base.readingProgress,
                locatorJson = progress?.locatorJson ?: base.locatorJson,
                isCloudOnly = fallback[id]?.isCloudOnly ?: base.isCloudOnly)
        }
    }
    fun referenced(snapshot: PortableSnapshot, id: String): Boolean = snapshot.preferences.any { it.value.contains("asset://" + id) } ||
        snapshot.books.any { it.coverAssetId == id } || snapshot.folders.any { it.coverPath == "asset://" + id }
    fun stripRuleLibrary(snapshot: PortableSnapshot) = snapshot.copy(highlightRules = emptyList(), bookHighlightRuleStates = emptyList(), bookHighlightSettings = emptyList())
    fun empty(deviceId: String) = PortableSnapshot(createdAt = 0, sourceDeviceId = deviceId, preferences = emptyList(), books = emptyList(),
        folders = emptyList(), bookFolderLinks = emptyList(), tags = emptyList(), bookTagLinks = emptyList(), readingRecords = emptyList(),
        bookmarks = emptyList(), notes = emptyList(), tombstones = emptyList(), assets = emptyList())
    fun fingerprint(snapshot: PortableSnapshot): String = MessageDigest.getInstance("SHA-256").digest(snapshot.copy(
        createdAt = 0, sourceDeviceId = "", preferences = snapshot.preferences.sortedBy { it.key }, books = snapshot.books.sortedBy { it.id },
        folders = snapshot.folders.sortedBy { it.id }, bookFolderLinks = snapshot.bookFolderLinks.sortedBy { it.bookId },
        tags = snapshot.tags.sortedBy { it.id }, annotationTags = snapshot.annotationTags.sortedBy { it.name },
        bookTagLinks = snapshot.bookTagLinks.sortedBy { it.bookId + ":" + it.tagId },
        readingRecords = snapshot.readingRecords.sortedBy { it.bookId + ":" + it.date + ":" + it.sourceDeviceId },
        bookmarks = snapshot.bookmarks.sortedBy { it.syncId }, notes = snapshot.notes.sortedBy { it.syncId },
        highlightRules = snapshot.highlightRules.sortedBy { it.id }, bookHighlightRuleStates = snapshot.bookHighlightRuleStates.sortedBy { it.bookId + ":" + it.ruleId },
        bookHighlightSettings = snapshot.bookHighlightSettings.sortedBy { it.bookId }, highlightRuleExclusions = snapshot.highlightRuleExclusions.sortedBy { it.bookId + ":" + it.ruleId + ":" + it.matchKey },
        tombstones = snapshot.tombstones.sortedBy { it.namespace + ":" + it.itemId }, assets = snapshot.assets.sortedBy { it.id }
    ).toJson().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
