package eu.teele.neugesplitter.format.export

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.json.Limits
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.format.json.array
import eu.teele.neugesplitter.format.json.integer
import eu.teele.neugesplitter.format.json.required
import eu.teele.neugesplitter.format.json.restricted
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod

/** One group as an export file carries it: the text of each event, in the order of its log. */
class ExportedGroup(val id: GroupId, val events: List<String>) {

    init {
        require(events.isNotEmpty()) { "a group has at least its group.created" }
    }
}

/** The export file is encrypted, and no passphrase was given. */
class Locked : Exception("the export file is encrypted")

/**
 * A zip file of groups, independent of any backend, optionally encrypted as a whole with zip's own
 * AES-256 (format-spec 11, DA-3, DA-4).
 */
object ExportFile {

    /** The layout version this implementation reads and writes. */
    const val VERSION: Int = 1

    /** What a reader holds in memory at most, uncompressed. */
    const val TOTAL: Long = 64L * 1024 * 1024

    private const val MANIFEST = "manifest.json"
    private const val VERSION_FIELD = "neugesplitter-export"
    private const val EVENTS_FIELD = "events"
    private val BATCH = Regex("groups/([0-9a-f]{32})/events/([0-9]{4,9})\\.json")
    private val HEAD = """{"neugesplitter":${LogObject.VERSION},"events":[""".encodeToByteArray()
    private val TAIL = "]}".encodeToByteArray()

    /** Writes [groups] to [out], encrypted with [passphrase] if there is one. */
    fun write(groups: List<ExportedGroup>, out: OutputStream, passphrase: CharArray? = null) {
        val zip = if (passphrase == null) ZipOutputStream(out) else ZipOutputStream(out, passphrase)
        zip.use {
            fun entry(name: String, bytes: ByteArray) {
                val parameters =
                    ZipParameters().apply {
                        fileNameInZip = name
                        compressionMethod = CompressionMethod.DEFLATE
                        if (passphrase != null) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                zip.putNextEntry(parameters)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(MANIFEST, """{"$VERSION_FIELD":$VERSION}""".encodeToByteArray())
            for (group in groups) {
                batches(group.events).forEachIndexed { index, batch ->
                    entry("groups/${group.id.hex}/events/%04d.json".format(index + 1), batch)
                }
            }
        }
    }

    /** The events as batches of one log object's limits, each event's text as it is (TR-17c). */
    private fun batches(events: List<String>): List<ByteArray> {
        val batches = mutableListOf<ByteArray>()
        val batch = ByteArrayOutputStream()
        var count = 0
        fun close() {
            batch.write(TAIL)
            batches += batch.toByteArray()
            batch.reset()
            count = 0
        }
        for (text in events) {
            val bytes = text.encodeToByteArray()
            require(HEAD.size + bytes.size + TAIL.size <= Limits.SIZE) { "an event too large" }
            val full =
                count == Limits.EVENTS || batch.size() + 1 + bytes.size + TAIL.size > Limits.SIZE
            if (count > 0 && full) close()
            if (count == 0) batch.write(HEAD) else batch.write(','.code)
            batch.write(bytes)
            count++
        }
        if (count > 0) close()
        return batches
    }

    /**
     * The groups in the export file [input], in the order it holds them. Each group's events are
     * checked against the JSON rules and limits, not against each other; that is the importer's.
     *
     * @throws Locked if the file is encrypted and [passphrase] is null.
     * @throws WrongPassphrase if [passphrase] does not open it.
     * @throws Malformed if it is no export file, or breaks a rule of this one.
     * @throws Unsupported if a later version wrote it.
     */
    fun read(input: InputStream, passphrase: CharArray? = null): List<ExportedGroup> {
        val batches = linkedMapOf<GroupId, MutableMap<Int, List<String>>>()
        var total = 0L
        try {
            ZipInputStream(input, passphrase).use { zip ->
                var first = true
                while (true) {
                    val header = zip.nextEntry ?: break
                    val name = header.fileName
                    if (first) {
                        if (name != MANIFEST) throw Malformed("no export file: no manifest first")
                        manifest(entry(zip))
                        first = false
                        continue
                    }
                    val match = BATCH.matchEntire(name) ?: continue
                    val bytes = entry(zip)
                    total += bytes.size
                    if (total > TOTAL) throw Malformed("an export file larger than $TOTAL bytes")
                    val group = GroupId.parse(match.groupValues[1])
                    val number = match.groupValues[2].toInt()
                    val numbered = batches.getOrPut(group) { mutableMapOf() }
                    if (numbered.put(number, batch(bytes)) != null) {
                        throw Malformed("a batch appears twice in one export file")
                    }
                }
                if (first) throw Malformed("no export file: no manifest")
            }
        } catch (e: ZipException) {
            when {
                e.type != ZipException.Type.WRONG_PASSWORD -> throw Malformed("no zip file")
                passphrase == null -> throw Locked()
                else -> throw WrongPassphrase()
            }
        }
        return batches.map { (group, numbered) ->
            if (numbered.keys != (1..numbered.size).toSet()) {
                throw Malformed("a group's batches are not numbered 1 to ${numbered.size}")
            }
            ExportedGroup(group, (1..numbered.size).flatMap(numbered::getValue))
        }
    }

    /** The rest of the current entry, refused past one object's size. */
    private fun entry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > Limits.SIZE) {
                throw Malformed("an entry is more than ${Limits.SIZE} bytes")
            }
        }
        return out.toByteArray()
    }

    private fun manifest(bytes: ByteArray) {
        val json = Json.parseToJsonElement(restricted(bytes)) as JsonObject
        val version = json.required(VERSION_FIELD, ::integer)
        if (version > VERSION) throw Unsupported("export version $version")
        if (version != VERSION.toLong()) throw Malformed("export version $version")
    }

    /** A batch's events, as text exactly as it holds them. */
    private fun batch(bytes: ByteArray): List<String> {
        val texts = mutableListOf<String>()
        val json = Json.parseToJsonElement(restricted(bytes, texts)) as JsonObject
        val version = json.required("neugesplitter", ::integer)
        if (version > LogObject.VERSION) throw Unsupported("format version $version")
        if (version != LogObject.VERSION.toLong()) throw Malformed("format version $version")
        if (json.required(EVENTS_FIELD, ::array).isEmpty()) throw Malformed("a batch of no events")
        return texts
    }
}
