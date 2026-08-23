package dev.ahmedmohamed.hayai.preferences

import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.fredporciuncula.flow.preferences.Preference
import com.fredporciuncula.flow.preferences.Serializer

/**
 * Decodes persisted enum names without relying on [java.lang.Enum.valueOf]. Fork upgrades can
 * legitimately remove an enum member while the old value remains in SharedPreferences.
 */
internal class CompatibleEnumSerializer<T : Enum<T>>(
    values: Array<T>,
    private val defaultValue: T,
    private val legacyAliases: Map<String, T> = emptyMap(),
) : Serializer<T> {
    private val valuesByName = values.associateBy { it.name }

    override fun serialize(value: T): String = value.name

    override fun deserialize(serialized: String): T =
        valuesByName[serialized] ?: legacyAliases[serialized] ?: defaultValue
}

internal inline fun <reified T : Enum<T>> FlowSharedPreferences.getCompatibleEnum(
    key: String,
    defaultValue: T,
    legacyAliases: Map<String, T> = emptyMap(),
): Preference<T> =
    getObject(
        key,
        CompatibleEnumSerializer(enumValues(), defaultValue, legacyAliases),
        defaultValue,
    )
