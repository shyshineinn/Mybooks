package com.huangder.lumibooks.ui.bookshelf

/** 编辑表单的交互边界，导入与编辑共用。 */
interface MyBooksEditActions {
    fun updateTitle(value: String)
    fun updateAuthor(value: String)
    fun updateTranslators(value: String)
    fun updateIsbn(value: String)
    fun updateSeries(value: String)
    fun updateSeriesIndex(value: String)
    fun updateSummary(value: String)
    fun updateIdentifier(index: Int, type: String? = null, value: String? = null)
    fun addIdentifier()
    fun removeIdentifier(index: Int)
    fun updateCustom(label: String, value: String)
    fun updateReadLog(index: Int, start: String? = null, finish: String? = null)
    fun addReadLog()
    fun removeReadLog(index: Int)
    fun lookupDouban()
    fun searchByTitle()
    fun applyCandidate(candidate: MyBooksDoubanCandidate)
}
