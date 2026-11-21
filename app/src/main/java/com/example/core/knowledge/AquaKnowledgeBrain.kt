package com.example.core.knowledge

import com.example.core.database.DriveFileMetadataDao
import com.example.core.drive.AquaDriveFolders
import com.example.core.memory.AquaMemoryEngine
import com.example.core.workspace.AquaWorkspaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KnowledgeCitation(
    val sourceType: String, // MEMORY, RESEARCH_DOC, AI_DOCUMENT, DRIVE_FILE
    val id: String,
    val title: String,
    val snippet: String,
    val originLabel: String
)

/**
 * Local + Drive RAG pipeline querying real FTS4 Memory, Workspace documents,
 * and Drive metadata to produce verifiable grounded citations (zero fabricated sources).
 */
class AquaKnowledgeBrain(
    private val memoryEngine: AquaMemoryEngine,
    private val workspaceEngine: AquaWorkspaceEngine,
    private val driveMetadataDao: DriveFileMetadataDao,
    private val userIdProvider: () -> String = { "default_user" }
) {
    suspend fun queryCitations(
        query: String,
        limit: Int = 12,
        userId: String = userIdProvider()
    ): List<KnowledgeCitation> = withContext(Dispatchers.IO) {
        val uid = userId.ifBlank { "default_user" }
        val clean = query.trim()
        val results = mutableListOf<KnowledgeCitation>()

        val memories = memoryEngine.searchMemories(query = clean, limit = limit, userId = uid)
        memories.forEach { m ->
            results.add(
                KnowledgeCitation(
                    sourceType = "MEMORY",
                    id = m.id,
                    title = m.title,
                    snippet = m.content.take(220),
                    originLabel = "Memory (${m.tier} • ${m.sourceProvenance})"
                )
            )
        }

        val docs = workspaceEngine.searchDocuments(query = clean, userId = uid)
        docs.take(limit).forEach { d ->
            results.add(
                KnowledgeCitation(
                    sourceType = if (d.folderType == "RESEARCH") "RESEARCH_DOC" else "AI_DOCUMENT",
                    id = d.id,
                    title = d.title,
                    snippet = d.content.take(220),
                    originLabel = "Workspace (${d.folderType})"
                )
            )
        }

        val lower = clean.lowercase()
        val driveMatches = AquaDriveFolders.ALL_SUBFOLDERS.flatMap { folder ->
            driveMetadataDao.getFilesInFolder(uid, folder)
        }.filter { meta ->
            lower.isBlank() || meta.name.lowercase().contains(lower) || meta.driveFolder.lowercase().contains(lower)
        }
        driveMatches.take(limit).forEach { meta ->
            results.add(
                KnowledgeCitation(
                    sourceType = "DRIVE_FILE",
                    id = meta.fileId,
                    title = meta.name,
                    snippet = "Synced in Aqua AI/${meta.driveFolder} (${meta.sizeBytes} bytes, modified ${meta.modifiedTimeIso})",
                    originLabel = "Drive/${meta.driveFolder}"
                )
            )
        }

        results.distinctBy { "${it.sourceType}:${it.id}" }.take(limit)
    }

    suspend fun buildGroundedPromptContext(
        query: String,
        userId: String = userIdProvider()
    ): Pair<String, List<KnowledgeCitation>> {
        val citations = queryCitations(query = query, userId = userId)
        if (citations.isEmpty()) {
            return "No matching saved memory, workspace document, or synced Drive file was found for '$query'." to emptyList()
        }
        val formatted = buildString {
            appendLine("[VERIFIED_AQUA_KNOWLEDGE_CITATIONS]")
            citations.forEachIndexed { idx, c ->
                appendLine("[Citation #${idx + 1}] (${c.originLabel}) ${c.title} [id=${c.id}]")
                appendLine(c.snippet)
                appendLine()
            }
        }
        return formatted to citations
    }
}
