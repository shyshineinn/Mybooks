package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.data.backup.*
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.domain.model.SyncContentSelection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyBooksPortableStateSync @Inject constructor(
    @ApplicationContext context: Context,
    private val database: AppDatabase,
    snapshots: PortableSnapshotManager
) {
    private val engine = MyBooksPortableStateEngine(context, MyBooksApiClient(),
        capture = { capturePins(snapshots.capture(includeBookFiles = false, includeBookCovers = false)) },
        apply = { snapshot, files ->
            snapshots.apply(localLayout(snapshot).copy(books = emptyList()), files, replace = false, replaceAssetDirectories = false,
                preserveLocalBookIdentity = true, preserveNewerRows = true, preserveBookTags = true)
            applyPins(snapshot.books)
        },
        identities = ::identities,
        revision = { library -> database.syncStateDao().getValue("mybooks_portable_revision:" + account(library))?.toLong() ?: 0L },
        rememberRevision = { library, revision -> database.syncStateDao().putState(SyncStateEntity("mybooks_portable_revision:" + account(library), revision.toString())) }
    )
    suspend fun sync(library: String, content: SyncContentSelection, direction: MyBooksSyncDirection) = engine.sync(library, content, direction)

    private fun account(library: String) = library + ":" + MyBooksConfig.connection.value.username
    private fun pinKey(uuid: String) = "mybooks_portable_pin:" + account(MyBooksConfig.LIBRARY_KEY) + ":" + uuid
    private suspend fun identities() = database.myBooksSyncStateDao().getAll().filterNot { it.remoteDeleted }.mapNotNull { state ->
        runCatching { state.bookId to JSONObject(state.baseJson).getString("uuid") }.getOrNull()
    }.toMap()

    private suspend fun capturePins(bundle: PortableSnapshotBundle): PortableSnapshotBundle = database.withTransaction {
        val ids = identities()
        val dao = database.syncStateDao()
        val current = database.bookDao().getAllBooksSnapshot().associateBy { it.id }
        val books = bundle.snapshot.books.mapNotNull { book ->
            val uuid = ids[book.id] ?: return@mapNotNull null
            val pinned = current[book.id]?.isPinned ?: return@mapNotNull null
            val key = pinKey(uuid)
            val previous = dao.getValue(key)?.let(::JSONObject)
            val previousTime = previous?.optLong("updatedAt") ?: 0L
            val changed = pinned != (previous?.optBoolean("pinned") ?: false)
            val time = if (changed) maxOf(System.currentTimeMillis(), previousTime + 1) else previousTime
            if (changed || previous == null) rememberPin(key, pinned, time)
            book.copy(isPinned = pinned, metadataUpdatedAt = time)
        }
        bundle.copy(snapshot = bundle.snapshot.copy(books = books))
    }

    private suspend fun applyPins(books: List<PortableBook>) = database.withTransaction {
        val ids = identities()
        for (book in books) {
            val uuid = ids[book.id] ?: continue
            val key = pinKey(uuid)
            val previous = database.syncStateDao().getValue(key)?.let(::JSONObject) ?: continue
            val previousTime = previous.getLong("updatedAt")
            if (book.metadataUpdatedAt < previousTime ||
                (book.metadataUpdatedAt == previousTime && book.isPinned == previous.getBoolean("pinned"))) continue
            if (database.bookDao().applySyncedPin(book.id, book.isPinned, previous.getBoolean("pinned")) != 0) {
                rememberPin(key, book.isPinned, book.metadataUpdatedAt)
            }
        }
    }

    private suspend fun rememberPin(key: String, pinned: Boolean, time: Long) {
        val data = JSONObject().put("pinned", pinned).put("updatedAt", time)
        database.syncStateDao().putState(SyncStateEntity(key, data.toString()))
    }

    private suspend fun localLayout(snapshot: PortableSnapshot): PortableSnapshot {
        val dao = database.folderDao()
        val local = dao.getAllFolders().first().associateBy { it.id }
        val incoming = snapshot.folders.associateBy { it.id }
        val mapped = mutableMapOf<String, String>()
        val aliasKey = "mybooks_portable_folders:" + account(MyBooksConfig.LIBRARY_KEY)
        val aliases = database.syncStateDao().getValue(aliasKey)?.let(::JSONObject) ?: JSONObject()
        val occupied = local.values.associateBy { it.parentId to it.normalizedName }.toMutableMap()
        val folders = mutableListOf<com.huangder.lumibooks.data.local.entity.FolderEntity>()
        fun mapFolder(id: String, visiting: Set<String>): String? {
            mapped[id]?.let { return it }
            if (id in visiting) return null
            val folder = incoming[id] ?: return null
            val parent = folder.parentId?.let { mapFolder(it, visiting + id) ?: return null }
            val name = parent to folder.normalizedName
            val existing = occupied[name]
            if (existing?.isStorageFolder == true || myBooksFolderUsesStorage(id, local::get)) return null
            val target = folder.copy(id = existing?.id ?: folder.id, parentId = parent)
            mapped[id] = target.id
            occupied[name] = target
            folders += target
            return target.id
        }
        snapshot.folders.sortedBy { it.updatedAt }.forEach { mapFolder(it.id, emptySet()) }
        val foldersById = local + folders.associateBy { it.id }
        val localLinks = dao.getAllBookFolderLinks().first()
        val physicalBooks = localLinks.filter { link ->
            myBooksFolderUsesStorage(link.folderId) { local[it] }
        }.mapTo(mutableSetOf()) { it.bookId }
        val appOnlyBooks = localLinks.filter { it.bookId !in physicalBooks &&
            myBooksDirectoryForFolder(it.folderId) { id -> local[id] } == null
        }.mapTo(mutableSetOf()) { it.bookId }
        val protectedFolders = mutableSetOf<String>()
        val protectedRoots = local.values.filter { it.isStorageFolder }.map { it.id } +
            localLinks.filterNot { it.bookId in appOnlyBooks }.map { it.folderId }
        for (root in protectedRoots) {
            var id: String? = root
            while (id != null && protectedFolders.add(id)) id = local[id]?.parentId
        }
        val nativeDirectories = database.myBooksSyncStateDao().getAll().associate { state ->
            state.bookId to runCatching { JSONObject(state.baseJson).optString("folder") }.getOrDefault("")
        }
        val links = snapshot.bookFolderLinks.mapNotNull { link ->
            if (link.bookId in physicalBooks) return@mapNotNull null
            val id = mapped[link.folderId] ?: return@mapNotNull null
            val directory = myBooksDirectoryForFolder(id) { foldersById[it] }
            if (directory != null && (link.bookId !in appOnlyBooks ||
                    !directory.equals(nativeDirectories[link.bookId], ignoreCase = true))) return@mapNotNull null
            link.copy(folderId = id)
        }
        for ((remote, localId) in mapped) {
            if (remote != localId) aliases.put(remote, localId) else aliases.remove(remote)
        }
        database.syncStateDao().putState(SyncStateEntity(aliasKey, aliases.toString()))
        val tombstones = snapshot.tombstones.mapNotNull { tombstone ->
            when (tombstone.namespace) {
                "book_folder" -> tombstone.takeIf { it.itemId in appOnlyBooks }
                "folder" -> {
                    val id = aliases.optString(tombstone.itemId, tombstone.itemId)
                    tombstone.copy(itemId = id).takeIf { id !in protectedFolders }
                }
                else -> null
            }
        }.groupBy { it.namespace to it.itemId }.values.map { rows -> rows.maxBy { it.deletedAt } }
        return snapshot.copy(folders = folders.associateBy { it.id }.values.toList(), bookFolderLinks = links, tombstones = tombstones)
    }
}

internal class MyBooksPortableStateEngine(
    private val context: Context,
    private val api: MyBooksApiClient,
    private val capture: suspend () -> PortableSnapshotBundle,
    private val apply: suspend (PortableSnapshot, Map<String, File>) -> Unit,
    private val identities: suspend () -> Map<String, String>,
    private val revision: suspend (String) -> Long,
    private val rememberRevision: suspend (String, Long) -> Unit
) {
    suspend fun sync(library: String, selection: SyncContentSelection, direction: MyBooksSyncDirection) {
        if (!selection.profileAndSettings && !selection.libraryOrganization && !selection.readingData) return
        val active = identities()
        val localBundle = forServer(capture(), active, selection)
        val stateSelection = selection.copy(readingData = false)
        repeat(3) { attempt ->
            val envelope = api.fetchPortableState()
            require(envelope.getString("library_id") == library) { "书库身份已变化" }
            val remoteRevision = envelope.getLong("revision")
            require(remoteRevision >= revision(library)) { "服务器同步状态版本倒退" }
            val remote = readState(envelope, active, localBundle.snapshot.sourceDeviceId)
            val merged = PortableSnapshotMerger.merge(localBundle.snapshot, remote)
            var outgoing = PortableSyncRules.forRemote(localBundle.snapshot, remote, merged, stateSelection)
            outgoing = wire(outgoing.copy(annotationTags = if (selection.readingData) merged.annotationTags else remote.annotationTags))
            if (direction == MyBooksSyncDirection.DOWNLOAD) outgoing = remote
            val directory = File(context.cacheDir, "mybooks-state-" + UUID.randomUUID()).apply { mkdirs() }
            try {
                val files = mutableMapOf<String, File>()
                val existingFiles = remote.assets.mapTo(mutableSetOf()) { it.fileName }
                for (asset in outgoing.assets) {
                    currentCoroutineContext().ensureActive()
                    val source = localBundle.assetSources[asset.id]
                    if (source != null) {
                        val file = materialize(source, directory); files[asset.id] = file
                        if (direction != MyBooksSyncDirection.DOWNLOAD && asset.fileName !in existingFiles) api.uploadPortableAsset(file, asset.fileName)
                    }
                }
                var confirmed = envelope
                if (direction != MyBooksSyncDirection.DOWNLOAD && PortableSyncRules.fingerprint(outgoing) != PortableSyncRules.fingerprint(remote)) {
                    val json = JSONObject(outgoing.toJson()).put("bookIdentities", JSONObject(outgoing.books.associate { it.id to requireNotNull(active[it.id]) }))
                    try { confirmed = api.putPortableState(library, remoteRevision, json) }
                    catch (error: MyBooksHttpException) {
                        if (error.status == 409 && attempt < 2) return@repeat
                        throw error
                    }
                }
                currentCoroutineContext().ensureActive()
                require(confirmed.getString("library_id") == library && confirmed.getLong("revision") >= remoteRevision)
                val accepted = readState(confirmed, active, localBundle.snapshot.sourceDeviceId)
                val latestBundle = forServer(capture(), active, selection)
                val latest = latestBundle.snapshot
                val combined = PortableSnapshotMerger.merge(latest, accepted)
                var incoming = PortableSyncRules.forLocal(latest, combined, stateSelection)
                incoming = incoming.copy(
                    annotationTags = if (selection.readingData) combined.annotationTags else latest.annotationTags,
                    tombstones = if (selection.libraryOrganization) incoming.tombstones else latest.tombstones
                )
                // Books absent from the latest local capture must not leave book-scoped rows
                // behind; inserting one would violate the cross-reference foreign keys.
                val presentBookIds = latest.books.mapTo(mutableSetOf()) { it.id }
                incoming = incoming.copy(
                    books = incoming.books.filter { it.id in presentBookIds },
                    bookFolderLinks = incoming.bookFolderLinks.filter { it.bookId in presentBookIds },
                    bookTagLinks = incoming.bookTagLinks.filter { it.bookId in presentBookIds },
                    readingRecords = incoming.readingRecords.filter { it.bookId in presentBookIds },
                    bookmarks = incoming.bookmarks.filter { it.bookId in presentBookIds },
                    notes = incoming.notes.filter { it.bookId in presentBookIds }
                )
                for (asset in incoming.assets.filter { PortableSyncRules.referenced(incoming, it.id) }) {
                    if (asset.id in files) continue
                    val source = latestBundle.assetSources[asset.id]
                    val file = if (source != null) materialize(source, directory) else File(directory, asset.fileName).also { target ->
                        val receipt = api.downloadPortableAsset(asset.fileName, target, asset.sizeBytes)
                        require(receipt.sha256 == asset.sha256 && receipt.size == asset.sizeBytes) { "同步资源校验失败" }
                    }
                    files[asset.id] = file
                }
                if (PortableSyncRules.fingerprint(incoming) != PortableSyncRules.fingerprint(latest)) apply(incoming, files)
                rememberRevision(library, confirmed.getLong("revision"))
                return
            } finally { directory.deleteRecursively() }
        }
        error("同步状态被其他设备连续更新，请重试")
    }
    private fun forServer(bundle: PortableSnapshotBundle, active: Map<String, String>, selection: SyncContentSelection): PortableSnapshotBundle {
        val selected = PortableSyncRules.filterBundle(bundle, selection)
        val local = selected.snapshot
        val ids = local.books.filter { it.id in active }.mapTo(mutableSetOf()) { it.id }
        val foldersById = local.folders.associateBy { it.id }
        val folders = local.folders.filterNot { myBooksFolderUsesStorage(it.id, foldersById::get) }
        val folderIds = folders.mapTo(mutableSetOf()) { it.id }
        val snapshot = local.copy(
            preferences = local.preferences.filterNot { privatePreference.containsMatchIn(it.key) },
            books = local.books.filter { it.id in ids }.map { it.pinProjection() },
            folders = folders,
            bookFolderLinks = local.bookFolderLinks.filter { it.bookId in ids && it.folderId in folderIds },
            tags = emptyList(), bookTagLinks = emptyList(),
            readingRecords = emptyList(), bookmarks = emptyList(), notes = emptyList(),
            tombstones = local.tombstones.filter { it.namespace == "folder" || it.namespace == "book_folder" && it.itemId in ids },
            highlightRuleExclusions = emptyList(),
            assets = local.assets.filter { it.kind != "book_cover" })
        val assets = snapshot.assets.filter { PortableSyncRules.referenced(snapshot, it.id) }
        return selected.copy(snapshot = snapshot.copy(assets = assets), assetSources = selected.assetSources.filterKeys { id -> assets.any { it.id == id } })
    }
    private fun readState(envelope: JSONObject, active: Map<String, String>, device: String): PortableSnapshot {
        val raw = envelope.optJSONObject("data") ?: return PortableSyncRules.empty(device)
        val identities = raw.getJSONObject("bookIdentities")
        val state = PortableSyncRules.stripRuleLibrary(PortableSnapshot.fromJson(raw.toString()))
        val ids = state.books.filter { active[it.id] != null && identities.optString(it.id) == active[it.id] }.mapTo(mutableSetOf()) { it.id }
        val snapshot = state.copy(books = state.books.filter { it.id in ids }, bookFolderLinks = state.bookFolderLinks.filter { it.bookId in ids },
            // 服务器书籍的标签关联只由书目三方合并维护；旧快照不能重新添加或删除关联。
            tags = emptyList(), bookTagLinks = emptyList(), readingRecords = emptyList(), bookmarks = emptyList(), notes = emptyList(),
            highlightRuleExclusions = emptyList(), tombstones = state.tombstones.filter {
                it.namespace == "folder" || it.namespace == "book_folder" && it.itemId in ids
            }, assets = state.assets.filter { it.kind != "book_cover" })
        return snapshot.copy(assets = snapshot.assets.filter { PortableSyncRules.referenced(snapshot, it.id) })
    }
    private fun wire(snapshot: PortableSnapshot): PortableSnapshot {
        val folderIds = snapshot.folders.mapTo(mutableSetOf()) { it.id }
        return snapshot.copy(
            books = snapshot.books.map { it.pinProjection() },
            folders = snapshot.folders.map { folder ->
                if (folder.parentId == null || folder.parentId in folderIds) folder else folder.copy(parentId = null)
            }
        )
    }

    private fun PortableBook.pinProjection() = copy(
        title = "", author = "", format = "EPUB", createdAt = 0, isFavorite = false, isCloudOnly = false,
        lastReadTime = 0, readingProgress = 0f, locatorJson = null, bodyAssetId = null, coverAssetId = null,
        remoteLibraryKey = null, remoteFileName = null, remoteFileSize = 0, remoteFileSha256 = null
    )
    private fun materialize(source: PortableAssetSource, directory: File): File {
        val file = File(directory, source.asset.fileName)
        source.openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(file.length() == source.asset.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == source.asset.sha256) { "本机同步资源已变化" }
        return file
    }

    private val privatePreference = Regex("^(webdav_|mybooks_|authorized_)|password|access_token|api_key|secret_key", RegexOption.IGNORE_CASE)
}
