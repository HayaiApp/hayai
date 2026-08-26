package dev.ahmedmohamed.hayai.testing

import eu.kanade.tachiyomi.data.preference.Preference
import eu.kanade.tachiyomi.data.preference.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MemoryPreferenceStore(
    vararg initial: Pair<String, Any>,
) : PreferenceStore {
    private val values = initial.toMap().toMutableMap()
    private val stringPreferences = mutableMapOf<String, MemoryPreference<String>>()
    private val longPreferences = mutableMapOf<String, MemoryPreference<Long>>()
    private val intPreferences = mutableMapOf<String, MemoryPreference<Int>>()
    private val floatPreferences = mutableMapOf<String, MemoryPreference<Float>>()
    private val booleanPreferences = mutableMapOf<String, MemoryPreference<Boolean>>()
    private val stringSetPreferences = mutableMapOf<String, MemoryPreference<Set<String>>>()

    override fun getString(key: String, defaultValue: String): Preference<String> =
        preference(stringPreferences, key, defaultValue) { it as? String }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        preference(longPreferences, key, defaultValue) { it as? Long }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        preference(intPreferences, key, defaultValue) { it as? Int }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        preference(floatPreferences, key, defaultValue) { it as? Float }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        preference(booleanPreferences, key, defaultValue) { it as? Boolean }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        preference(stringSetPreferences, key, defaultValue) { value ->
            (value as? Set<*>)
                ?.takeIf { elements -> elements.all { it is String } }
                ?.map { it as String }
                ?.toSet()
        }

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> =
        SerializedPreference(
            delegate = getString(key, serializer(defaultValue)),
            defaultValue = defaultValue,
            serializer = serializer,
            deserializer = deserializer,
        )

    override fun getAll(): Map<String, *> = values.toMap()

    private fun <T : Any> preference(
        preferences: MutableMap<String, MemoryPreference<T>>,
        key: String,
        defaultValue: T,
        decode: (Any?) -> T?,
    ): Preference<T> =
        preferences.getOrPut(key) {
            MemoryPreference(
                key = key,
                defaultValue = defaultValue,
                read = { decode(values[key]) ?: defaultValue },
                write = { values[key] = it },
                remove = { values.remove(key) },
                contains = { key in values },
            )
        }
}

private class SerializedPreference<T>(
    private val delegate: Preference<String>,
    private val defaultValue: T,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T,
) : Preference<T> {
    override fun key(): String = delegate.key()
    override fun get(): T = deserializer(delegate.get())
    override fun set(value: T) = delegate.set(serializer(value))
    override fun isSet(): Boolean = delegate.isSet()
    override fun delete() = delegate.delete()
    override fun defaultValue(): T = defaultValue
    override fun changes(): Flow<T> = delegate.changes().map(deserializer)
    override fun stateIn(scope: CoroutineScope): StateFlow<T> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}

private class MemoryPreference<T>(
    private val key: String,
    private val defaultValue: T,
    private val read: () -> T,
    private val write: (T) -> Unit,
    private val remove: () -> Unit,
    private val contains: () -> Boolean,
) : Preference<T> {
    private val state = MutableStateFlow(read())

    override fun key(): String = key
    override fun get(): T = read()
    override fun set(value: T) {
        write(value)
        state.value = value
    }
    override fun isSet(): Boolean = contains()
    override fun delete() {
        remove()
        state.value = defaultValue
    }
    override fun defaultValue(): T = defaultValue
    override fun changes(): Flow<T> = state
    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
}
