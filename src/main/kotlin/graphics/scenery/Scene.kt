package graphics.scenery

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import de.javakaffee.kryoserializers.UUIDSerializer
import graphics.scenery.attribute.material.DefaultMaterial
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.spatial.HasSpatial
import graphics.scenery.net.NodePublisher
import graphics.scenery.net.NodeSubscriber
import graphics.scenery.serialization.*
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.VolumeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.imglib2.img.basictypeaccess.array.ByteArray
import org.joml.Vector3f
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Stream
import kotlin.streams.asSequence
import kotlin.system.measureTimeMillis

/**
 * Scene class. A Scene is a special kind of [Node] that can only exist once per graph,
 * as a root node.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class Scene : DefaultNode("RootNode"), HasRenderable, HasMaterial, HasSpatial {

    /** Temporary storage of the active observer ([Camera]) of the Scene. */
    var activeObserver: Camera? = null

    internal var sceneSize: AtomicLong = AtomicLong(0L)

    /** Callbacks to be called when a child is added to the scene */
    var onChildrenAdded = ConcurrentHashMap<String, (Node, Node) -> Unit>()
    /** Callbacks to be called when a child is removed from the scene */
    var onChildrenRemoved = ConcurrentHashMap<String, (Node, Node) -> Unit>()
    /** Callbacks to be called when a child is removed from the scene */
    var onNodePropertiesChanged = ConcurrentHashMap<String, (Node) -> Unit>()

    init {
        addRenderable()
        addMaterial()
        addSpatial()
    }

    /**
     * Find the currently active observer in the Scene.
     *
     * TODO: Store once-found camera in [activeObserver]
     *
     * @return The [Camera] that is currently active.
     */
    fun findObserver(): Camera? {
        return if(activeObserver == null) {
            val observers = discover(this, { n -> n is Camera}, useDiscoveryBarriers = true)

            activeObserver = observers.firstOrNull() as Camera?
            activeObserver
        } else {
            activeObserver
        }
    }

    /**
     * Depth-first search for elements in a Scene.
     *
     * @param[s] The Scene to search in
     * @param[func] A lambda taking a [Node] and returning a Boolean for matching.
     * @return A list of [Node]s that match [func].
     */
    fun discover(s: Scene, func: (Node) -> Boolean, useDiscoveryBarriers: Boolean = false): ArrayList<Node> {
        val visited = HashSet<Node>()
        val matched = ArrayList<Node>()

        fun discover(current: Node, f: (Node) -> Boolean) {
            if (!visited.add(current)) return
            for (v in current.children) {
                if (f(v)) {
                    matched.add(v)
                }

                if(!(useDiscoveryBarriers && v.discoveryBarrier)) {
                    discover(v, f)
                }
            }
        }

        discover(s, func)

        return matched
    }

    /**
     * Discovers [Node]s in a Scene [s] via [func] in a parallel manner, optionally stopping at discovery barriers,
     * if [useDiscoveryBarriers] is true.
     */
    @Suppress("UNUSED_VARIABLE", "unused")
    fun discoverParallel(s: Scene, func: (Node) -> Boolean, useDiscoveryBarriers: Boolean = false) = runBlocking<List<Node>> {
        val visited = Collections.synchronizedSet(HashSet<Node>(sceneSize.toInt()))
        val crs = Collections.synchronizedSet(HashSet<Job>())
        val matches = Collections.synchronizedSet(HashSet<Node>(sceneSize.toInt()))

        val channel = Channel<Node>()

        fun discover(current: Node, f: (Node) -> Boolean) {
            if (!visited.add(current)) return

            crs.add(launch {
                for(v in current.children) {
                    if (f(v)) {
//                        channel.send(v)
                        matches.add(v)
                    }

                    if (useDiscoveryBarriers && v.discoveryBarrier) {
                        logger.trace("Hit discovery barrier, not recursing deeper.")
                    } else {
                        discover(v, f)
                    }
                }
            })
        }

        discover(s as Node, func)

//        channel.close()

        crs.forEach { it.join() }
//        channel.consumeEach { logger.info("Added ${it.name}"); matches.add(it) }
        matches.toList()
    }

    /**
     * Depth-first search routine for a Scene.
     */
    @Suppress("unused")
    fun dfs(s: Scene) {
        val visited = HashSet<Node>()
        fun dfs(current: Node) {
            if (!visited.add(current)) return
            for (v in current.children)
                dfs(v)
        }

        dfs(s.children[0])
    }

    /**
     * Find a [Node] by its name.
     *
     * @param[name] The name of the [Node] to find.
     * @return The first [Node] matching [name].
     */
    @Suppress("unused")
    fun find(name: String): Node? {
        val matches = discover(this, { n -> n.name == name })

        return matches.firstOrNull()
    }

    /**
     * Find a [Node] by its class name.
     *
     * @param[className] The class name of the [Node] to find.
     * @return A list of Nodes with class name [name].
     */
    @Suppress("unused")
    fun findByClassname(className: String): List<Node> {
        return this.discover(this, { n -> n.javaClass.simpleName.contains(className) })
    }

    /**
     * Data class for selection matches, contains the [Node] as well as the distance
     * from the observer to it.
     */
    data class RaycastMatch(val node: Node, val distance: Float)

    /**
     * Data class for raycast results, including all matches, and the ray's origin and direction.
     */
    data class RaycastResult(val matches: List<RaycastMatch>, val initialPosition: Vector3f, val initialDirection: Vector3f)

    /**
     * Performs a raycast to discover objects in this [Scene] that would be intersected
     * by a ray originating from [position], shot in [direction]. This method can
     * be given a filter function to to ignore nodes for the raycast for nodes it retuns false for.
     * If [debug] is true, a set of spheres is placed along the cast ray.
     */
    @JvmOverloads fun raycast(position: Vector3f, direction: Vector3f,
                              filter: (Node) -> Boolean = {true},
                              debug: Boolean = false): RaycastResult {
        if (debug) {
            val indicatorMaterial = DefaultMaterial()
            indicatorMaterial.diffuse = Vector3f(1.0f, 0.2f, 0.2f)
            indicatorMaterial.specular = Vector3f(1.0f, 0.2f, 0.2f)
            indicatorMaterial.ambient = Vector3f(0.0f, 0.0f, 0.0f)

            for(it in 1..20) {
                val s = Box(Vector3f(0.03f))
                s.setMaterial(indicatorMaterial)
                s.spatial {
                    this.position = position + direction * (it.toFloat() * 0.5f)
                }
                this.addChild(s)
            }
        }

        val matches = this.discover(this, { node ->
            node.visible && filter(node)
        }).flatMap { (
            if (it is InstancedNode)
                Stream.concat(Stream.of(it as Node), it.instances.map { instanceNode -> instanceNode as Node }.stream())
            else
                Stream.of(it)).asSequence()
        }.mapNotNull {
            val p = Pair(it, it.spatialOrNull()?.intersectAABB(position, direction))
            if(p.first !is InstancedNode && p.second is MaybeIntersects.Intersection
                && (p.second as MaybeIntersects.Intersection).distance > 0.0f) {
                RaycastMatch(p.first, (p.second as MaybeIntersects.Intersection).distance)
            } else {
                null
            }
        }.sortedBy {
            it.distance
        }

        if (debug) {
            logger.info(matches.joinToString(", ") { "${it.node.name} at distance ${it.distance}" })

            val m = DefaultMaterial()
            m.diffuse = Vector3f(1.0f, 0.0f, 0.0f)
            m.specular = Vector3f(0.0f, 0.0f, 0.0f)
            m.ambient = Vector3f(0.0f, 0.0f, 0.0f)

            matches.firstOrNull()?.node?.setMaterial(m)
        }

        return RaycastResult(matches, position, direction)
    }

    fun export(filename: String) {
        var size = 0L
        val duration = measureTimeMillis {
            val kryo = freeze()

            val output = Output(FileOutputStream(filename))
            kryo.writeObject(output, this)
            size = output.total()
            output.close()

        }

        logger.info("Written scene to $filename (${"%.2f".format(size/1024.0f)} KiB) in ${duration}ms")
    }

    fun publishSubscribe(hub: Hub, filter: (Node) -> Boolean = { true }) {
        val nodes = discover(this, filter)
        val pub = hub.get<NodePublisher>()
        val sub = hub.get<NodeSubscriber>()

        nodes.forEachIndexed { i, node ->
            pub?.nodes?.put(13337 + i, node)
            sub?.nodes?.put(13337 + i, node)
        }

    }

    companion object {
        @JvmStatic
        fun import(filename: String): Scene {
            val kryo = freeze()
            val input = Input(FileInputStream(filename))
            val scene = kryo.readObject(input, Scene::class.java)
            scene.discover(scene, { true }).forEach { it.initialized = false }
            scene.initialized = false

            return scene
        }

        fun freeze(): Kryo {
            val kryo = Kryo()
            kryo.isRegistrationRequired = false
            kryo.references = true
            kryo.register(UUID::class.java, UUIDSerializer())
            kryo.register(OrientedBoundingBox::class.java, OrientedBoundingBoxSerializer())
            kryo.register(Triple::class.java, TripleSerializer())
            kryo.register(ByteBuffer::class.java, ByteBufferSerializer())
            val tmp = ByteBuffer.allocateDirect(1)
            kryo.register(tmp.javaClass, ByteBufferSerializer())
            kryo.register(ByteArray::class.java, Imglib2ByteArraySerializer())
            kryo.register(ShaderMaterial::class.java, ShaderMaterialSerializer())
            kryo.register(java.util.zip.Inflater::class.java, IgnoreSerializer<java.util.zip.Inflater>())
            kryo.register(VolumeManager::class.java, IgnoreSerializer<VolumeManager>())

            return kryo
        }
    }
}
