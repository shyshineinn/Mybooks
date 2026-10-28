package com.huangder.lumibooks.domain.model

/** Both sync backends use the same local-first/cloud-first semantics selected by the user. */
suspend fun executeBookDeletion(
    mode: BookDeleteMode,
    deleteLocal: suspend () -> List<String>,
    deleteCloud: suspend () -> List<String>
): List<String> = when (mode) {
    BookDeleteMode.LOCAL_ONLY -> deleteLocal()
    BookDeleteMode.LOCAL_AND_CLOUD -> deleteCloud().let { errors -> if (errors.isEmpty()) deleteLocal() else errors }
    BookDeleteMode.FORCE_LOCAL_AND_CLOUD -> deleteLocal() + deleteCloud()
}
