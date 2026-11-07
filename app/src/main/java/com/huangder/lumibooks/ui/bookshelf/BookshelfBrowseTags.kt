package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.domain.model.BookTagLink
import com.huangder.lumibooks.domain.model.LibraryTag
import com.huangder.lumibooks.domain.model.TagNameValidator

/** Categories join the browsing view. The original tag lists remain the editable associations. */
internal fun bookshelfBrowseTags(
    tags: List<LibraryTag>,
    links: List<BookTagLink>,
    categoriesByBook: Map<String, String>
): Pair<List<LibraryTag>, List<BookTagLink>> {
    if (categoriesByBook.isEmpty()) return tags to links
    val byName = tags.filter { it.parentId == null }.associateBy { TagNameValidator.normalized(it.name) }.toMutableMap()
    val browseTags = tags.toMutableList()
    val browseLinks = links.toMutableList()
    val associations = links.mapTo(mutableSetOf()) { it.bookId to it.tagId }
    for ((bookId, category) in categoriesByBook) {
        val key = TagNameValidator.normalized(category)
        val tag = byName.getOrPut(key) {
            LibraryTag("mybooks-category:$key", category, 0L).also(browseTags::add)
        }
        if (associations.add(bookId to tag.id)) browseLinks += BookTagLink(bookId, tag.id)
    }
    return browseTags to browseLinks
}
