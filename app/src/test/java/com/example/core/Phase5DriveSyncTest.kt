package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.core.database.DriveFileMetadataEntity
import com.example.core.database.SyncStatus
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import com.example.core.drive.AquaDriveSyncWorker
import com.example.core.drive.ConflictResolutionStrategy
import com.example.core.security.PermissionDomain
import com.example.core.security.PermissionPolicyStore
import com.example.data.local.AquaCacheDatabase
import com.example.data.repository.DriveConversationSnapshot
import com.example.data.repository.DriveHistorySnapshot
import com.example.data.repository.DriveMemorySnapshot
import com.example.data.repository.DriveMessageSnapshot
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class Phase5DriveSyncTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase
    private lateinit var policyStore: PermissionPolicyStore
    private lateinit var httpServer: HttpServer
    private lateinit var baseUrl: String

    private val createdFolders = mutableListOf<String>()
    private val remoteFiles = ConcurrentHashMap<String, Triple<String, String, Int>>() // id -> (name, content, version)
    private val idCounter = AtomicInteger(1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aqua_drive_folder_ids", Context.MODE_PRIVATE).edit().clear().apply()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
        policyStore = PermissionPolicyStore(context, "uid_phase5")
        policyStore.resetDefaults()

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/drive/v3/files") { exchange ->
            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val bodyBytes = exchange.requestBody.readBytes()
            val bodyStr = String(bodyBytes, Charsets.UTF_8)

            val responseJson = when {
                method == "GET" && path == "/drive/v3/files" -> {
                    val qParam = query.split("&").firstOrNull { it.startsWith("q=") }?.substringAfter("q=").orEmpty()
                    val decodedQ = URLDecoder.decode(qParam, "UTF-8")
                    val filesArr = JSONArray()
                    remoteFiles.forEach { (id, triple) ->
                        val (name, _, version) = triple
                        if (decodedQ.contains("name = '$name'")) {
                            filesArr.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("name", name)
                                    .put("modifiedTime", "2026-11-20T12:00:00Z")
                                    .put("version", version.toString())
                                    .put("md5Checksum", "abc123")
                                    .put("size", "42")
                            )
                        }
                    }
                    JSONObject().put("files", filesArr).toString()
                }
                method == "POST" && path == "/drive/v3/files" -> {
                    val req = JSONObject(bodyStr)
                    val folderName = req.optString("name", "folder")
                    val fid = "folder_${idCounter.getAndIncrement()}"
                    createdFolders.add(folderName)
                    remoteFiles[fid] = Triple(folderName, "", 1)
                    JSONObject().put("id", fid).put("name", folderName).toString()
                }
                method == "DELETE" && path.startsWith("/drive/v3/files/") -> {
                    val fid = path.substringAfterLast("/")
                    remoteFiles.remove(fid)
                    ""
                }
                else -> "{}"
            }

            val code = if (method == "DELETE") 204 else 200
            val outBytes = responseJson.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, if (code == 204) -1L else outBytes.size.toLong())
            if (code != 204) {
                exchange.responseBody.use { it.write(outBytes) }
            }
            exchange.close()
        }

        httpServer.createContext("/upload/drive/v3/files") { exchange ->
            val method = exchange.requestMethod.uppercase()
            val path = exchange.requestURI.path
            val rawBody = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            val extractedName = Regex("\"name\":\"([^\"]+)\"").find(rawBody)?.groupValues?.getOrNull(1) ?: "item.json"

            val respJson = if (method == "PATCH" && path.startsWith("/upload/drive/v3/files/")) {
                val fid = path.substringAfterLast("/")
                val prev = remoteFiles[fid]
                val nextVer = (prev?.third ?: 1) + 1
                remoteFiles[fid] = Triple(extractedName, rawBody, nextVer)
                JSONObject()
                    .put("id", fid)
                    .put("name", extractedName)
                    .put("modifiedTime", "2026-11-20T12:05:00Z")
                    .put("version", nextVer.toString())
                    .put("size", rawBody.length.toString())
                    .toString()
            } else {
                val fid = "file_${idCounter.getAndIncrement()}"
                remoteFiles[fid] = Triple(extractedName, rawBody, 1)
                JSONObject()
                    .put("id", fid)
                    .put("name", extractedName)
                    .put("modifiedTime", "2026-11-20T12:01:00Z")
                    .put("version", "1")
                    .put("size", rawBody.length.toString())
                    .toString()
            }
            val bytes = respJson.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        httpServer.start()
        baseUrl = "http://127.0.0.1:${httpServer.address.port}"
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
        db.close()
    }

    @Test
    fun `drive sync coordinator rejects secrets creates 10 folders flushes queue and detects conflicts`() = runBlocking {
        val coordinator = AquaDriveSyncCoordinator(
            context = context,
            syncQueueDao = db.driveSyncQueueDao(),
            metadataDao = db.driveFileMetadataDao(),
            database = db,
            permissionPolicyStore = policyStore,
            driveApiBaseUrl = "$baseUrl/drive/v3",
            driveUploadBaseUrl = "$baseUrl/upload/drive/v3"
        )

        // Secret exclusion gate
        val secretBlocked = coordinator.enqueueMutation(
            userId = "uid_phase5",
            entityType = "wallet_vault",
            entityId = "w1",
            driveFolder = AquaDriveFolders.BACKUPS,
            fileName = "wallet_backup.json",
            serializedPayloadJson = """{"mnemonic":"abandon ability able about"}""",
            scheduleBackgroundWork = false
        )
        assertTrue(secretBlocked.isFailure)

        // Clean memory mutation queues offline cleanly
        val queued = coordinator.enqueueMutation(
            userId = "uid_phase5",
            entityType = "memory",
            entityId = "mem_1",
            driveFolder = AquaDriveFolders.MEMORY,
            fileName = "mem_1.json",
            serializedPayloadJson = """{"title":"Language","content":"Prefers concise Urdu + English"}""",
            scheduleBackgroundWork = false
        )
        assertTrue(queued.isSuccess)
        assertEquals(1, db.driveSyncQueueDao().observePendingCount("uid_phase5").first())

        // Flush -> initializes Aqua AI/ + all 10 folders and uploads mem_1.json
        val summary1 = coordinator.flushQueue("uid_phase5", accessToken = "mock_oauth_token")
        assertEquals(1, summary1.syncedCount)
        assertEquals(0, summary1.conflictCount)
        assertTrue(summary1.foldersReady)
        assertTrue(createdFolders.contains(AquaDriveFolders.ROOT_FOLDER_NAME))
        AquaDriveFolders.ALL_SUBFOLDERS.forEach { folder ->
            assertTrue("Expected Drive folder '$folder'", createdFolders.contains(folder))
        }

        val syncedMeta = db.driveFileMetadataDao().getFilesInFolder("uid_phase5", AquaDriveFolders.MEMORY).firstOrNull()
        assertTrue(syncedMeta != null)

        // Simulate remote concurrent modification (bump remote file version to v9)
        val existingRemote = remoteFiles[syncedMeta!!.fileId]!!
        remoteFiles[syncedMeta.fileId] = Triple(existingRemote.first, existingRemote.second, 9)

        // Queue updated local payload -> flush detects conflict & saves conflict copy without deleting data
        coordinator.enqueueMutation(
            userId = "uid_phase5",
            entityType = "memory",
            entityId = "mem_1",
            driveFolder = AquaDriveFolders.MEMORY,
            fileName = "mem_1.json",
            serializedPayloadJson = """{"title":"Language","content":"Updated locally offline"}""",
            scheduleBackgroundWork = false
        )
        val summaryConflict = coordinator.flushQueue("uid_phase5", accessToken = "mock_oauth_token")
        assertEquals(1, summaryConflict.conflictCount)
        val conflicts = db.driveSyncQueueDao().observeConflicts("uid_phase5").first()
        assertEquals(1, conflicts.size)
        assertEquals(SyncStatus.CONFLICT, conflicts.first().status)

        // Resolve conflict by keeping local
        val resolved = coordinator.resolveConflict(
            userId = "uid_phase5",
            mutationId = conflicts.first().mutationId,
            strategy = ConflictResolutionStrategy.KEEP_LOCAL,
            accessToken = "mock_oauth_token"
        )
        assertTrue(resolved.isSuccess)
        assertEquals(0, db.driveSyncQueueDao().observeConflicts("uid_phase5").first().size)
    }

    @Test
    fun `restore snapshot writes conversations messages and memories directly into encrypted Room SSOT`() = runBlocking {
        val coordinator = AquaDriveSyncCoordinator(
            context = context,
            syncQueueDao = db.driveSyncQueueDao(),
            metadataDao = db.driveFileMetadataDao(),
            database = db,
            permissionPolicyStore = policyStore
        )
        val snapshot = DriveHistorySnapshot(
            exportedAtIso = "2026-11-20T12:00:00Z",
            userId = "uid_restore",
            conversations = listOf(
                DriveConversationSnapshot(
                    id = "c_1",
                    title = "Urdu Voice Chat",
                    summary = "Checked weather",
                    modelUsed = "gemini-3.8-flash",
                    messageCount = 2L,
                    messages = listOf(
                        DriveMessageSnapshot("msg_1", "user", "Aaj ka mausam?", "", "", "", "", ""),
                        DriveMessageSnapshot("msg_2", "model", "Karachi mein dhoop hai.", "gemini-3.8-flash", "", "", "", "")
                    )
                )
            ),
            memories = listOf(
                DriveMemorySnapshot("mem_r1", "preference", "City", "Karachi")
            )
        )
        val (convs, mems) = coordinator.restoreSnapshotIntoRoom("uid_restore", snapshot)
        assertEquals(1, convs)
        assertEquals(1, mems)
        assertEquals(1, db.conversationDao().getConversations("uid_restore").size)
        assertEquals(2, db.conversationDao().getMessages("c_1").size)
        assertEquals(1, db.memoryDao().getMemories("uid_restore").size)
    }

    @Test
    fun `drive sync worker skips cleanly when cloud storage permission domain is disabled`() = runBlocking {
        policyStore.setAllowed(PermissionDomain.CLOUD_STORAGE, false)
        val worker = TestListenableWorkerBuilder<AquaDriveSyncWorker>(context)
            .setInputData(workDataOf(AquaDriveSyncWorker.KEY_USER_ID to "uid_phase5"))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val successResult = result as ListenableWorker.Result.Success
        assertEquals(
            "skipped_cloud_policy_disabled",
            successResult.outputData.getString(AquaDriveSyncWorker.KEY_STATUS)
        )
    }
}
