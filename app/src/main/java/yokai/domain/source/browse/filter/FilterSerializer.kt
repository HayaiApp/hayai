package yokai.domain.source.browse.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class FilterSerializer {
    private val serializers = listOf<Serializer<*>>(
        HeaderSerializer(this),
        SeparatorSerializer(this),
        SelectSerializer(this),
        TextSerializer(this),
        CheckBoxSerializer(this),
        TriStateSerializer(this),
        GroupSerializer(this),
        SortSerializer(this),
    )

    fun serialize(filters: FilterList) = buildJsonArray {
        filters.filterIsInstance<Filter<Any?>>().forEach { add(serialize(it)) }
    }

    /**
     * Stable saved-search format. Unlike the legacy positional array this includes recursive index
     * paths, identity breadcrumbs, autocomplete values, and option labels for safe fallback when
     * an extension reorders its filters.
     */
    fun serializeV2(filters: FilterList): JsonObject = buildJsonObject {
        put(VERSION, CURRENT_VERSION)
        putJsonArray(ENTRIES) {
            fun visit(children: List<*>, parent: FilterPath, names: List<String>, kinds: List<FilterKind>) {
                children.forEachIndexed { index, candidate ->
                    val filter = candidate as? Filter<*> ?: return@forEachIndexed
                    val path = parent.child(index)
                    val nextNames = names + filter.name
                    val nextKinds = kinds + filter.kind()
                    if (filter is Filter.Group<*>) {
                        visit(filter.state, path, nextNames, nextKinds)
                    } else if (filter !is Filter.Header && filter !is Filter.Separator) {
                        add(
                            buildJsonObject {
                                putJsonArray(PATH) { path.indices.forEach { add(it) } }
                                putJsonArray(NAMES) { nextNames.forEach { add(it) } }
                                putJsonArray(KINDS) { nextKinds.forEach { add(it.name) } }
                                put(KIND, filter.kind().name)
                                put(VALUE, encodeValue(FilterTree.capture(filter).value))
                            },
                        )
                    }
                }
            }
            visit(filters, FilterPath.Root, emptyList(), emptyList())
        }
    }

    /** Applies as many compatible V2 entries as possible, leaving all other source defaults. */
    fun deserializeV2(filters: FilterList, json: JsonObject) {
        if (json[VERSION]?.jsonPrimitive?.intOrNull != CURRENT_VERSION) return
        val candidates = indexedCandidates(filters)
        json[ENTRIES]?.jsonArray?.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val kind = entry[KIND]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { FilterKind.valueOf(it) }.getOrNull() }
                ?: return@forEach
            val path = entry[PATH]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?.let(::FilterPath)
                ?: return@forEach
            val names = entry[NAMES]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val kinds = entry[KINDS]?.jsonArray?.mapNotNull {
                it.jsonPrimitive.contentOrNull?.let { raw -> runCatching { FilterKind.valueOf(raw) }.getOrNull() }
            }.orEmpty()

            val exact = candidates.firstOrNull {
                it.path == path && it.filter.kind() == kind && it.names == names && it.kinds == kinds
            }?.filter
            val target = exact ?: candidates
                .filter { it.filter.kind() == kind && it.names == names && it.kinds == kinds }
                .singleOrNull()
                ?.filter
                ?: return@forEach
            val value = decodeValue(kind, entry[VALUE]) ?: return@forEach
            FilterTree.apply(FilterNodeSnapshot(target.name, kind, value), target)
        }
    }

    fun serialize(filter: Filter<Any?>): JsonObject {
        return serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull { filter::class.isSubclassOf(it.clazz) }
            ?.let { serializer ->
                buildJsonObject {
                    with(serializer) { serialize(filter) }

                    serializer.mappings().forEach {
                        val res = it.second.get(filter)
                        putJsonObject(it.first) {
                            put(Serializer.TYPE, res?.javaClass?.name ?: "null")
                            put("value", res.toString())
                        }
                    }

                    put(Serializer.TYPE, serializer.type)
                }
            } ?: throw IllegalArgumentException("Cannot serialize this Filter object!")
    }

    fun deserialize(filters: FilterList, json: JsonArray) {
        filters.filterIsInstance<Filter<Any?>>().zip(json).forEach { (filter, obj) ->
            deserialize(filter, obj.jsonObject)
        }
    }

    fun deserialize(filter: Filter<Any?>, json: JsonObject) {
        val serializer = serializers
            .filterIsInstance<Serializer<Filter<Any?>>>()
            .firstOrNull { it.type == json[Serializer.TYPE]!!.jsonPrimitive.content }
            ?: throw IllegalArgumentException("Cannot deserialize this type!")

        serializer.deserialize(json, filter)

        serializer.mappings().forEach {
            if (it.second is KMutableProperty1) {
                val valueObj = json[it.first]!!.jsonObject
                val obj = valueObj["value"]!!.jsonPrimitive
                val res: Any? = when (valueObj[Serializer.TYPE]!!.jsonPrimitive.content) {
                    Int::class.java.name, "java.lang.Integer" -> obj.int
                    Long::class.java.name, "java.lang.Long" -> obj.long
                    Float::class.java.name, "java.lang.Float" -> obj.float
                    Double::class.java.name, "java.lang.Double" -> obj.double
                    String::class.java.name, "java.lang.String" -> obj.content
                    Boolean::class.java.name, "java.lang.Boolean" -> obj.boolean
                    Byte::class.java.name, "java.lang.Byte" -> obj.content.toByte()
                    Short::class.java.name, "java.lang.Short" -> obj.content.toShort()
                    Char::class.java.name, "java.lang.Character" -> obj.content[0]
                    "null" -> null
                    else -> throw IllegalArgumentException("Cannot deserialize this type!")
                }
                @Suppress("UNCHECKED_CAST")
                (it.second as KMutableProperty1<in Filter<Any?>, in Any?>).set(filter, res)
            }
        }
    }

    private data class IndexedCandidate(
        val path: FilterPath,
        val filter: Filter<*>,
        val names: List<String>,
        val kinds: List<FilterKind>,
    )

    private fun indexedCandidates(filters: FilterList): List<IndexedCandidate> = buildList {
        fun visit(
            children: List<*>,
            parent: FilterPath,
            names: List<String>,
            kinds: List<FilterKind>,
        ) {
            children.forEachIndexed { index, candidate ->
                val filter = candidate as? Filter<*> ?: return@forEachIndexed
                val path = parent.child(index)
                val nextNames = names + filter.name
                val nextKinds = kinds + filter.kind()
                add(IndexedCandidate(path, filter, nextNames, nextKinds))
                if (filter is Filter.Group<*>) visit(filter.state, path, nextNames, nextKinds)
            }
        }
        visit(filters, FilterPath.Root, emptyList(), emptyList())
    }

    private fun encodeValue(value: FilterValueSnapshot): JsonElement = when (value) {
        FilterValueSnapshot.Stateless -> JsonNull
        is FilterValueSnapshot.Index -> buildJsonObject {
            put(INDEX, value.index)
            value.label?.let { put(LABEL, it) }
        }
        is FilterValueSnapshot.Text -> buildJsonObject { put(TEXT, value.value) }
        is FilterValueSnapshot.Checked -> buildJsonObject { put(CHECKED, value.value) }
        is FilterValueSnapshot.TriState -> buildJsonObject { put(STATE, value.value) }
        is FilterValueSnapshot.AutoComplete -> buildJsonObject {
            putJsonArray(VALUES) { value.values.forEach { add(it) } }
        }
        is FilterValueSnapshot.Sort -> buildJsonObject {
            if (value.index != null) put(INDEX, value.index) else put(INDEX, JsonNull)
            if (value.ascending != null) put(ASCENDING, value.ascending) else put(ASCENDING, JsonNull)
            value.label?.let { put(LABEL, it) }
        }
        is FilterValueSnapshot.Group -> JsonNull
    }

    private fun decodeValue(kind: FilterKind, element: JsonElement?): FilterValueSnapshot? {
        val value = element as? JsonObject ?: return null
        return when (kind) {
            FilterKind.SELECT -> FilterValueSnapshot.Index(
                index = value[INDEX]?.jsonPrimitive?.intOrNull ?: return null,
                label = value[LABEL]?.jsonPrimitive?.contentOrNull,
            )
            FilterKind.TEXT -> FilterValueSnapshot.Text(value[TEXT]?.jsonPrimitive?.contentOrNull ?: return null)
            FilterKind.CHECKBOX -> FilterValueSnapshot.Checked(value[CHECKED]?.jsonPrimitive?.booleanOrNull ?: return null)
            FilterKind.TRI_STATE -> FilterValueSnapshot.TriState(value[STATE]?.jsonPrimitive?.intOrNull ?: return null)
            FilterKind.AUTO_COMPLETE -> FilterValueSnapshot.AutoComplete(
                value[VALUES]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            FilterKind.SORT -> FilterValueSnapshot.Sort(
                index = value[INDEX]?.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull },
                ascending = value[ASCENDING]?.let { if (it is JsonNull) null else it.jsonPrimitive.booleanOrNull },
                label = value[LABEL]?.jsonPrimitive?.contentOrNull,
            )
            else -> null
        }
    }

    companion object {
        private const val CURRENT_VERSION = 2
        private const val VERSION = "version"
        private const val ENTRIES = "entries"
        private const val PATH = "path"
        private const val NAMES = "names"
        private const val KINDS = "kinds"
        private const val KIND = "kind"
        private const val VALUE = "value"
        private const val INDEX = "index"
        private const val LABEL = "label"
        private const val TEXT = "text"
        private const val CHECKED = "checked"
        private const val STATE = "state"
        private const val VALUES = "values"
        private const val ASCENDING = "ascending"
    }
}
