package com.huangder.lumibooks.data.sync

import android.content.Context
import com.huangder.lumibooks.data.backup.PortableAsset
import com.huangder.lumibooks.data.backup.PortableAssetSource
import com.huangder.lumibooks.data.backup.PortableBook
import com.huangder.lumibooks.data.backup.PortableSnapshot
import com.huangder.lumibooks.data.backup.PortableSnapshotBundle
import com.huangder.lumibooks.data.backup.PortableSnapshotManager
import com.huangder.lumibooks.data.backup.PortableSnapshotMerger
import com.huangder.lumibooks.data.backup.PortableSyncRules
import com.huangder.lumibooks.domain.model.SyncContentSelection
import com.huangder.lumibooks.domain.model.WebdavConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PortableStateSyncResult(
    val uploadedAssets: Int,
    val downloadedAssets: Int
)

@Singleton
class WebdavPortableStateSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: WebdavClient,
    private val snapshotManager: PortableSnapshotManager
) {
    suspend fun sync(config: WebdavConfig, password: String): PortableStateSyncResult {
        val serverUrl = config.serverUrl
        val root = "${config.syncPath}/state-v1"
        client.ensureDirectory(serverUrl, config.username, password, root)
        client.ensureDirectory(serverUrl, config.username, password, "$root/assets")
        val localBundle = snapshotManager.capture(includeBookFiles = false).forWebdav(config)
        var uploadedAssets = 0
        var downloadedAssets = 0

        repeat(MAX_CONFLICT_RETRIES) { attempt ->
            val remoteVersioned = downloadStateOrNull(serverUrl, root, config.username, password)
            val remote = remoteVersioned?.let {
                PortableSnapshot.fromJson(it.data.toString(Charsets.UTF_8))
            } ?: emptySnapshot(localBundle.snapshot.sourceDeviceId)
            val merged = PortableSnapshotMerger.merge(localBundle.snapshot, remote)
            val remotePayload = selectForRemote(localBundle.snapshot, remote, merged, config)
            val localPayload = selectForLocal(localBundle.snapshot, remotePayload, config)
            val workDir = File(context.cacheDir, "webdav_state_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val files = mutableMapOf<String, File>()
                val remoteAssetIds = remote.assets.mapTo(mutableSetOf()) { it.id }
                for (asset in remotePayload.assets) {
                    val localSource = localBundle.assetSources[asset.id]
                    if (localSource != null) {
                        val localFile = materialize(localSource, workDir)
                        files[asset.id] = localFile
                        if (asset.id !in remoteAssetIds) {
                            client.uploadStream(
                                url = WebdavUrl.append(serverUrl, root, "assets", asset.fileName),
                                contentLength = asset.sizeBytes,
                                inputStreamProvider = localSource.openStream,
                                username = config.username,
                                password = password
                            )
                            uploadedAssets++
                        }
                    } else if (assetReferenced(localPayload, asset.id)) {
                        val target = File(workDir, asset.fileName)
                        val result = client.downloadToFile(
                            WebdavUrl.append(serverUrl, root, "assets", asset.fileName),
                            target,
                            config.username,
                            password,
                            asset.sizeBytes
                        )
                        require(result.sha256 == asset.sha256) { "Synced asset checksum mismatch" }
                        files[asset.id] = target
                        downloadedAssets++
                    }
                }

                if (remoteVersioned == null || stateFingerprint(remotePayload) != stateFingerprint(remote)) {
                    val bytes = remotePayload.toJson().toByteArray(Charsets.UTF_8)
                    try {
                        if (remoteVersioned == null || remoteVersioned.etag != null) {
                            client.uploadConditional(
                                WebdavUrl.append(serverUrl, root, "state.json"),
                                bytes,
                                config.username,
                                password,
                                remoteVersioned?.etag,
                                "application/json"
                            )
                        } else {
                            client.upload(
                                WebdavUrl.append(serverUrl, root, "state.json"),
                                bytes,
                                config.username,
                                password,
                                "application/json"
                            )
                        }
                    } catch (error: WebdavException) {
                        if (error.statusCode == 412 && attempt + 1 < MAX_CONFLICT_RETRIES) return@repeat
                        if (error.statusCode in setOf(400, 405, 501)) {
                            client.upload(
                                WebdavUrl.append(serverUrl, root, "state.json"),
                                bytes,
                                config.username,
                                password,
                                "application/json"
                            )
                        } else throw error
                    }
                }
                if (stateFingerprint(localPayload) != stateFingerprint(localBundle.snapshot)) {
                    snapshotManager.apply(
                        localPayload,
                        files,
                        // State snapshots intentionally omit book bodies. Apply them as a
                        // merge so a cloud book discovered from manifest.json cannot disappear
                        // just because it was not present in the state snapshot being applied.
                        replace = false,
                        replaceAssetDirectories = false
                    )
                }
                cleanupUnreferencedAssets(serverUrl, root, config.username, password, remotePayload)
                return PortableStateSyncResult(uploadedAssets, downloadedAssets)
            } finally {
                workDir.deleteRecursively()
            }
        }
        error("WebDAV state changed repeatedly during synchronization")
    }

    private suspend fun downloadStateOrNull(
        serverUrl: String,
        root: String,
        username: String,
        password: String
    ): WebdavVersionedData? = try {
        client.downloadVersioned(WebdavUrl.append(serverUrl, root, "state.json"), username, password)
    } catch (error: WebdavException) {
        if (error.statusCode == 404) null else throw error
    }

    private fun WebdavConfig.selection() = SyncContentSelection(syncBookFiles, syncProfileAndSettings, syncLibraryOrganization, syncReadingData)
    private fun PortableSnapshotBundle.forWebdav(config: WebdavConfig) = PortableSyncRules.filterBundle(this, config.selection())
    private fun selectForRemote(local: PortableSnapshot, remote: PortableSnapshot, merged: PortableSnapshot, config: WebdavConfig) =
        PortableSyncRules.forRemote(local, remote, merged, config.selection())
    private fun selectForLocal(local: PortableSnapshot, remotePayload: PortableSnapshot, config: WebdavConfig) =
        PortableSyncRules.forLocal(local, remotePayload, config.selection())

    private fun materialize(source: PortableAssetSource, directory: File): File {
        val target = File(directory, source.asset.fileName)
        source.openStream().use { input -> target.outputStream().buffered().use { input.copyTo(it) } }
        require(target.length() == source.asset.sizeBytes && target.sha256() == source.asset.sha256)
        return target
    }

    private fun assetReferenced(snapshot: PortableSnapshot, assetId: String): Boolean =
        snapshot.preferences.any { it.value.contains("asset://$assetId") } ||
            snapshot.books.any { it.coverAssetId == assetId } ||
            snapshot.folders.any { it.coverPath == "asset://$assetId" }

    private suspend fun cleanupUnreferencedAssets(
        serverUrl: String,
        root: String,
        username: String,
        password: String,
        committed: PortableSnapshot
    ) {
        val referencedNames = committed.assets.mapTo(mutableSetOf()) { it.fileName }
        runCatching {
            client.listDirectory(WebdavUrl.append(serverUrl, root, "assets"), username, password)
                .asSequence()
                .filterNot { it.isCollection }
                .map { it.href.substringBefore('?').trimEnd('/').substringAfterLast('/') }
                .filter { it.matches(Regex("[a-f0-9]{64}\\.[a-z0-9]{1,10}")) }
                .filterNot { it in referencedNames }
                .forEach { name ->
                    client.delete(
                        WebdavUrl.appendEncoded(WebdavUrl.append(serverUrl, root, "assets"), name),
                        username,
                        password
                    )
                }
        }
    }

    private fun emptySnapshot(deviceId: String) = PortableSyncRules.empty(deviceId)
    private fun stateFingerprint(snapshot: PortableSnapshot) = PortableSyncRules.fingerprint(snapshot)

    companion object {
        private const val MAX_CONFLICT_RETRIES = 3
    }
}

/** Rule libraries belong to explicit LUMI backups in v1, not automatic WebDAV state sync. */
internal fun stripHighlightRuleLibraryForWebdav(snapshot: PortableSnapshot): PortableSnapshot = snapshot.copy(
    highlightRules = emptyList(),
    bookHighlightRuleStates = emptyList(),
    bookHighlightSettings = emptyList()
)

/**
 * 选取应用到本机（[forLocal]=true）或写回服务器 state.json（[forLocal]=false）的书籍列表。
 *
 * `isCloudOnly`/`filePath` 表示“书本体是否已在本机”，是设备本地状态。WebDAV 状态同步
 * 刻意不携带书本体（`bodyAssetId` 恒为空），合并结果无法可靠推导该字段；因此本机应用时
 * 一律保留 local 侧的值，写回服务器时保留 remote 侧的值，绝不用合并结果覆盖。
 */
internal fun chooseWebdavBooksForDevice(
    localBooks: List<PortableBook>,
    remoteBooks: List<PortableBook>,
    mergedBooks: List<PortableBook>,
    syncLibraryOrganization: Boolean,
    syncReadingData: Boolean,
    forLocal: Boolean
): List<PortableBook> {
    val localMap = localBooks.associateBy { it.id }
    val remoteMap = remoteBooks.associateBy { it.id }
    val mergedMap = mergedBooks.associateBy { it.id }
    val fallback = if (forLocal) localMap else remoteMap
    return (fallback.keys + mergedMap.keys).mapNotNull { id ->
        val base = if (syncLibraryOrganization) mergedMap[id] else fallback[id]
        val progress = if (syncReadingData) mergedMap[id] else fallback[id]
        base?.copy(
            lastReadTime = progress?.lastReadTime ?: base.lastReadTime,
            readingProgress = progress?.readingProgress ?: base.readingProgress,
            locatorJson = progress?.locatorJson ?: base.locatorJson,
            isCloudOnly = fallback[id]?.isCloudOnly ?: base.isCloudOnly
        )
    }
}

private fun File.sha256(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
