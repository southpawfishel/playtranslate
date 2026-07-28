package com.playtranslate.bunpro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs and derived models for the Bunpro frontend API
 * (`https://api.bunpro.jp/api/frontend`), reverse-engineered and verified
 * against live traffic. See `docs/features/bunpro-integration.md`.
 *
 * These model only the slice of each (very large) response we consume;
 * [com.playtranslate.PtJson.lenient] is configured with `ignoreUnknownKeys`,
 * so every unmodeled field is dropped silently.
 *
 * JSON keys are snake_case; Kotlin properties stay camelCase via [SerialName].
 */

// ── Search request ──────────────────────────────────────────────────────────

/**
 * Body for `POST /search/reviewables_v1_1`. Toggling [isSearchingGrammar] /
 * [isSearchingVocab] selects which sections come back; `include_reviews`
 * side-loads the caller's SRS [BunproReview] records into the matching
 * section's `included` array (see [BunproSection.srsFor]).
 */
@Serializable
data class BunproSearchRequest(
    val query: String,
    val options: BunproSearchOptions = BunproSearchOptions(),
    @SerialName("is_searching_grammar") val isSearchingGrammar: Boolean,
    @SerialName("is_searching_vocab") val isSearchingVocab: Boolean,
)

@Serializable
data class BunproSearchOptions(
    @SerialName("include_reviews") val includeReviews: Boolean = true,
    @SerialName("include_bookmarks") val includeBookmarks: Boolean = true,
    @SerialName("include_notes") val includeNotes: Boolean = true,
    @SerialName("only_bookmarks") val onlyBookmarks: Boolean = false,
)

// ── Search response ─────────────────────────────────────────────────────────

@Serializable
data class BunproSearchResponse(
    val vocabs: BunproSection? = null,
    @SerialName("grammar_points") val grammarPoints: BunproSection? = null,
)

/**
 * One result section (vocab or grammar). [data] holds the matched items;
 * [included] holds this user's [BunproReview] records for the items they've
 * studied (JSON:API side-loading). `included` is populated per-section only
 * when the request set `include_reviews`.
 */
@Serializable
data class BunproSection(
    val data: List<BunproItem> = emptyList(),
    val included: List<BunproIncluded> = emptyList(),
) {
    /**
     * SRS status for [item], resolved from this section's side-loaded reviews.
     * Returns [BunproSrsStatus.UNSTUDIED] when the user has no review for it.
     * Matching is by numeric id within the section — `included` never mixes
     * item types, so the id alone is unambiguous.
     */
    fun srsFor(item: BunproItem): BunproSrsStatus {
        val review = included
            .firstOrNull { it.type == "review" && it.attributes.reviewableId == item.attributes.id }
        return BunproSrsStatus.from(review?.attributes)
    }
}

@Serializable
data class BunproItem(
    val id: String,
    val type: String,                       // "vocab" | "grammar_point"
    val attributes: BunproItemAttributes,
)

/**
 * Union of the vocab and grammar-point attribute fields we read. Grammar-only
 * and vocab-only fields are both nullable, so one DTO serves both sections.
 */
@Serializable
data class BunproItemAttributes(
    val id: Long,
    val title: String? = null,
    val slug: String? = null,
    val kana: String? = null,
    val furigana: String? = null,
    val meaning: String? = null,
    @SerialName("jlpt_level") val jlptLevel: String? = null,
    // Vocab-only
    @SerialName("jmdict_id") val jmdictId: Long? = null,
    @SerialName("pitch_accent_stress") val pitchAccentStress: String? = null,
    // Grammar-only
    val level: String? = null,
    @SerialName("nuance_translation") val nuanceTranslation: String? = null,
)

@Serializable
data class BunproIncluded(
    val id: String,
    val type: String,                       // "review"
    val attributes: BunproReview,
)

/**
 * A user's SRS record for one reviewable. Bunpro has no single "srs level"
 * integer — standing is expressed through [streak] plus the flags below.
 */
@Serializable
data class BunproReview(
    val id: Long,
    val streak: Int? = null,
    @SerialName("times_studied") val timesStudied: Int? = null,
    val accuracy: Int? = null,
    val complete: Boolean? = null,
    @SerialName("is_recurring_mastered") val isRecurringMastered: Boolean? = null,
    @SerialName("ghost_count") val ghostCount: Int? = null,
    @SerialName("next_review") val nextReview: String? = null,
    @SerialName("reviewable_id") val reviewableId: Long? = null,
    @SerialName("reviewable_type") val reviewableType: String? = null,   // "Vocab" | "GrammarPoint"
)

// ── Call outcome ────────────────────────────────────────────────────────────

/**
 * Result of a Bunpro read. [Unauthorized] is kept distinct from [Failed]
 * because the frontend session token expires with no refresh path: a 401 is
 * the app's only signal that the stored token has gone stale, and it drives
 * `Prefs.bunproTokenRejected` (and the "token expired" settings cell). Folding
 * it into a generic failure would make that state unreachable.
 */
sealed interface BunproResult<out T> {
    data class Ok<T>(val value: T) : BunproResult<T>
    /** 401/403 — the stored token is expired or revoked; prompt for a new one. */
    data object Unauthorized : BunproResult<Nothing>
    /** Anything else: offline, 5xx, parse error. Not the token's fault. */
    data object Failed : BunproResult<Nothing>
}

// ── Derived, UI-facing status ───────────────────────────────────────────────

/**
 * Flattened SRS standing for one item, derived from a [BunproReview].
 *
 * NOTE: a named level bucket (beginner/adept/seasoned/expert/master) is
 * deliberately not derived here — Bunpro's streak→bucket thresholds are not
 * yet known. Add the mapping once confirmed; callers have the raw [streak]
 * and flags in the meantime.
 */
data class BunproSrsStatus(
    val studied: Boolean,
    val streak: Int?,
    val timesStudied: Int?,
    val accuracy: Int?,
    val mastered: Boolean,
    val ghost: Boolean,
    val nextReview: String?,
) {
    companion object {
        val UNSTUDIED = BunproSrsStatus(
            studied = false, streak = null, timesStudied = null,
            accuracy = null, mastered = false, ghost = false, nextReview = null,
        )

        fun from(review: BunproReview?): BunproSrsStatus {
            if (review == null) return UNSTUDIED
            return BunproSrsStatus(
                studied = true,
                streak = review.streak,
                timesStudied = review.timesStudied,
                accuracy = review.accuracy,
                mastered = review.isRecurringMastered == true,
                ghost = (review.ghostCount ?: 0) > 0,
                nextReview = review.nextReview,
            )
        }
    }
}
