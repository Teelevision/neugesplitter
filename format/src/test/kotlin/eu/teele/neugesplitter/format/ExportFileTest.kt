package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.domain.BEN
import eu.teele.neugesplitter.domain.GROUP
import eu.teele.neugesplitter.domain.Log
import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.crypto.WrongPassphrase
import eu.teele.neugesplitter.format.export.ExportFile
import eu.teele.neugesplitter.format.export.ExportedGroup
import eu.teele.neugesplitter.format.export.Locked
import eu.teele.neugesplitter.format.json.EventJson
import eu.teele.neugesplitter.format.json.Limits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod

class ExportFileTest {

    private val other = GroupId.parse("0f".repeat(16))

    private val texts =
        Log().create("Flat").add(BEN, "Ben").rename(BEN, "Benno").events().map(EventJson::write)

    private fun written(groups: List<ExportedGroup>, passphrase: CharArray? = null): ByteArray =
        ByteArrayOutputStream().also { ExportFile.write(groups, it, passphrase) }.toByteArray()

    private fun read(bytes: ByteArray, passphrase: CharArray? = null) =
        ExportFile.read(ByteArrayInputStream(bytes), passphrase)

    /** Each entry's name, whether it is encrypted with AES, and its size. */
    private fun entries(bytes: ByteArray, passphrase: CharArray? = null) =
        ZipInputStream(ByteArrayInputStream(bytes), passphrase).use { zip ->
            generateSequence { zip.nextEntry }
                .map { Triple(it.fileName, it.encryptionMethod, zip.readBytes().size) }
                .toList()
        }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipParameters().apply { fileNameInZip = name })
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `groups read back in order, each event's text exactly as written (TR-17c)`() {
        val groups = listOf(ExportedGroup(GROUP, texts), ExportedGroup(other, texts.take(1)))
        val read = read(written(groups))
        assertEquals(listOf(GROUP, other), read.map { it.id })
        assertEquals(listOf(texts, texts.take(1)), read.map { it.events })
    }

    @Test
    fun `the file holds a manifest and a numbered batch per group, nothing about a backend`() {
        val names = entries(written(listOf(ExportedGroup(GROUP, texts)))).map { it.first }
        assertEquals(listOf("manifest.json", "groups/${GROUP.hex}/events/0001.json"), names)
    }

    @Test
    fun `a batch holds at most as many events as a log object`() {
        val many = List(Limits.EVENTS * 2 + 1) { """{"n":$it}""" }
        val bytes = written(listOf(ExportedGroup(GROUP, many)))
        assertEquals(4, entries(bytes).size)
        assertEquals(many, read(bytes).single().events)
    }

    @Test
    fun `a batch is at most as large as a log object`() {
        val long = "x".repeat(Limits.STRING - 16)
        val large = List(200) { """{"n":$it,"s":"$long"}""" }
        val bytes = written(listOf(ExportedGroup(GROUP, large)))
        val batches = entries(bytes).drop(1)
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.third <= Limits.SIZE })
        assertEquals(large, read(bytes).single().events)
    }

    @Test
    fun `an encrypted file is AES throughout and opens only with its passphrase (DA-4)`() {
        val passphrase = "correct horse battery staple".toCharArray()
        val bytes = written(listOf(ExportedGroup(GROUP, texts)), passphrase.copyOf())
        assertTrue(entries(bytes, passphrase).all { it.second == EncryptionMethod.AES })
        assertEquals(texts, read(bytes, passphrase).single().events)
        assertFailsWith<Locked> { read(bytes) }
        assertFailsWith<WrongPassphrase> { read(bytes, "wrong".toCharArray()) }
    }

    @Test
    fun `what is no export file is refused`() {
        assertFailsWith<Malformed> { read("not a zip".encodeToByteArray()) }
        assertFailsWith<Malformed> { read(zip("groups/x" to "{}")) }
        assertFailsWith<Malformed> {
            read(zip("groups/x" to "{}", "manifest.json" to """{"neugesplitter-export":1}"""))
        }
    }

    @Test
    fun `a later version is unsupported, not malformed (format-spec 8)`() {
        assertFailsWith<Unsupported> {
            read(zip("manifest.json" to """{"neugesplitter-export":2}"""))
        }
        assertFailsWith<Unsupported> {
            read(
                zip(
                    "manifest.json" to """{"neugesplitter-export":1}""",
                    "groups/${GROUP.hex}/events/0001.json" to
                        """{"neugesplitter":2,"events":[{}]}""",
                )
            )
        }
    }

    @Test
    fun `a missing or repeated batch is refused, an unknown entry ignored`() {
        val manifest = "manifest.json" to """{"neugesplitter-export":1}"""
        val batch = """{"neugesplitter":1,"events":[{"n":1}]}"""
        val at = "groups/${GROUP.hex}/events"
        assertFailsWith<Malformed> { read(zip(manifest, "$at/0002.json" to batch)) }
        assertFailsWith<Malformed> {
            read(zip(manifest, "$at/0001.json" to batch, "$at/00001.json" to batch))
        }
        val read = read(zip(manifest, "$at/0001.json" to batch, "notes.txt" to "hello"))
        assertEquals(listOf("""{"n":1}"""), read.single().events)
    }

    @Test
    fun `a batch breaking the JSON rules is refused`() {
        assertFailsWith<Malformed> {
            read(
                zip(
                    "manifest.json" to """{"neugesplitter-export":1}""",
                    "groups/${GROUP.hex}/events/0001.json" to
                        """{"neugesplitter":1,"events":[1.5]}""",
                )
            )
        }
    }

    @Test
    fun `regrouping a group created changes its id and nothing else`() {
        val regrouped = EventJson.regrouped(texts.first(), other)
        val before = EventJson.read(texts.first()) as Event.GroupCreated
        val after = EventJson.read(regrouped) as Event.GroupCreated
        assertEquals(before.copy(group = before.group.copy(id = other)), after)
        assertFailsWith<Malformed> { EventJson.regrouped(texts[1], other) }
    }
}
