package de.westnordost.streetcomplete.data.osm.mapdata

import de.westnordost.osmapi.map.data.*
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolygonsGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.p
import org.junit.Assert.*
import org.junit.Test

class MutableMapDataWithGeometryTest {

    @Test fun `put get remove node`() {
        val m = MutableMapDataWithGeometry()
        val p = p(0.0,0.0)
        val node = OsmNode(1L, 1, p, null)
        val geom = ElementPointGeometry(p)

        m.put(node, null)
        assertEquals(node, m.getNode(node.id))
        assertNull(m.getNodeGeometry(node.id))

        m.put(node, geom)
        assertEquals(node, m.getNode(node.id))
        assertEquals(geom, m.getNodeGeometry(node.id))

        m.remove(Element.Type.NODE, node.id)
        assertNull(m.getNode(node.id))
        assertNull(m.getNodeGeometry(node.id))
    }

    @Test fun `put get remove way`() {
        val m = MutableMapDataWithGeometry()
        val p = p(0.0,0.0)
        val way = OsmWay(1L, 1, listOf(), null)
        val geom = ElementPolylinesGeometry(listOf(listOf(p)), p)

        m.put(way, null)
        assertEquals(way, m.getWay(way.id))
        assertNull(m.getWayGeometry(way.id))

        m.put(way, geom)
        assertEquals(way, m.getWay(way.id))
        assertEquals(geom, m.getWayGeometry(way.id))

        m.remove(Element.Type.WAY, way.id)
        assertNull(m.getWay(way.id))
        assertNull(m.getWayGeometry(way.id))
    }

    @Test fun `put get remove relation`() {
        val m = MutableMapDataWithGeometry()
        val p = p(0.0,0.0)
        val rel = OsmRelation(1L, 1, listOf(), null)
        val geom = ElementPolygonsGeometry(listOf(listOf(p)), p)

        m.put(rel, null)
        assertEquals(rel, m.getRelation(rel.id))
        assertNull(m.getRelationGeometry(rel.id))

        m.put(rel, geom)
        assertEquals(rel, m.getRelation(rel.id))
        assertEquals(geom, m.getRelationGeometry(rel.id))
    }
}