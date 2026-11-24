package com.playtranslate.bunpro

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What the device has stored for one grammar point beyond its catalogue entry.
 *
 * [nuance], [caution] and [partOfSpeech] come from the bulk sweep; [writeup] is
 * present only for a point the user actually opened. Any field can be null —
 * the row renders whatever is there.
 */
data class StoredGrammarDetail(
    val pointId: Long,
    val nuance: String?,
    val caution: String?,
    val partOfSpeech: String?,
    val writeup: String?,
) {
    val isEmpty: Boolean
        get() = nuance == null && caution == null && partOfSpeech == null && writeup == null
}

/**
 * On-disk mirror of the Bunpro grammar catalogue.
 *
 * Storage follows the house pattern ([com.playtranslate.translationlog.TranslationHistoryStore],
 * [com.playtranslate.yomitan.YomitanDataStore]): `openOrCreateDatabase` under
 * [Context.noBackupFilesDir], `PRAGMA user_version` schema stamp, every
 * operation confined to one single-thread dispatcher.
 *
 * **One deliberate divergence.** `TranslationHistoryStore` holds PRIMARY data,
 * so a version bump there must migrate. This is DERIVED data — every row is
 * re-fetchable from Bunpro — so a bump simply drops and re-syncs. That keeps
 * schema changes free, at the cost of one re-sync.
 *
 * Contents are fetched under the user's own Bunpro credentials and stay on the
 * device: [clear] wipes it when the token is removed, so the mirror never
 * outlives the licence it was fetched under.
 */
object BunproGrammarStore {

    private const val SCHEMA_VERSION = 2
    private const val KEY_BUILD_ID = "build_id"
    private const val KEY_INDEX_SYNCED_AT = "index_synced_at"

    private val dispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "BunproGrammarStore").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var db: SQLiteDatabase? = null

    private fun openDb(ctx: Context): SQLiteDatabase {
        db?.let { return it }
        val file = File(File(ctx.applicationContext.noBackupFilesDir, "bunpro"), "grammar.sqlite")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        val version = database.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        // Derived data: a schema change drops rather than migrates.
        if (version != SCHEMA_VERSION) dropAll(database)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS grammar_points (" +
                "id INTEGER PRIMARY KEY, title TEXT NOT NULL, level TEXT, meaning TEXT, " +
                "grammar_order INTEGER, lesson_id INTEGER, slug TEXT, " +
                // Newline-joined; matching reads these, never re-derives them.
                "aliases TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS grammar_detail (" +
                "point_id INTEGER PRIMARY KEY, nuance TEXT, caution TEXT, " +
                // Written ONLY by [saveWriteup] — i.e. only for a point the user
                // actually opened. The bulk sweep never touches this column; see
                // BunproGrammarIncluded for why the write-ups are not harvested.
                "part_of_speech TEXT, structure_forms TEXT, writeup TEXT, " +
                // 1 only when the bulk sweep wrote this row. A row created by a
                // write-up fetch alone must NOT make the sweep think it is done
                // with that point — see [idsMissingDetail] / [detailCount].
                "swept INTEGER NOT NULL DEFAULT 0, " +
                "fetched_at INTEGER NOT NULL)"
        )
        // Which grammar points the user has already studied. Volatile relative
        // to the catalogue (it changes every review session), so it is stored
        // separately and re-synced on its own schedule.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS studied_grammar (" +
                "point_id INTEGER PRIMARY KEY, streak INTEGER)"
        )
        database.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (k TEXT PRIMARY KEY, v TEXT)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_gp_level ON grammar_points(level)")
        if (version != SCHEMA_VERSION) {
            database.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        db = database
        return database
    }

    private fun dropAll(database: SQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS grammar_points")
        database.execSQL("DROP TABLE IF EXISTS grammar_detail")
        database.execSQL("DROP TABLE IF EXISTS studied_grammar")
        database.execSQL("DROP TABLE IF EXISTS sync_meta")
    }

    // ── Studied set (relevance) ─────────────────────────────────────────

    /** Replaces the studied set wholesale — it is a snapshot of the user's
     *  review state, and a merge would leave removed items behind. */
    suspend fun saveStudied(ctx: Context, reviews: List<BunproReview>) = withContext(dispatcher) {
        val database = openDb(ctx)
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM studied_grammar")
            reviews.forEach { r ->
                val id = r.reviewableId ?: return@forEach
                database.insertWithOnConflict(
                    "studied_grammar", null,
                    ContentValues().apply { put("point_id", id); put("streak", r.streak) },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Records one point as studied, after an add. Avoids a full re-sync just
     *  to reflect a change we already know about. */
    suspend fun markStudied(ctx: Context, pointId: Long) = withContext(dispatcher) {
        openDb(ctx).insertWithOnConflict(
            "studied_grammar", null,
            ContentValues().apply { put("point_id", pointId); put("streak", 0) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    /** point id → SRS streak, for every point in the user's reviews. Absent
     *  keys mean "never studied"; the matcher suppresses only MASTERED ones. */
    suspend fun loadStudied(ctx: Context): Map<Long, Int> = withContext(dispatcher) {
        openDb(ctx).rawQuery("SELECT point_id, streak FROM studied_grammar", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), if (c.isNull(1)) 0 else c.getInt(1)) }
        }
    }

    // ── Index ───────────────────────────────────────────────────────────

    /** Replaces the catalogue wholesale. The index arrives as one response, so
     *  a partial write would mean a partially-correct matcher — this is all or
     *  nothing, in a transaction. */
    suspend fun saveIndex(ctx: Context, points: List<BunproGrammarPoint>, buildId: String?) =
        withContext(dispatcher) {
            val database = openDb(ctx)
            database.beginTransaction()
            try {
                database.execSQL("DELETE FROM grammar_points")
                points.forEach { p ->
                    database.insertWithOnConflict(
                        "grammar_points", null,
                        ContentValues().apply {
                            put("id", p.id); put("title", p.title); put("level", p.level)
                            put("meaning", p.meaning); put("grammar_order", p.grammarOrder)
                            put("lesson_id", p.lessonId); put("slug", p.slug)
                            put("aliases", p.japaneseAliases().joinToString("\n"))
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                buildId?.let { putMeta(database, KEY_BUILD_ID, it) }
                putMeta(database, KEY_INDEX_SYNCED_AT, System.currentTimeMillis().toString())
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }

    suspend fun loadIndex(ctx: Context): List<BunproGrammarPoint> = withContext(dispatcher) {
        openDb(ctx).rawQuery(
            "SELECT id,title,level,meaning,grammar_order,lesson_id,slug,aliases " +
                "FROM grammar_points ORDER BY grammar_order",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BunproGrammarPoint(
                            id = c.getLong(0), title = c.getString(1),
                            level = c.getString(2), meaning = c.getString(3),
                            grammarOrder = if (c.isNull(4)) null else c.getInt(4),
                            lessonId = if (c.isNull(5)) null else c.getInt(5),
                            slug = c.getString(6),
                            // Aliases were computed at write time; round-trip
                            // them through metadata so japaneseAliases() (which
                            // re-filters) yields exactly what was stored.
                            metadata = c.getString(7).replace("\n", ", "),
                        )
                    )
                }
            }
        }
    }

    /** How many points the sweep has covered — drives its progress row. Counts
     *  only swept rows, so opening a write-up can't inflate the number. */
    suspend fun detailCount(ctx: Context): Int = withContext(dispatcher) {
        openDb(ctx).rawQuery("SELECT COUNT(*) FROM grammar_detail WHERE swept = 1", null)
            .use { it.moveToFirst(); it.getInt(0) }
    }

    suspend fun indexSize(ctx: Context): Int = withContext(dispatcher) {
        openDb(ctx).rawQuery("SELECT COUNT(*) FROM grammar_points", null)
            .use { it.moveToFirst(); it.getInt(0) }
    }

    // ── Detail ──────────────────────────────────────────────────────────

    /**
     * Upserts the short fields, **preserving any cached [writeup]**.
     *
     * Deliberately update-then-insert rather than CONFLICT_REPLACE: a replace
     * rewrites the whole row, so a later sweep over a point the user had
     * already opened would silently discard its cached explanation and make
     * the row fetch again on the next capture.
     */
    suspend fun saveDetail(ctx: Context, detail: BunproGrammarDetail) = withContext(dispatcher) {
        val database = openDb(ctx)
        val values = ContentValues().apply {
            put("nuance", detail.nuanceTranslation)
            put("caution", detail.caution)
            put("part_of_speech", detail.partOfSpeech)
            put("structure_forms", detail.structureForms().joinToString("\n"))
            put("swept", 1)
            put("fetched_at", System.currentTimeMillis())
        }
        val updated = database.update(
            "grammar_detail", values, "point_id = ?", arrayOf(detail.id.toString()),
        )
        if (updated == 0) {
            database.insertWithOnConflict(
                "grammar_detail", null,
                ContentValues(values).apply { put("point_id", detail.id) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        Unit
    }

    /** Caches the lesson write-up for ONE point the user opened. Same
     *  update-or-insert shape as [saveDetail] so it never clobbers the short
     *  fields a sweep may already have stored. */
    suspend fun saveWriteup(ctx: Context, pointId: Long, writeup: String) = withContext(dispatcher) {
        val database = openDb(ctx)
        val values = ContentValues().apply { put("writeup", writeup) }
        val updated = database.update(
            "grammar_detail", values, "point_id = ?", arrayOf(pointId.toString()),
        )
        if (updated == 0) {
            database.insertWithOnConflict(
                "grammar_detail", null,
                ContentValues(values).apply {
                    put("point_id", pointId)
                    put("structure_forms", "")
                    put("fetched_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        Unit
    }

    /** Everything stored for one point, or null when nothing has been fetched
     *  for it yet. Drives the inline detail on a grammar row. */
    suspend fun loadDetail(ctx: Context, pointId: Long): StoredGrammarDetail? =
        withContext(dispatcher) {
            openDb(ctx).rawQuery(
                "SELECT nuance, caution, part_of_speech, writeup FROM grammar_detail " +
                    "WHERE point_id = ?",
                arrayOf(pointId.toString()),
            ).use { c ->
                if (!c.moveToFirst()) null
                else StoredGrammarDetail(
                    pointId = pointId,
                    nuance = c.getString(0)?.takeIf { it.isNotBlank() },
                    caution = c.getString(1)?.takeIf { it.isNotBlank() },
                    partOfSpeech = c.getString(2)?.takeIf { it.isNotBlank() },
                    writeup = c.getString(3)?.takeIf { it.isNotBlank() },
                )
            }
        }

    /** point id → conjugated forms from its structure tables, for
     *  `BunproGrammarIndex(extraForms = …)`. */
    suspend fun loadStructureForms(ctx: Context): Map<Long, List<String>> = withContext(dispatcher) {
        openDb(ctx).rawQuery(
            "SELECT point_id, structure_forms FROM grammar_detail WHERE structure_forms <> ''",
            null,
        ).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), c.getString(1).split("\n").filter { it.isNotBlank() })
            }
        }
    }

    /**
     * Catalogue ids with no detail row yet — the resume point.
     *
     * A detail sweep is ~979 requests against someone else's server; it will be
     * interrupted (app backgrounded, connection dropped, Bunpro redeployed).
     * Resuming from here means an interruption costs only the in-flight request.
     */
    suspend fun idsMissingDetail(ctx: Context): List<Long> = withContext(dispatcher) {
        openDb(ctx).rawQuery(
            "SELECT p.id FROM grammar_points p " +
                "LEFT JOIN grammar_detail d ON d.point_id = p.id " +
                // `swept <> 1` covers the row a write-up fetch created: it holds
                // an explanation but none of the conjugation tables the sweep is
                // actually after, so the sweep must still visit it.
                "WHERE d.point_id IS NULL OR d.swept <> 1 ORDER BY p.grammar_order",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    // ── Meta / lifecycle ────────────────────────────────────────────────

    suspend fun buildId(ctx: Context): String? = withContext(dispatcher) {
        getMeta(openDb(ctx), KEY_BUILD_ID)
    }

    suspend fun indexSyncedAt(ctx: Context): Long? = withContext(dispatcher) {
        getMeta(openDb(ctx), KEY_INDEX_SYNCED_AT)?.toLongOrNull()
    }

    /** Wipes the mirror. Called when the Bunpro token is cleared — the cached
     *  catalogue must not outlive the credential it was fetched under. */
    suspend fun clear(ctx: Context) = withContext(dispatcher) {
        val database = openDb(ctx)
        dropAll(database)
        db = null
        database.close()
        File(File(ctx.applicationContext.noBackupFilesDir, "bunpro"), "grammar.sqlite").delete()
        Unit
    }

    private fun putMeta(database: SQLiteDatabase, k: String, v: String) {
        database.insertWithOnConflict(
            "sync_meta", null,
            ContentValues().apply { put("k", k); put("v", v) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun getMeta(database: SQLiteDatabase, k: String): String? =
        database.rawQuery("SELECT v FROM sync_meta WHERE k = ?", arrayOf(k))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    @VisibleForTesting
    internal fun closeForTest() {
        db?.close(); db = null
    }
}
