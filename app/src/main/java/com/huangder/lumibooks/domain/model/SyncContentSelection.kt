package com.huangder.lumibooks.domain.model

enum class SyncContent { BOOK_FILES, PROFILE_AND_SETTINGS, LIBRARY_ORGANIZATION, READING_DATA }
data class SyncContentSelection(
    val bookFiles: Boolean = true,
    val profileAndSettings: Boolean = true,
    val libraryOrganization: Boolean = true,
    val readingData: Boolean = true
) {
    val hasSelectedContent get() = bookFiles || profileAndSettings || libraryOrganization || readingData
}
