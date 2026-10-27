package com.everyday.shared.sync

/**
 * A minimal decoder for Mapbox Vector Tiles (the protobuf format OpenFreeMap serves): layers,
 * their features' properties, and their geometry in tile coordinates (0..[VectorTileLayer.extent]).
 * Only what the map fallback needs; no dependency.
 */
object VectorTileDecoder {

    /** Decodes [tile] into its layers, keeping only those named in [wanted] (all when null). */
    fun decode(tile: ByteArray, wanted: Set<String>? = null): Map<String, VectorTileLayer> {
        val layers = LinkedHashMap<String, VectorTileLayer>()
        ProtoReader(tile).forEachField { field, reader ->
            if (field == TILE_LAYER) {
                val layer = decodeLayer(reader.bytes(), wanted)
                if (layer != null) layers[layer.name] = layer
            } else reader.skip()
        }
        return layers
    }

    private fun decodeLayer(bytes: ByteArray, wanted: Set<String>?): VectorTileLayer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val features = ArrayList<ByteArray>()
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                LAYER_NAME -> name = reader.string()
                LAYER_FEATURE -> features += reader.bytes()
                LAYER_KEY -> keys += reader.string()
                LAYER_VALUE -> values += decodeValue(reader.bytes())
                LAYER_EXTENT -> extent = reader.varint().toInt()
                else -> reader.skip()
            }
        }
        if (wanted != null && name !in wanted) return null
        return VectorTileLayer(name, extent, features.map { decodeFeature(it, keys, values) })
    }

    private fun decodeFeature(bytes: ByteArray, keys: List<String>, values: List<Any?>): VectorTileFeature {
        var id = 0L
        var type = 0
        val properties = HashMap<String, Any?>()
        var commands = IntArray(0)
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                FEATURE_ID -> id = reader.varint()
                FEATURE_TAGS -> {
                    val tags = reader.packedVarints()
                    for (i in 0 until tags.size - 1 step 2) {
                        val key = keys.getOrNull(tags[i]) ?: continue
                        properties[key] = values.getOrNull(tags[i + 1])
                    }
                }
                FEATURE_TYPE -> type = reader.varint().toInt()
                FEATURE_GEOMETRY -> commands = reader.packedVarints()
                else -> reader.skip()
            }
        }
        return VectorTileFeature(id, type, properties, decodeGeometry(commands))
    }

    /** The geometry commands as lists of points: one list per line, ring, or point group. */
    private fun decodeGeometry(commands: IntArray): List<List<VectorTilePoint>> {
        val parts = ArrayList<List<VectorTilePoint>>()
        var current = ArrayList<VectorTilePoint>()
        var x = 0
        var y = 0
        var i = 0
        while (i < commands.size) {
            val command = commands[i] and 0x7
            val count = commands[i] ushr 3
            i++
            when (command) {
                MOVE_TO, LINE_TO -> repeat(count) {
                    if (i + 1 >= commands.size) return@repeat
                    if (command == MOVE_TO && current.isNotEmpty()) {
                        parts += current
                        current = ArrayList()
                    }
                    x += zigZag(commands[i]); y += zigZag(commands[i + 1]); i += 2
                    current += VectorTilePoint(x, y)
                }
                CLOSE_PATH -> current.firstOrNull()?.let { current += it }
                else -> return parts.also { if (current.isNotEmpty()) it += current }
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    private fun decodeValue(bytes: ByteArray): Any? {
        var value: Any? = null
        ProtoReader(bytes).forEachField { field, reader ->
            value = when (field) {
                VALUE_STRING -> reader.string()
                VALUE_FLOAT -> java.lang.Float.intBitsToFloat(reader.fixed32())
                VALUE_DOUBLE -> java.lang.Double.longBitsToDouble(reader.fixed64())
                VALUE_INT, VALUE_UINT -> reader.varint()
                VALUE_SINT -> reader.varint().let { (it ushr 1) xor -(it and 1) }
                VALUE_BOOL -> reader.varint() != 0L
                else -> { reader.skip(); value }
            }
        }
        return value
    }

    private fun zigZag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private const val TILE_LAYER = 3
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURE = 2
    private const val LAYER_KEY = 3
    private const val LAYER_VALUE = 4
    private const val LAYER_EXTENT = 5
    private const val FEATURE_ID = 1
    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4
    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7
}

data class VectorTileLayer(val name: String, val extent: Int, val features: List<VectorTileFeature>)

/** [type]: 1 point, 2 line, 3 polygon. [parts]: the geometry, in tile coordinates. */
data class VectorTileFeature(
    val id: Long,
    val type: Int,
    val properties: Map<String, Any?>,
    val parts: List<List<VectorTilePoint>>
) {
    fun string(key: String): String? = properties[key] as? String
}

data class VectorTilePoint(val x: Int, val y: Int)

/** Reads protobuf wire format: varints, length-delimited fields, fixed 32/64-bit values. */
private class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val end: Int = bytes.size) {
    private var wireType = 0

    fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (position < end) {
            val key = varint().toInt()
            wireType = key and 0x7
            block(key ushr 3, this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[position++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    fun bytes(): ByteArray {
        val length = varint().toInt()
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun packedVarints(): IntArray {
        val length = varint().toInt()
        val stop = position + length
        val out = ArrayList<Int>()
        while (position < stop) out += varint().toInt()
        return out.toIntArray()
    }

    fun fixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((bytes[position + i].toInt() and 0xff) shl (8 * i))
        position += 4
        return v
    }

    fun fixed64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[position + i].toLong() and 0xff) shl (8 * i))
        position += 8
        return v
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> position += 8
            2 -> position += varint().toInt()
            5 -> position += 4
            else -> throw IllegalStateException("Unsupported protobuf wire type $wireType")
        }
    }
}
