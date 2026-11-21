package app.nubo.drive

import app.nubo.crypto.Bytes
import app.nubo.crypto.DataKey
import app.nubo.crypto.hex
import app.nubo.util.Parallel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A read of every own item under [folder] (research F-65). [at]: the elapsed time when the read started, or 0 for a
 * copy from the disk, which is always old.
 */
class Tree(val folder: String, val items: List<Item>, val at: Long) {
    private val folders: Set<String> by lazy { items.filter { it.folder }.mapTo(HashSet()) { it.uuid } }

    /** The part of this tree under the folder [sub], with the same read time; null when [sub] is not in it. */
    fun sub(sub: String): Tree? {
        if (sub == folder) return this
        if (sub !in folders) return null
        val children = HashMap<String, MutableList<String>>()
        for (item in items) if (item.folder) children.getOrPut(item.parent) { ArrayList() } += item.uuid
        val inside = hashSetOf(sub)
        val queue = ArrayDeque(listOf(sub))
        // add() is false for a folder that is already in: a loop in bad server data ends.
        while (queue.isNotEmpty()) children[queue.removeFirst()]?.forEach { if (inside.add(it)) queue += it }
        return Tree(sub, items.filter { it.parent in inside && it.uuid != sub }, at)
    }
}

/**
 * The last tree reads (search: the root; Photos: the photo folder), in memory and on the disk. Search and Photos
 * show them at once, also after a restart, and read again behind (DESIGN §6, review P-03). A part of the root read
 * serves a folder in it, so search and Photos share one read.
 *
 * The disk copy is encrypted with the session cache key: a header record, then records of [RECORD] items. Each
 * record is AES-256-GCM with the file ID and its index as associated data, so that no record can move to another
 * place or file; the header has the folder and the counts, so that a short file fails. A copy that does not
 * decrypt is deleted. At most two files: the root and one other folder. The logout wipe deletes the folder with the
 * rest of the cache.
 */
class Trees(private val dir: File, private val cacheKey: () -> DataKey?) {
    private val lock = Any()
    private val memory = HashMap<String, Tree>()
    private var generation = 0
    private val writes = Mutex()

    /** The newest tree for [folder]: its own read or a part of the [root] read, from memory, else from the disk. */
    suspend fun get(folder: String, root: String): Tree? = withContext(Dispatchers.Default) {
        val (own, whole) = synchronized(lock) { memory[folder] to memory[root] }
        // On a tie the own read wins: no need to cut it out of the root.
        listOfNotNull(own, whole?.sub(folder)).maxByOrNull { it.at }
            ?: load(folder)
            ?: if (folder != root) load(root)?.sub(folder) else null
    }

    /**
     * Keeps [tree] for [get] and writes it to the disk in [scope], unless the last read of its folder had the same
     * items (most reads: no change since). A new root read replaces the other reads.
     */
    fun put(tree: Tree, root: String, scope: CoroutineScope) {
        val old = synchronized(lock) {
            memory.put(tree.folder, tree).also { memory.keys.retainAll { it == tree.folder || it == root && tree.folder != root } }
        }
        scope.launch(Dispatchers.IO) { if (old?.items != tree.items || !file(tree.folder).isFile) save(tree, root) }
    }

    /** At logout. A write that runs now does not put its file back. */
    fun clear() = synchronized(lock) {
        generation++
        memory.clear()
    }

    private suspend fun load(folder: String): Tree? {
        val key = cacheKey() ?: return null
        val gen = synchronized(lock) { generation }
        val file = file(folder)
        val tree = try {
            withContext(Dispatchers.IO) { if (file.isFile) read(file, key, folder) else null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Damaged, short, or from another session key.
            withContext(Dispatchers.IO) { file.delete() }
            null
        } ?: return null
        return synchronized(lock) {
            if (gen != generation) return null
            memory.getOrPut(folder) { tree }
        }
    }

    /** The records of [file], read here (IO) and decoded on all cores. */
    private suspend fun read(file: File, key: DataKey, folder: String): Tree = withContext(Dispatchers.IO) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val head = DataInputStream(ByteArrayInputStream(key.decryptChunk(input.record(), aad(HEADER, 0))))
            if (head.readInt() != FORMAT) throw IOException("Old format")
            val id = ByteArray(ID_BYTES).also(head::readFully)
            if (head.readUTF() != folder) throw IOException("Another folder")
            val count = head.readInt()
            val records = head.readInt()
            val parts = Parallel<Pair<Int, ByteArray>, List<Item>>(this) { (i, data) -> unpack(key.decryptChunk(data, aad(id, i))) }
            for (i in 1..records) parts.add(i to input.record())
            if (input.read() != -1) throw IOException("Data after the end")
            val items = parts.results().flatten()
            if (items.size != count) throw IOException("Wrong count")
            Tree(folder, items, 0)
        }
    }

    private suspend fun save(tree: Tree, root: String) = writes.withLock {
        val key = cacheKey() ?: return@withLock
        val gen = synchronized(lock) { generation }
        val target = file(tree.folder)
        val tmp = File(dir, target.name + ".tmp")
        try {
            dir.mkdirs()
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                val id = Bytes.random(ID_BYTES)
                val parts = tree.items.chunked(RECORD)
                val head = ByteArrayOutputStream()
                DataOutputStream(head).apply {
                    writeInt(FORMAT)
                    write(id)
                    writeUTF(tree.folder)
                    writeInt(tree.items.size)
                    writeInt(parts.size)
                }
                out.record(key.encryptChunk(head.toByteArray(), aad = aad(HEADER, 0)))
                parts.forEachIndexed { i, part -> out.record(key.encryptChunk(pack(part), aad = aad(id, i + 1))) }
            }
            synchronized(lock) {
                if (gen != generation || !tmp.renameTo(target)) {
                    tmp.delete()
                    return@withLock
                }
                // At most the root and one other folder; a root read replaces the other.
                val keep = setOf(target.name, file(root).name)
                dir.listFiles()?.forEach { if (it.name !in keep || tree.folder == root && it.name != target.name) it.delete() }
            }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Exception) {
            // No disk copy (the disk is full, or a field is too long): the memory copy still serves.
            tmp.delete()
        }
    }

    /** A file name that tells nothing: a hash of the folder UUID. */
    private fun file(folder: String) = File(dir, Bytes.sha1(folder.encodeToByteArray()).hex())

    companion object {
        private const val FORMAT = 1
        private const val ID_BYTES = 16
        private const val RECORD = 2048

        /** Larger than any record (2048 items of at most a few KB each, compressed). */
        private const val MAX_RECORD = 32 shl 20
        private val HEADER = "nubo-tree-header".encodeToByteArray()

        private fun aad(id: ByteArray, index: Int) = id + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

        private fun DataOutputStream.record(data: ByteArray) {
            writeInt(data.size)
            write(data)
        }

        private fun DataInputStream.record(): ByteArray {
            val size = readInt()
            if (size !in 0..MAX_RECORD) throw IOException("Bad record")
            return ByteArray(size).also(::readFully)
        }

        /**
         * [items] with all fields, compressed. Tree items are always own items ([Source.Own]). The fields go to memory
         * first and the compression runs once: a stream through the compressor makes one native call for each field.
         */
        internal fun pack(items: List<Item>): ByteArray {
            val plain = ByteArrayOutputStream(items.size * 256)
            DataOutputStream(plain).use { out ->
                out.writeInt(items.size)
                for (i in items) {
                    out.writeUTF(i.uuid)
                    out.writeUTF(i.parent)
                    out.writeUTF(i.name)
                    out.writeBoolean(i.folder)
                    out.writeLong(i.size)
                    out.writeUTF(i.mime)
                    out.writeLong(i.modified)
                    out.writeLong(i.created)
                    out.writeLong(i.uploaded)
                    out.writeBoolean(i.favorite)
                    out.writeUTF(i.color)
                    out.writeUTF(i.key)
                    out.writeUTF(i.region)
                    out.writeUTF(i.bucket)
                    out.writeInt(i.chunks)
                    out.writeInt(i.version)
                    out.writeUTF(i.hash)
                }
            }
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                deflater.setInput(plain.toByteArray())
                deflater.finish()
                return drain(deflater::finished, deflater::deflate)
            } finally {
                deflater.end()
            }
        }

        /** The items of one record. One native inflate for the record, then the fields from memory. */
        internal fun unpack(data: ByteArray): List<Item> {
            val inflater = Inflater()
            val plain = try {
                inflater.setInput(data)
                drain(inflater::finished) { buffer ->
                    inflater.inflate(buffer).also { if (it == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("Short record") }
                }
            } finally {
                inflater.end()
            }
            return items(DataInputStream(ByteArrayInputStream(plain)))
        }

        /** Runs [step] into a buffer until [done]; at most [MAX_RECORD] bytes. */
        private inline fun drain(done: () -> Boolean, step: (ByteArray) -> Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 shl 10)
            while (!done()) {
                out.write(buffer, 0, step(buffer))
                if (out.size() > MAX_RECORD) throw IOException("Bad record")
            }
            return out.toByteArray()
        }

        private fun items(input: DataInputStream): List<Item> {
            val count = input.readInt()
            if (count !in 0..RECORD) throw IOException("Bad record")
            return List(count) {
                Item(
                    uuid = input.readUTF(),
                    parent = input.readUTF(),
                    name = input.readUTF(),
                    folder = input.readBoolean(),
                    size = input.readLong(),
                    mime = input.readUTF(),
                    modified = input.readLong(),
                    created = input.readLong(),
                    uploaded = input.readLong(),
                    favorite = input.readBoolean(),
                    color = input.readUTF(),
                    key = input.readUTF(),
                    region = input.readUTF(),
                    bucket = input.readUTF(),
                    chunks = input.readInt(),
                    version = input.readInt(),
                    hash = input.readUTF(),
                )
            }
        }
    }
}
