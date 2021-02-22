package de.westnordost.streetcomplete.data.osm.changes

import de.westnordost.osmapi.map.MapData
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.osmapi.map.MutableMapData
import de.westnordost.osmapi.map.data.*
import de.westnordost.osmapi.map.data.Element.Type.*
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryCreator
import de.westnordost.streetcomplete.data.osm.mapdata.*
import de.westnordost.streetcomplete.data.osm.upload.ElementConflictException
import de.westnordost.streetcomplete.util.intersect
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

// TODO TEST

/** Source for map data. It combines the original data downloaded with the edits made.
 *
 *  This class is threadsafe.
 * */
@Singleton class MapDataWithEditsSource @Inject internal constructor(
    private val mapDataController: MapDataController,
    private val elementEditsController: ElementEditsController,
    private val elementGeometryCreator: ElementGeometryCreator
) : MapDataRepository {

    /** Interface to be notified of new or updated OSM elements */
    interface Listener {
        /** Called when a number of elements have been updated or deleted */
        fun onUpdated(updated: MapDataWithGeometry, deleted: Collection<ElementKey>)

        /** Called when all elements in the given bounding box should be replaced with the elements
         *  in the mapDataWithGeometry */
        fun onReplacedForBBox(bbox: BoundingBox, mapDataWithGeometry: MapDataWithGeometry)
    }
    private val listeners: MutableList<Listener> = CopyOnWriteArrayList()

    /* For thread-safety, all access to these three fields is synchronized. Since there is no hell
     * of parallelism, simply any method that somehow accesses these fields (~just about any method
     * in this class) is marked synchronized */
    private val deletedElements = HashSet<ElementKey>()
    private val updatedElements = HashMap<ElementKey, Element>()
    private val updatedGeometries = HashMap<ElementKey, ElementGeometry>()

    private val mapDataListener = object : MapDataController.Listener {

        @Synchronized override fun onUpdated(updated: MutableMapDataWithGeometry, deleted: Collection<ElementKey>) {
            rebuildLocalChanges()

            val modifiedElements = ArrayList<Pair<Element, ElementGeometry?>>()
            val modifiedDeleted = ArrayList<ElementKey>()
            for (element in updated) {
                val key = ElementKey(element.type, element.id)
                if (deletedElements.contains(key)) {
                    modifiedDeleted.add(key)
                } else {
                    val modifiedElement = updatedElements[key] ?: element
                    val modifiedGeometry = updatedGeometries[key] ?: updated.getGeometry(key.elementType, key.elementId)
                    modifiedElements.add(Pair(modifiedElement, modifiedGeometry))
                }
            }
            for ((element, geometry) in modifiedElements) {
                updated.put(element, geometry)
            }

            for (key in deleted) {
                if (!updatedElements.containsKey(key)) {
                    modifiedDeleted.add(key)
                }
            }
            callOnUpdated(updated = updated, deleted = modifiedDeleted)
        }

        @Synchronized override fun onReplacedForBBox(bbox: BoundingBox, mapDataWithGeometry: MutableMapDataWithGeometry) {
            rebuildLocalChanges()

            modifyBBoxMapData(bbox, mapDataWithGeometry)
            callOnReplacedForBBox(bbox, mapDataWithGeometry)
        }
    }

    private val elementEditsListener = object : ElementEditsSource.Listener {
        @Synchronized override fun onAddedEdit(edit: ElementEdit) {
            val elements = applyEdit(edit)
            if (elements.isEmpty()) return

            val elementsToDelete = ArrayList<ElementKey>()
            val mapData = MutableMapDataWithGeometry()
            for (element in elements) {
                if (element.isDeleted) {
                    elementsToDelete.add(ElementKey(element.type, element.id))
                } else {
                    mapData.put(element, getGeometry(edit.elementType, edit.elementId))
                }
            }

            callOnUpdated(updated = mapData, deleted = elementsToDelete)
        }

        override fun onSyncedEdit(edit: ElementEdit) {
            /* do nothing: If the change was synced successfully, it means that our local change
               was accepted by the server. There will also be a call to onUpdated
               in MapDataSource.Listener any moment now */
        }

        @Synchronized override fun onDeletedEdit(edit: ElementEdit) {
            rebuildLocalChanges()
            /* the elements that were created by the given edit must be deleted, however, some
               other edit might have created them instead, so only delete those that are not in
               updated elements */
            val elementsToDelete = getCreatedElementKeys(edit).toMutableList()
            elementsToDelete.removeAll(updatedElements.keys)

            val mapData = MutableMapDataWithGeometry()
            val element = get(edit.elementType, edit.elementId)
            if (element != null) {
                mapData.put(element, getGeometry(edit.elementType, edit.elementId))
            } else {
                // element that got edited by the deleted edit not found? Hmm, okay then
                elementsToDelete.add(ElementKey(edit.elementType, edit.elementId))
            }

            callOnUpdated(updated = mapData, deleted = elementsToDelete)
        }
    }

    init {
        mapDataController.addListener(mapDataListener)
        elementEditsController.addListener(elementEditsListener)
    }

    @Synchronized fun get(type: Element.Type, id: Long): Element? {
        val key = ElementKey(type, id)
        if (deletedElements.contains(key)) return null

        return updatedElements[key] ?: mapDataController.get(type, id)
    }

    @Synchronized fun getGeometry(type: Element.Type, id: Long): ElementGeometry? {
        val key = ElementKey(type, id)
        if (deletedElements.contains(key)) return null

        return updatedGeometries[key] ?: mapDataController.getGeometry(type, id)
    }

    @Synchronized fun getMapDataWithGeometry(bbox: BoundingBox): MapDataWithGeometry {
        val mapDataWithGeometry = mapDataController.getMapDataWithGeometry(bbox)
        modifyBBoxMapData(bbox, mapDataWithGeometry)
        return mapDataWithGeometry
    }

    /* ----------------------------------- MapDataRepository ------------------------------------ */

    override fun getNode(id: Long): Node? = get(NODE, id) as? Node
    override fun getWay(id: Long): Way? = get(WAY, id) as? Way
    override fun getRelation(id: Long): Relation? = get(RELATION, id) as? Relation

    @Synchronized override fun getWayComplete(id: Long): MapData? {
        val way = getWay(id) ?: return null
        val nodeIds = way.nodeIds.toSet()
        val nodes = mapDataController.getNodes(nodeIds)

        val nodesById = HashMap<Long, Node>()
        nodes.associateByTo(nodesById) { it.id }

        for (element in updatedElements.values) {
            if (element is Node) {
                // if a node is part of the way, put the updated node into the map
                if (nodeIds.contains(element.id)) {
                    nodesById[element.id] = element
                }
            }
        }
        for (key in deletedElements) {
            if (key.elementType == NODE) {
                nodesById.remove(key.elementId)
            }
        }

        /* If the way is (now) not complete, this is not acceptable */
        if (nodesById.size < nodeIds.size) return null

        val mapData = MutableMapData(listOf(way))
        mapData.addAll(nodesById.values)

        return mapData
    }

    @Synchronized override fun getRelationComplete(id: Long): MapData? {
        val relation = getRelation(id) ?: return null
        val referredElementKeys = relation.members.map { ElementKey(it.type, it.ref) }.toSet()
        val referredElements = mapDataController.getAll(referredElementKeys)

        val referredElementsByKey = HashMap<ElementKey, Element>()
        referredElements.associateByTo(referredElementsByKey) { ElementKey(it.type, it.id) }

        for (element in updatedElements.values) {
            val key = ElementKey(element.type, element.id)
            // if an element is part of the relation, put the updated one into the map
            if (referredElementKeys.contains(key)) {
                referredElementsByKey[key] = element
            }
        }
        for (key in deletedElements) {
            referredElementsByKey.remove(key)
        }

        /* Even though the function name says "complete", it is acceptable for relations if after
         *  all, not all members are included */

        val mapData = MutableMapData(listOf(relation))
        mapData.addAll(referredElementsByKey.values)

        return mapData
    }

    @Synchronized override fun getWaysForNode(id: Long): Collection<Way> {
        val waysById = HashMap<Long, Way>()
        mapDataController.getWaysForNode(id).associateByTo(waysById) { it.id }

        for (element in updatedElements.values) {
            if (element is Way) {
                // if the updated version of a way contains the node, put/replace the updated way
                if (element.nodeIds.contains(id)) {
                    waysById[element.id] = element
                }
                // if the updated version does not contain the node (anymore), we need to remove it
                // from the output set (=an edit removed that node) - if it was contained at all
                else {
                    waysById.remove(element.id)
                }
            }
        }
        for (key in deletedElements) {
            if (key.elementType == WAY) {
                waysById.remove(key.elementId)
            }
        }

        return waysById.values
    }

    override fun getRelationsForNode(id: Long): Collection<Relation> = getRelationsForElement(NODE, id)

    override fun getRelationsForWay(id: Long): Collection<Relation> = getRelationsForElement(WAY, id)

    override fun getRelationsForRelation(id: Long): Collection<Relation> = getRelationsForElement(RELATION, id)

    @Synchronized fun getRelationsForElement(type: Element.Type, id: Long): Collection<Relation> {
        val relationsById = HashMap<Long, Relation>()
        val relations = when(type) {
            NODE -> mapDataController.getRelationsForNode(id)
            WAY -> mapDataController.getRelationsForWay(id)
            RELATION -> mapDataController.getRelationsForRelation(id)
        }
        relations.associateByTo(relationsById) { it.id }

        for (element in updatedElements.values) {
            if (element is Relation) {
                // if the updated version of a relation contains the node, put/replace the updated relation
                if (element.members.any { it.type == type && it.ref == id }) {
                    relationsById[element.id] = element
                }
                // if the updated version does not contain the node (anymore), we need to remove it
                // from the output set (=an edit removed that node) - if it was contained at all
                else {
                    relationsById.remove(element.id)
                }
            }
        }
        for (key in deletedElements) {
            if (key.elementType == RELATION) {
                relationsById.remove(key.elementId)
            }
        }

        return relationsById.values
    }

    /* ------------------------------------------------------------------------------------------ */

    @Synchronized private fun modifyBBoxMapData(bbox: BoundingBox, mapData: MutableMapDataWithGeometry) {
        // add the modified data if it is in the bbox
        for ((key, geometry) in updatedGeometries) {
            if (geometry.getBounds().intersect(bbox)) {
                val element = updatedElements[key]
                if (element != null) {
                    mapData.put(element, geometry)
                }
            }
        }
        // and remove elements that have been deleted
        for (key in deletedElements) {
            mapData.remove(key.elementType, key.elementId)
        }
    }

    @Synchronized private fun rebuildLocalChanges() {
        deletedElements.clear()
        updatedElements.clear()
        updatedGeometries.clear()
        val edits = elementEditsController.getAllUnsynced()
        for (edit in edits) {
            applyEdit(edit)
        }
    }

    @Synchronized private fun applyEdit(edit: ElementEdit): Collection<Element>  {
        val idProvider = elementEditsController.getIdProvider(edit.id!!)
        val editElement = get(edit.elementType, edit.elementId) ?: return emptyList()

        val elements: Collection<Element>
        try {
            elements = edit.action.createUpdates(editElement, this, idProvider)
        } catch (e: ElementConflictException) {
            return emptyList()
        }
        for (element in elements) {
            val key = ElementKey(element.type, element.id)
            if (element.isDeleted) {
                deletedElements.add(key)
                updatedElements.remove(key)
                updatedGeometries.remove(key)
            } else {
                deletedElements.remove(key)
                updatedElements[key] = element
                val geometry = createGeometry(element)
                if (geometry != null) updatedGeometries[key] = geometry
                else updatedGeometries.remove(key)
            }
        }
        return elements
    }

    private fun createGeometry(element: Element): ElementGeometry? {
        return when(element) {
            is Node -> {
                elementGeometryCreator.create(element)
            }
            is Way -> {
                val wayMapData = getWayComplete(element.id) ?: return null
                elementGeometryCreator.create(element, wayMapData)
            }
            is Relation -> {
                val relationMapData = getRelationComplete(element.id) ?: return null
                elementGeometryCreator.create(element, relationMapData, true)
            }
            else -> throw IllegalStateException()
        }
    }

    /** Return the key of elements that the given edit created. May be empty. */
    private fun getCreatedElementKeys(edit: ElementEdit): List<ElementKey> {
        val counts = edit.action.newElementsCount
        val idProvider = elementEditsController.getIdProvider(edit.id!!)
        val elementKeys = ArrayList<ElementKey>(counts.nodes + counts.ways + counts.relations)
        repeat(counts.nodes) { elementKeys.add(ElementKey(NODE, idProvider.nextNodeId())) }
        repeat(counts.ways) { elementKeys.add(ElementKey(WAY, idProvider.nextWayId())) }
        repeat(counts.relations) { elementKeys.add(ElementKey(RELATION, idProvider.nextRelationId())) }
        return elementKeys
    }

    fun addListener(listener: Listener) {
        this.listeners.add(listener)
    }
    fun removeListener(listener: Listener) {
        this.listeners.remove(listener)
    }

    private fun callOnUpdated(updated: MapDataWithGeometry = MutableMapDataWithGeometry(), deleted: Collection<ElementKey> = emptyList()) {
        listeners.forEach { it.onUpdated(updated, deleted) }
    }
    private fun callOnReplacedForBBox(bbox: BoundingBox, mapDataWithGeometry: MapDataWithGeometry) {
        listeners.forEach { it.onReplacedForBBox(bbox, mapDataWithGeometry) }
    }
}