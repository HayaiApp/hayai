package dev.ahmedmohamed.hayai.novel.reader

internal class NovelChapterQueue<T, K>(
    private val keyOf: (T) -> K,
    capacity: Int,
) {
    private val items = ArrayDeque<T>()
    var capacity: Int = capacity.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
            trim()
        }

    var currentKey: K? = null
        private set

    fun replaceCurrent(item: T) {
        val key = keyOf(item)
        val existing = items.indexOfFirst { keyOf(it) == key }
        if (existing >= 0) items.removeAt(existing)
        items.addLast(item)
        currentKey = key
        trim()
    }

    fun append(item: T) {
        val key = keyOf(item)
        if (items.none { keyOf(it) == key }) items.addLast(item)
        trim()
    }

    fun prepend(item: T) {
        val key = keyOf(item)
        if (items.none { keyOf(it) == key }) items.addFirst(item)
        trim()
    }

    fun focus(key: K): Boolean {
        if (items.none { keyOf(it) == key }) return false
        currentKey = key
        trim()
        return true
    }

    fun snapshot(): List<T> = items.toList()

    fun find(key: K): T? = items.firstOrNull { keyOf(it) == key }

    fun keys(): Set<K> = items.mapTo(linkedSetOf(), keyOf)

    fun retain(keys: Set<K>) {
        items.removeAll { keyOf(it) !in keys }
        if (currentKey !in keys) currentKey = null
    }

    fun clear() {
        items.clear()
        currentKey = null
    }

    private fun trim() {
        while (items.size > capacity) {
            val currentIndex = items.indexOfFirst { keyOf(it) == currentKey }
            when {
                currentIndex < 0 -> items.removeFirst()
                currentIndex > items.lastIndex - currentIndex -> items.removeFirst()
                else -> items.removeLast()
            }
        }
    }
}
