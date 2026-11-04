package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.util.ImportBookMetadata
import com.huangder.lumibooks.util.normalizeImportIsbn
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal enum class ImportReviewReason { READ_FAILED, MULTIPLE_ISBNS, NOT_FOUND, AMBIGUOUS, CONFLICT, INCOMPLETE, BLOCKED, LOOKUP_FAILED }
internal data class LocalBookImportPreparation(
    val state: MyBooksEditUiState,
    val reviewReason: ImportReviewReason? = null,
    val failure: Exception? = null
)

/** Only a verified, unambiguous Douban edition can bypass the information editor. */
internal suspend fun prepareLocalBookImport(
    initial: MyBooksEditUiState,
    readMetadata: suspend () -> ImportBookMetadata,
    lookupIsbn: suspend (String) -> String,
    searchTitle: suspend (String) -> String
): LocalBookImportPreparation {
    var state = initial
    try {
        val metadata = try { readMetadata() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { return LocalBookImportPreparation(state, ImportReviewReason.READ_FAILED, error) }
        val isbns = metadata.isbns.mapNotNull(::normalizeImportIsbn).distinct()
        state = state.copy(title = metadata.title.ifBlank { state.title }, author = metadata.author,
            isbn = isbns.singleOrNull().orEmpty())
        if (isbns.size > 1) return LocalBookImportPreparation(state.copy(lookupMessage = isbns.joinToString("、")), ImportReviewReason.MULTIPLE_ISBNS)
        val isbn = isbns.singleOrNull()
        val candidates = if (isbn != null) {
            val result = JSONObject(lookupIsbn(isbn))
            when (result.optString("status")) {
                "blocked" -> return LocalBookImportPreparation(state, ImportReviewReason.BLOCKED)
                "not_found" -> return LocalBookImportPreparation(state, ImportReviewReason.NOT_FOUND)
                "found" -> Unit
                else -> return LocalBookImportPreparation(state, ImportReviewReason.LOOKUP_FAILED)
            }
            listOfNotNull(parseMyBooksCandidate(result.optJSONObject("book")))
        } else {
            val result = JSONObject(searchTitle(state.title))
            val results = result.optJSONArray("results")
            buildList { if (results != null) for (i in 0 until results.length()) parseMyBooksCandidate(results.optJSONObject(i))?.let(::add) }
        }
        if (candidates.isEmpty()) return LocalBookImportPreparation(state, ImportReviewReason.NOT_FOUND)
        state = state.copy(candidates = candidates)
        if (candidates.size != 1) return LocalBookImportPreparation(state, ImportReviewReason.AMBIGUOUS)
        val candidate = candidates.single()
        val candidateIsbn = normalizeImportIsbn(candidate.isbn)
        if (isbn != null && candidateIsbn != null && isbn != candidateIsbn) return LocalBookImportPreparation(state, ImportReviewReason.CONFLICT)
        if (isbn == null && normalizedImportTitle(state.title) != normalizedImportTitle(candidate.title)) return LocalBookImportPreparation(state, ImportReviewReason.CONFLICT)
        if (isbn == null && state.author.isNotBlank() && normalizedImportTitle(state.author) != normalizedImportTitle(candidate.author)) return LocalBookImportPreparation(state, ImportReviewReason.CONFLICT)
        if (candidate.doubanId.isBlank() || candidateIsbn == null || candidate.author.isBlank()) return LocalBookImportPreparation(state, ImportReviewReason.INCOMPLETE)
        state = applyMyBooksCandidate(state, candidate.copy(isbn = candidateIsbn), "")
        return LocalBookImportPreparation(state)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { return LocalBookImportPreparation(state, ImportReviewReason.LOOKUP_FAILED, error) }
}

private fun normalizedImportTitle(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
