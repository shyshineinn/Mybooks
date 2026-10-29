package com.huangder.lumibooks.data.backup

import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.local.entity.TagEntity

internal data class PortableTagMerge(
    val tags: List<TagEntity>,
    val links: List<BookTagCrossRefEntity>
)

/** Independent devices can create different IDs for the same unique normalized name. */
internal fun mergePortableTags(tags: List<TagEntity>, links: List<BookTagCrossRefEntity>): PortableTagMerge {
    val latest = tags.groupBy { it.id }.values.map { rows ->
        rows.maxWith(compareBy<TagEntity> { it.updatedAt }.thenBy { it.toString() })
    }
    val aliases = mutableMapOf<String, String>()
    val canonical = latest.groupBy { it.normalizedName }.values.map { rows ->
        val winner = rows.maxWith(compareBy<TagEntity> { it.updatedAt }.thenBy { it.id })
        rows.forEach { aliases[it.id] = winner.id }
        winner
    }
    return PortableTagMerge(
        tags = canonical.map { tag -> tag.copy(parentId = tag.parentId?.let(aliases::get)?.takeUnless { it == tag.id }) },
        links = links.mapNotNull { link -> aliases[link.tagId]?.let { link.copy(tagId = it) } }
            .groupBy { it.bookId to it.tagId }.values.map { rows -> rows.maxBy { it.updatedAt } }
    )
}