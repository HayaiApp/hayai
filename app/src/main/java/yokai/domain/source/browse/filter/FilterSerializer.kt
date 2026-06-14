package yokai.domain.source.browse.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
}
