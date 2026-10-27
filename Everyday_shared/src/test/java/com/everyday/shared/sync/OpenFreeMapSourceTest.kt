package com.everyday.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class OpenFreeMapSourceTest {
    @Test
    fun `decodes layers, properties and geometry of a vector tile`() {
        val tile = tile(
            layer(
                "poi",
                keys = listOf("class", "subclass", "name"),
                values = listOf(stringValue("grocery"), stringValue("supermarket"), stringValue("Corner Shop")),
                features = listOf(feature(id = 7, type = 1, tags = listOf(0, 0, 1, 1, 2, 2), geometry = listOf(command(1, 1), zigZag(2048), zigZag(1024))))
            ),
            layer(
                "transportation",
                keys = listOf("class"),
                values = listOf(stringValue("minor")),
                features = listOf(
                    feature(
                        id = 9, type = 2, tags = listOf(0, 0),
                        geometry = listOf(command(1, 1), zigZag(10), zigZag(20), command(2, 2), zigZag(5), zigZag(0), zigZag(0), zigZag(-5))
                    )
                )
            ),
            layer("water", keys = emptyList(), values = emptyList(), features = emptyList())
        )

        val layers = VectorTileDecoder.decode(tile, setOf("poi", "transportation"))

        assertEquals(setOf("poi", "transportation"), layers.keys)
        val shop = layers.getValue("poi").features.single()
        assertEquals(7L, shop.id)
        assertEquals("supermarket", shop.string("subclass"))
        assertEquals("Corner Shop", shop.string("name"))
        assertEquals(listOf(listOf(VectorTilePoint(2048, 1024))), shop.parts)
        val road = layers.getValue("transportation").features.single()
        assertEquals(listOf(listOf(VectorTilePoint(10, 20), VectorTilePoint(15, 20), VectorTilePoint(15, 15))), road.parts)
    }

    @Test
    fun `tile places become the app's categories`() {
        assertEquals("railway=station", OpenFreeMapSource.osmCategory("railway", "station"))
        assertNull(OpenFreeMapSource.osmCategory("railway", "tram_stop"))
        assertEquals("shop=supermarket", OpenFreeMapSource.osmCategory("grocery", "supermarket"))
        assertEquals("amenity=place_of_worship", OpenFreeMapSource.osmCategory("place_of_worship", "christian"))
        assertEquals("amenity=cafe", OpenFreeMapSource.osmCategory("cafe", "cafe"))
        assertEquals("amenity=university", OpenFreeMapSource.osmCategory("college", "university"))
        assertEquals("shop=books", OpenFreeMapSource.osmCategory("library", "books"))
        assertEquals("amenity=library", OpenFreeMapSource.osmCategory("library", "library"))
        assertEquals("tourism=museum", OpenFreeMapSource.osmCategory("museum", "museum"))
        assertEquals("historic=castle", OpenFreeMapSource.osmCategory("castle", "castle"))
        assertEquals("shop=clothes", OpenFreeMapSource.osmCategory("clothing_store", "clothes"))
        assertNull(OpenFreeMapSource.osmCategory("parking", "parking"))
        assertNull(OpenFreeMapSource.osmCategory("fuel", "charging_station"))
        assertNull(OpenFreeMapSource.osmCategory("bus", "bus_stop"))

        assertEquals("school", OpenFreeMapSource.categoryId("amenity=university"))
        assertEquals("supermarket", OpenFreeMapSource.categoryId("shop=supermarket"))
        assertEquals("shop", OpenFreeMapSource.categoryId("shop=books"))
        assertEquals("station", OpenFreeMapSource.categoryId("railway=station"))
    }

    @Test
    fun `tile road classes become highway values`() {
        assertEquals("primary", OpenFreeMapSource.highwayOf("primary", null))
        assertEquals("residential", OpenFreeMapSource.highwayOf("minor", null))
        assertEquals("footway", OpenFreeMapSource.highwayOf("path", "footway"))
        assertEquals("path", OpenFreeMapSource.highwayOf("path", null))
        assertNull(OpenFreeMapSource.highwayOf("path", "corridor"))
        assertNull(OpenFreeMapSource.highwayOf("rail", "rail"))
        assertNull(OpenFreeMapSource.highwayOf("minor_construction", null))
    }

    // ==================== A tiny vector tile encoder ====================

    private fun tile(vararg layers: ByteArray) = message { layers.forEach { bytesField(3, it) } }

    private fun layer(name: String, keys: List<String>, values: List<ByteArray>, features: List<ByteArray>) = message {
        varintField(15, 2)
        bytesField(1, name.toByteArray())
        features.forEach { bytesField(2, it) }
        keys.forEach { bytesField(3, it.toByteArray()) }
        values.forEach { bytesField(4, it) }
        varintField(5, 4096)
    }

    private fun feature(id: Long, type: Int, tags: List<Int>, geometry: List<Int>) = message {
        varintField(1, id)
        packedField(2, tags)
        varintField(3, type.toLong())
        packedField(4, geometry)
    }

    private fun stringValue(text: String) = message { bytesField(1, text.toByteArray()) }
    private fun command(id: Int, count: Int) = (count shl 3) or id
    private fun zigZag(n: Int) = (n shl 1) xor (n shr 31)

    private class Writer {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun varintField(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
        fun bytesField(field: Int, bytes: ByteArray) { varint(((field shl 3) or 2).toLong()); varint(bytes.size.toLong()); out.write(bytes) }
        fun packedField(field: Int, values: List<Int>) {
            val packed = Writer().apply { values.forEach { varint(it.toLong()) } }.out.toByteArray()
            bytesField(field, packed)
        }
    }

    private fun message(block: Writer.() -> Unit): ByteArray = Writer().apply(block).out.toByteArray()
}
