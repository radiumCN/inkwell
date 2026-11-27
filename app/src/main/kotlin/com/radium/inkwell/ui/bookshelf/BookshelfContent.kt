package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity

/** 首屏用同一份书架数据决定筛选栏、书籍位置和继续阅读卡。 */
internal data class BookshelfContent(
    val allBooks: List<BookEntity>,
    val books: List<BookEntity>,
    val groups: List<String>,
    val group: String?,
    val allHidden: Boolean,
    val continuingBook: BookEntity?,
) {
    val booksBelowContinueCard = if (continuingBook == null) books else books.filter { it.id != continuingBook.id }
}

internal fun bookshelfContent(
    allBooks: List<BookEntity>,
    group: String?,
    showHidden: Boolean,
    frozenOrder: List<String>?,
): BookshelfContent {
    val accessible = if (showHidden) allBooks else allBooks.filterNot { it.hidden }
    val groups = accessible.asSequence()
        .map { it.groupName.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .toList()
    // 分组删除或收起隐藏区后也在同一帧回到「全部」，避免中间露出一个空分组。
    val activeGroup = group?.takeIf { it == BookshelfViewModel.UNGROUPED || it in groups }
    val visible = if (activeGroup == null) accessible else accessible.filter { it.inBookshelfGroup(activeGroup) }
    val ordered = if (frozenOrder == null) visible else {
        val rank = HashMap<String, Int>(frozenOrder.size * 2)
        frozenOrder.forEachIndexed { index, id -> rank[id] = index }
        visible.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }
    return BookshelfContent(
        allBooks = allBooks,
        books = ordered,
        groups = groups,
        group = activeGroup,
        allHidden = allBooks.isNotEmpty() && accessible.isEmpty(),
        // 用冻结后的顺序决定卡片，关书动画仍能缩回原来点开的书位。
        continuingBook = if (activeGroup == null) ordered.firstOrNull { it.lastActiveAt() > 0L } else null,
    )
}

internal fun BookEntity.inBookshelfGroup(group: String?): Boolean = when (group) {
    null -> true
    BookshelfViewModel.UNGROUPED -> groupName.isBlank()
    else -> groupName.trim() == group
}
