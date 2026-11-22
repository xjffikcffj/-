package com.studentlearning.platform2026.domain.model

data class FavoriteStudyFile(
    val fileId: String,
    val sectionId: String = "",
    val title: String,
    val description: String? = null,
    val activeVersionId: String? = null,
    val activeVersionLabel: String? = null,
    val savedAtEpochMillis: Long
)

object LocalFileOrderingHelper {

    fun <T> applyLocalFileOrder(
        items: List<T>,
        idSelector: (T) -> String,
        orderedIds: List<String>
    ): List<T> {
        if (items.size <= 1 || orderedIds.isEmpty()) return items
        val rankById = HashMap<String, Int>(orderedIds.size)
        orderedIds.forEachIndexed { index, rawId ->
            val cleanId = rawId.trim()
            if (cleanId.isNotEmpty() && !rankById.containsKey(cleanId)) {
                rankById[cleanId] = index
            }
        }
        if (rankById.isEmpty()) return items

        val originalIndexByItem = items.withIndex().associate { (index, item) ->
            idSelector(item) to index
        }
        return items.sortedWith(
            compareBy<T>(
                { rankById[idSelector(it)] ?: Int.MAX_VALUE },
                { originalIndexByItem[idSelector(it)] ?: Int.MAX_VALUE }
            )
        )
    }

    fun moveItemInOrderedIds(
        orderedIds: List<String>,
        targetId: String,
        moveUp: Boolean
    ): List<String> {
        val currentIndex = orderedIds.indexOf(targetId)
        if (currentIndex < 0) return orderedIds
        val targetIndex = if (moveUp) currentIndex - 1 else currentIndex + 1
        if (targetIndex !in orderedIds.indices) return orderedIds
        val mutable = orderedIds.toMutableList()
        val swapped = mutable[currentIndex]
        mutable[currentIndex] = mutable[targetIndex]
        mutable[targetIndex] = swapped
        return mutable
    }
}
