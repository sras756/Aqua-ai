package com.example.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.MemoryTier
import com.example.core.drive.AquaDriveFolders
import com.example.core.drive.AquaDriveSyncCoordinator
import com.example.core.memory.AquaMemoryEngine
import com.example.data.local.AquaCacheDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase6MemoryEngineTest {

    private lateinit var context: Context
    private lateinit var db: AquaCacheDatabase
    private lateinit var driveSync: AquaDriveSyncCoordinator
    private lateinit var engine: AquaMemoryEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AquaCacheDatabase.createInMemoryForTesting(context)
        driveSync = AquaDriveSyncCoordinator(
            context = context,
            syncQueueDao = db.driveSyncQueueDao(),
            metadataDao = db.driveFileMetadataDao(),
            database = db
        )
        engine = AquaMemoryEngine(
            memoryDao = db.memoryDao(),
            driveSyncCoordinator = driveSync,
            userIdProvider = { "uid_mem" }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saves tiered memories searches with fts4 exports json markdown and propagates delete to drive queue`() = runBlocking {
        val m1 = engine.saveMemory(
            title = "Preferred Language",
            content = "Speak Urdu and English concisely with technical depth.",
            category = "preference",
            tier = MemoryTier.LONG_TERM,
            confidence = 0.95f,
            sourceProvenance = "voice_session_1",
            isPinned = true,
            scheduleDriveSync = false
        )
        val m2 = engine.saveMemory(
            title = "Active Sprint Summary",
            content = "Working on Aqua AI Android Phase 6 memory engine",
            category = "task_summary",
            tier = MemoryTier.EPISODIC,
            confidence = 0.88f,
            sourceProvenance = "agent_orchestrator",
            scheduleDriveSync = false
        )

        // FTS4 search across content & title
        val urduHits = engine.searchMemories("Urdu")
        assertEquals(1, urduHits.size)
        assertEquals(m1.id, urduHits.first().id)

        // Tier filtering
        val episodic = engine.getAllMemories(tierFilter = MemoryTier.EPISODIC)
        assertEquals(1, episodic.size)
        assertEquals(m2.id, episodic.first().id)

        // JSON and Markdown exports preserve provenance & confidence
        val json = engine.exportMemoriesJson()
        assertTrue(json.contains("Preferred Language"))
        assertTrue(json.contains("voice_session_1"))

        val md = engine.exportMemoriesMarkdown()
        assertTrue(md.contains("[PINNED]"))
        assertTrue(md.contains("95%"))

        // Permanent delete removes locally and enqueues DELETE in Aqua AI/Memory/
        engine.deleteMemoryPermanently(m1.id, scheduleDriveSync = false)
        val remaining = engine.getAllMemories()
        assertEquals(1, remaining.size)
        assertFalse(remaining.any { it.id == m1.id })

        val queue = db.driveSyncQueueDao().getPendingMutations("uid_mem", limit = 10)
        val deleteMutation = queue.firstOrNull { it.entityId == m1.id }
        assertTrue(deleteMutation != null)
        assertEquals("DELETE", deleteMutation!!.operation)
        assertEquals(AquaDriveFolders.MEMORY, deleteMutation.driveFolder)
    }
}
