package net.corda.node.internal.cordapp

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import net.corda.core.internal.toPath
import net.corda.core.node.services.AttachmentId
import net.corda.core.node.services.AttachmentStorage
import net.corda.node.VersionInfo
import net.corda.testing.core.internal.ContractJarTestUtils
import net.corda.testing.core.internal.SelfCleaningDir
import net.corda.testing.internal.MockCordappConfigProvider
import net.corda.testing.services.MockAttachmentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.Deflater.NO_COMPRESSION
import java.util.zip.ZipEntry
import java.util.zip.ZipEntry.DEFLATED
import java.util.zip.ZipEntry.STORED
import kotlin.test.assertFailsWith

class CordappProviderImplTests {
    private companion object {
        val isolatedJAR = this::class.java.getResource("/isolated.jar")!!.toPath()
        // TODO: Cordapp name should differ from the JAR name
        const val isolatedCordappName = "isolated"
        val emptyJAR = this::class.java.getResource("empty.jar")!!.toPath()
        val validConfig: Config = ConfigFactory.parseString("key=value")

        @JvmField
        val ID1 = AttachmentId.randomSHA256()
        @JvmField
        val ID2 = AttachmentId.randomSHA256()
        @JvmField
        val ID3 = AttachmentId.randomSHA256()
        @JvmField
        val ID4 = AttachmentId.randomSHA256()

        val stubConfigProvider = object : CordappConfigProvider {
            override fun getConfigByName(name: String): Config = ConfigFactory.empty()
        }

        fun directoryEntry(internalName: String) = ZipEntry("$internalName/").apply {
            method = STORED
            compressedSize = 0
            size = 0
            crc = 0
        }

        fun fileEntry(internalName: String) = ZipEntry(internalName).apply {
            method = DEFLATED
        }
    }

    private lateinit var attachmentStore: AttachmentStorage

    @Before
    fun setup() {
        attachmentStore = MockAttachmentStorage()
    }

    @Test(timeout=300_000)
	fun `isolated jar is loaded into the attachment store`() {
        val provider = newCordappProvider(isolatedJAR)
        val maybeAttachmentId = provider.getCordappAttachmentId(provider.cordapps.first())

        assertNotNull(maybeAttachmentId)
        assertNotNull(attachmentStore.openAttachment(maybeAttachmentId!!))
    }

    @Test(timeout=300_000)
	fun `empty jar is not loaded into the attachment store`() {
        val provider = newCordappProvider(emptyJAR)
        assertNull(provider.getCordappAttachmentId(provider.cordapps.first()))
    }

    @Test(timeout=300_000)
	fun `test that we find a cordapp class that is loaded into the store`() {
        val provider = newCordappProvider(isolatedJAR)
        val className = "net.corda.isolated.contracts.AnotherDummyContract"

        val expected = provider.cordapps.first()
        val actual = provider.getCordappForClass(className)

        assertNotNull(actual)
        assertEquals(expected, actual)
    }

    @Test(timeout=300_000)
	fun `test that we find an attachment for a cordapp contract class`() {
        val provider = newCordappProvider(isolatedJAR)
        val className = "net.corda.isolated.contracts.AnotherDummyContract"
        val expected = provider.getAppContext(provider.cordapps.first()).attachmentId
        val actual = provider.getContractAttachmentID(className)

        assertNotNull(actual)
        assertEquals(actual!!, expected)
    }

    @Test(timeout=300_000)
	fun `test cordapp configuration`() {
        val configProvider = MockCordappConfigProvider()
        configProvider.cordappConfigs[isolatedCordappName] = validConfig
        val loader = JarScanningCordappLoader.fromJarUrls(setOf(isolatedJAR), VersionInfo.UNKNOWN)
        val provider = CordappProviderImpl(loader, configProvider, attachmentStore).apply { start() }

        val expected = provider.getAppContext(provider.cordapps.first()).config

        assertThat(expected.getString("key")).isEqualTo("value")
    }

    @Test(timeout=300_000)
	fun `test fixup rule that adds attachment`() {
        val fixupJar = File.createTempFile("fixup", ".jar")
            .writeFixupRules("$ID1 => $ID2, $ID3")
        val fixedIDs = with(newCordappProvider(fixupJar.toPath())) {
            start()
            attachmentFixups.fixupAttachmentIds(listOf(ID1))
        }
        assertThat(fixedIDs).containsExactly(ID2, ID3)
    }

    @Test(timeout=300_000)
	fun `test fixup rule that deletes attachment`() {
        val fixupJar = File.createTempFile("fixup", ".jar")
            .writeFixupRules("$ID1 =>")
        val fixedIDs = with(newCordappProvider(fixupJar.toPath())) {
            start()
            attachmentFixups.fixupAttachmentIds(listOf(ID1))
        }
        assertThat(fixedIDs).isEmpty()
    }

    @Test(timeout=300_000)
	fun `test fixup rule with blank LHS`() {
        val fixupJar = File.createTempFile("fixup", ".jar")
            .writeFixupRules(" => $ID2")
        val ex = assertFailsWith<IllegalArgumentException> {
            newCordappProvider(fixupJar.toPath()).start()
        }
        assertThat(ex).hasMessageContaining(
            "Forbidden empty list of source attachment IDs in '${fixupJar.absolutePath}'"
        )
    }

    @Test(timeout=300_000)
	fun `test fixup rule without arrows`() {
        val rule = " $ID1 "
        val fixupJar = File.createTempFile("fixup", ".jar")
            .writeFixupRules(rule)
        val ex = assertFailsWith<IllegalArgumentException> {
            newCordappProvider(fixupJar.toPath()).start()
        }
        assertThat(ex).hasMessageContaining(
            "Invalid fix-up line '${rule.trim()}' in '${fixupJar.absolutePath}'"
        )
    }

    @Test(timeout=300_000)
	fun `test fixup rule with too many arrows`() {
        val rule = " $ID1 => $ID2 => $ID3 "
        val fixupJar = File.createTempFile("fixup", ".jar")
            .writeFixupRules(rule)
        val ex = assertFailsWith<IllegalArgumentException> {
            newCordappProvider(fixupJar.toPath()).start()
        }
        assertThat(ex).hasMessageContaining(
            "Invalid fix-up line '${rule.trim()}' in '${fixupJar.absolutePath}'"
        )
    }

    @Test(timeout=300_000)
	fun `test fixup file containing multiple rules and comments`() {
        val fixupJar = File.createTempFile("fixup", ".jar").writeFixupRules(
            "# Whole line comment",
            "\t$ID1,$ID2 =>  $ID2,,  $ID3 # EOl comment",
            "   # Empty line with comment",
            "",
            "$ID3 => $ID4"
        )
        val fixedIDs = with(newCordappProvider(fixupJar.toPath())) {
            start()
            attachmentFixups.fixupAttachmentIds(listOf(ID2, ID1))
        }
        assertThat(fixedIDs).containsExactlyInAnyOrder(ID2, ID4)
    }

    @Test(timeout=300_000)
    fun `test an exception is raised when we have two jars with the same hash`() {
        SelfCleaningDir().use { file ->
            val jarAndSigner = ContractJarTestUtils.makeTestSignedContractJar(file.path, "com.example.MyContract")
            val signedJarPath = jarAndSigner.first
            val duplicateJarPath = signedJarPath.parent.resolve("duplicate-${signedJarPath.fileName}")

            Files.copy(signedJarPath, duplicateJarPath)
            val paths = setOf(signedJarPath, duplicateJarPath)
            JarScanningCordappLoader.fromJarUrls(paths, VersionInfo.UNKNOWN).use {
                assertFailsWith<DuplicateCordappsInstalledException> {
                    CordappProviderImpl(it, stubConfigProvider, attachmentStore).apply { start() }
                }
            }
        }
    }

    @Test(timeout=300_000)
    fun `test an exception is raised when two jars share a contract`() {
        SelfCleaningDir().use { file ->
            val jarA = ContractJarTestUtils.makeTestContractJar(file.path, listOf("com.example.MyContract", "com.example.AnotherContractForA"), generateManifest = false, jarFileName = "sampleA.jar")
            val jarB = ContractJarTestUtils.makeTestContractJar(file.path, listOf("com.example.MyContract", "com.example.AnotherContractForB"), generateManifest = false, jarFileName = "sampleB.jar")
            val paths = setOf(jarA, jarB)
            JarScanningCordappLoader.fromJarUrls(paths, VersionInfo.UNKNOWN).use {
                assertFailsWith<IllegalStateException> {
                    CordappProviderImpl(it, stubConfigProvider, attachmentStore).apply { start() }
                }
            }
        }
    }

    private fun File.writeFixupRules(vararg lines: String): File {
        JarOutputStream(FileOutputStream(this)).use { jar ->
            jar.setMethod(DEFLATED)
            jar.setLevel(NO_COMPRESSION)
            jar.putNextEntry(directoryEntry("META-INF"))
            jar.putNextEntry(fileEntry("META-INF/Corda-Fixups"))
            for (line in lines) {
                jar.write(line.toByteArray())
                jar.write('\r'.code)
                jar.write('\n'.code)
            }
        }
        return this
    }

    private fun newCordappProvider(vararg paths: Path): CordappProviderImpl {
        val loader = JarScanningCordappLoader.fromJarUrls(paths.toSet(), VersionInfo.UNKNOWN)
        return CordappProviderImpl(loader, stubConfigProvider, attachmentStore).apply { start() }
    }
}
