package com.unciv.mpserver

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable state for one game, in data/games/<id>/:
 * - meta.json: request + seat tokens (written once)
 * - save.txt: the Unciv save at the start of the current round, replaced atomically after each resolution
 * - plans.jsonl: intents and commits for the open round, fsynced before they are acknowledged
 *
 * Recovery = load save.txt, re-apply plans.jsonl entries for that turn. A crash mid-resolution
 * therefore re-resolves the round from the pre-round save with every acknowledged plan intact.
 */
class GameStore(val dir: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val metaFile = File(dir, "meta.json")
    private val saveFile = File(dir, "save.txt")
    private val plansFile = File(dir, "plans.jsonl")

    init { dir.mkdirs() }

    fun writeMeta(meta: GameMeta) = atomicWrite(metaFile, json.encodeToString(GameMeta.serializer(), meta))

    /** Returns the save size in bytes. */
    fun writeSave(game: GameInfo): Int {
        val save = UncivFiles.gameInfoToString(game, forceZip = true)
        atomicWrite(saveFile, save)
        return save.length
    }

    fun appendPlan(entry: PlanLogEntry) {
        FileOutputStream(plansFile, true).use { out ->
            out.write((json.encodeToString(PlanLogEntry.serializer(), entry) + "\n").toByteArray())
            out.fd.sync()
        }
    }

    /** Called after the next round's save is on disk; stale entries are also filtered by turn on load. */
    fun clearPlans() { plansFile.delete() }

    fun load(): Triple<GameMeta, GameInfo, List<PlanLogEntry>>? {
        if (!metaFile.exists() || !saveFile.exists()) return null
        val meta = json.decodeFromString(GameMeta.serializer(), metaFile.readText())
        val game = UncivFiles.gameInfoFromString(saveFile.readText())
        val plans = if (plansFile.exists()) plansFile.readLines().filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString(PlanLogEntry.serializer(), it) }.getOrNull() }
            .filter { it.turn == game.turns }
        else emptyList()
        return Triple(meta, game, plans)
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
