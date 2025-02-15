package net.corda.node.internal.cordapp

import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.ScanResult
import net.corda.common.logging.errorReporting.CordappErrors
import net.corda.common.logging.errorReporting.ErrorCode
import net.corda.core.CordaRuntimeException
import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.SchedulableFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.internal.JAVA_17_CLASS_FILE_FORMAT_MAJOR_VERSION
import net.corda.core.internal.JAVA_1_2_CLASS_FILE_FORMAT_MAJOR_VERSION
import net.corda.core.internal.JarSignatureCollector
import net.corda.core.internal.PlatformVersionSwitches
import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.cordapp.CordappImpl.Companion.UNKNOWN_INFO
import net.corda.core.internal.cordapp.get
import net.corda.core.internal.hash
import net.corda.core.internal.isAbstractClass
import net.corda.core.internal.loadClassOfType
import net.corda.core.internal.location
import net.corda.core.internal.mapToSet
import net.corda.core.internal.notary.NotaryService
import net.corda.core.internal.notary.SinglePartyNotaryService
import net.corda.core.internal.objectOrNewInstance
import net.corda.core.internal.pooledScan
import net.corda.core.internal.telemetry.TelemetryComponent
import net.corda.core.internal.toPath
import net.corda.core.internal.toTypedArray
import net.corda.core.internal.warnContractWithoutConstraintPropagation
import net.corda.core.node.services.CordaService
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.CheckpointCustomSerializer
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.utilities.contextLogger
import net.corda.node.VersionInfo
import net.corda.nodeapi.internal.cordapp.CordappLoader
import net.corda.nodeapi.internal.coreContractClasses
import net.corda.serialization.internal.DefaultWhitelist
import java.lang.reflect.Modifier
import java.math.BigInteger
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Random
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isSameFileAs
import kotlin.io.path.listDirectoryEntries
import kotlin.reflect.KClass

/**
 * Handles CorDapp loading and classpath scanning of CorDapp JARs
 *
 * @property cordappJars The classpath of cordapp JARs
 */
class JarScanningCordappLoader private constructor(private val cordappJars: Set<Path>,
                                                   private val versionInfo: VersionInfo = VersionInfo.UNKNOWN,
                                                   extraCordapps: List<CordappImpl>,
                                                   private val signerKeyFingerprintBlacklist: List<SecureHash> = emptyList()) : CordappLoaderTemplate() {
    init {
        if (cordappJars.isEmpty()) {
            logger.info("No CorDapp paths provided")
        } else {
            logger.info("Loading CorDapps from ${cordappJars.joinToString()}")
        }
    }
    private val cordappClasses: ConcurrentHashMap<String, Set<Cordapp>> = ConcurrentHashMap()
    override val cordapps: List<CordappImpl> by lazy { loadCordapps() + extraCordapps }

    override val appClassLoader: URLClassLoader = URLClassLoader(
            cordappJars.stream().map { it.toUri().toURL() }.toTypedArray(),
            javaClass.classLoader
    )

    override fun close() = appClassLoader.close()

    companion object {
        private val logger = contextLogger()

        /**
         * Creates a CordappLoader from multiple directories.
         *
         * @param cordappDirs Directories used to scan for CorDapp JARs.
         */
        fun fromDirectories(cordappDirs: Collection<Path>,
                            versionInfo: VersionInfo = VersionInfo.UNKNOWN,
                            extraCordapps: List<CordappImpl> = emptyList(),
                            signerKeyFingerprintBlacklist: List<SecureHash> = emptyList()): JarScanningCordappLoader {
            logger.info("Looking for CorDapps in ${cordappDirs.distinct().joinToString(", ", "[", "]")}")
            val paths = cordappDirs
                    .asSequence()
                    .flatMap { if (it.exists()) it.listDirectoryEntries("*.jar") else emptyList() }
                    .toSet()
            return JarScanningCordappLoader(paths, versionInfo, extraCordapps, signerKeyFingerprintBlacklist)
        }

        /**
         * Creates a CordappLoader loader out of a list of JAR URLs.
         *
         * @param scanJars Uses the JAR URLs provided for classpath scanning and Cordapp detection.
         */
        fun fromJarUrls(scanJars: Set<Path>,
                        versionInfo: VersionInfo = VersionInfo.UNKNOWN,
                        extraCordapps: List<CordappImpl> = emptyList(),
                        cordappsSignerKeyFingerprintBlacklist: List<SecureHash> = emptyList()): JarScanningCordappLoader {
            return JarScanningCordappLoader(scanJars, versionInfo, extraCordapps, cordappsSignerKeyFingerprintBlacklist)
        }
    }

    private fun loadCordapps(): List<CordappImpl> {
        val invalidCordapps = mutableMapOf<String, Path>()

        val cordapps = cordappJars
                .map { path -> scanCordapp(path).use { it.toCordapp(path) } }
                .filter { cordapp ->
                    if (cordapp.minimumPlatformVersion > versionInfo.platformVersion) {
                        logger.warn("Not loading CorDapp ${cordapp.info.shortName} (${cordapp.info.vendor}) as it requires minimum " +
                                "platform version ${cordapp.minimumPlatformVersion} (This node is running version ${versionInfo.platformVersion}).")
                        invalidCordapps["CorDapp requires minimumPlatformVersion: ${cordapp.minimumPlatformVersion}, but was: ${versionInfo.platformVersion}"] = cordapp.jarFile
                        false
                    } else {
                        true
                    }
                }.filter { cordapp ->
                    if (signerKeyFingerprintBlacklist.isEmpty()) {
                        true //Nothing blacklisted, no need to check
                    } else {
                        val certificates = cordapp.jarPath.openStream().let(::JarInputStream).use(JarSignatureCollector::collectCertificates)
                        val blockedCertificates = certificates.filter { it.publicKey.hash.sha256() in signerKeyFingerprintBlacklist }
                        if (certificates.isEmpty() || (certificates - blockedCertificates).isNotEmpty()) {
                            true // Cordapp is not signed or it is signed by at least one non-blacklisted certificate
                        } else {
                            logger.warn("Not loading CorDapp ${cordapp.info.shortName} (${cordapp.info.vendor}) as it is signed by blacklisted key(s) only (probably development key): " +
                                    "${blockedCertificates.map { it.publicKey }}.")
                            invalidCordapps["Corresponding contracts are signed by blacklisted key(s) only (probably development key),"] = cordapp.jarFile
                            false
                        }
                    }
                }

        if (invalidCordapps.isNotEmpty()) {
            throw InvalidCordappException("Invalid Cordapps found, that couldn't be loaded: " +
                    "${invalidCordapps.map { "Problem: ${it.key} in Cordapp ${it.value}" }}, ")
        }

        cordapps.forEach(::register)
        return cordapps
    }

    private fun register(cordapp: Cordapp) {
        val contractClasses = cordapp.contractClassNames.toSet()
        val existingClasses = cordappClasses.keys
        val classesToRegister = cordapp.cordappClasses.toSet()
        val notAlreadyRegisteredClasses = classesToRegister - existingClasses
        val alreadyRegistered= HashMap(cordappClasses).apply { keys.retainAll(classesToRegister) }

        notAlreadyRegisteredClasses.forEach { cordappClasses[it] = setOf(cordapp) }

        for ((registeredClassName, registeredCordapps) in alreadyRegistered) {
            val duplicateCordapps = registeredCordapps.filter { it.jarHash == cordapp.jarHash }.toSet()

            if (duplicateCordapps.isNotEmpty()) {
                throw DuplicateCordappsInstalledException(cordapp, duplicateCordapps)
            }
            if (registeredClassName in contractClasses) {
                throw IllegalStateException("More than one CorDapp installed on the node for contract $registeredClassName. " +
                        "Please remove the previous version when upgrading to a new version.")
            }
            cordappClasses[registeredClassName] = registeredCordapps + cordapp
        }
    }

    private fun RestrictedScanResult.toCordapp(path: Path): CordappImpl {
        val manifest: Manifest? = JarInputStream(path.inputStream()).use { it.manifest }
        val info = parseCordappInfo(manifest, CordappImpl.jarName(path))
        val minPlatformVersion = manifest?.get(CordappImpl.MIN_PLATFORM_VERSION)?.toIntOrNull() ?: 1
        val targetPlatformVersion = manifest?.get(CordappImpl.TARGET_PLATFORM_VERSION)?.toIntOrNull() ?: minPlatformVersion
        validateContractStateClassVersion(this)
        validateWhitelistClassVersion(this)
        return CordappImpl(
                path,
                findContractClassNamesWithVersionCheck(this),
                findInitiatedFlows(this),
                findRPCFlows(this),
                findServiceFlows(this),
                findSchedulableFlows(this),
                findServices(this),
                findTelemetryComponents(this),
                findWhitelists(path),
                findSerializers(this),
                findCheckpointSerializers(this),
                findCustomSchemas(this),
                findAllFlows(this),
                info,
                path.hash,
                minPlatformVersion,
                targetPlatformVersion,
                findNotaryService(this),
                explicitCordappClasses = findAllCordappClasses(this)
        )
    }

    private fun parseCordappInfo(manifest: Manifest?, defaultName: String): Cordapp.Info {
        if (manifest == null) return UNKNOWN_INFO

        /** new identifiers (Corda 4) */
        // is it a Contract Jar?
        val contractInfo = if (manifest[CordappImpl.CORDAPP_CONTRACT_NAME] != null) {
            Cordapp.Info.Contract(
                    shortName = manifest[CordappImpl.CORDAPP_CONTRACT_NAME] ?: defaultName,
                    vendor = manifest[CordappImpl.CORDAPP_CONTRACT_VENDOR] ?: CordappImpl.UNKNOWN_VALUE,
                    versionId = parseVersion(manifest[CordappImpl.CORDAPP_CONTRACT_VERSION], CordappImpl.CORDAPP_CONTRACT_VERSION),
                    licence = manifest[CordappImpl.CORDAPP_CONTRACT_LICENCE] ?: CordappImpl.UNKNOWN_VALUE
            )
        } else {
            null
        }

        // is it a Workflow (flows and services) Jar?
        val workflowInfo = if (manifest[CordappImpl.CORDAPP_WORKFLOW_NAME] != null) {
            Cordapp.Info.Workflow(
                    shortName = manifest[CordappImpl.CORDAPP_WORKFLOW_NAME] ?: defaultName,
                    vendor = manifest[CordappImpl.CORDAPP_WORKFLOW_VENDOR] ?: CordappImpl.UNKNOWN_VALUE,
                    versionId = parseVersion(manifest[CordappImpl.CORDAPP_WORKFLOW_VERSION], CordappImpl.CORDAPP_WORKFLOW_VERSION),
                    licence = manifest[CordappImpl.CORDAPP_WORKFLOW_LICENCE] ?: CordappImpl.UNKNOWN_VALUE
            )
        } else {
            null
        }

        when {
            // combined Contract and Workflow Jar?
            contractInfo != null && workflowInfo != null -> return Cordapp.Info.ContractAndWorkflow(contractInfo, workflowInfo)
            contractInfo != null -> return contractInfo
            workflowInfo != null -> return workflowInfo
        }

        return Cordapp.Info.Default(
                shortName = manifest["Name"] ?: defaultName,
                vendor = manifest["Implementation-Vendor"] ?: CordappImpl.UNKNOWN_VALUE,
                version = manifest["Implementation-Version"] ?: CordappImpl.UNKNOWN_VALUE,
                licence = CordappImpl.UNKNOWN_VALUE
        )
    }

    private fun parseVersion(versionStr: String?, attributeName: String): Int {
        if (versionStr == null) {
            throw CordappInvalidVersionException(
                    "Target versionId attribute $attributeName not specified. Please specify a whole number starting from 1.",
                    CordappErrors.MISSING_VERSION_ATTRIBUTE,
                    listOf(attributeName))
        }
        val version = versionStr.toIntOrNull()
                ?: throw CordappInvalidVersionException(
                        "Version identifier ($versionStr) for attribute $attributeName must be a whole number starting from 1.",
                        CordappErrors.INVALID_VERSION_IDENTIFIER,
                        listOf(versionStr, attributeName))
        if (version < PlatformVersionSwitches.FIRST_VERSION) {
            throw CordappInvalidVersionException(
                    "Target versionId ($versionStr) for attribute $attributeName must not be smaller than 1.",
                    CordappErrors.INVALID_VERSION_IDENTIFIER,
                    listOf(versionStr, attributeName))
        }
        return version
    }

    private fun findNotaryService(scanResult: RestrictedScanResult): Class<out NotaryService>? {
        // Note: we search for implementations of both NotaryService and SinglePartyNotaryService as
        // the scanner won't find subclasses deeper down the hierarchy if any intermediate class is not
        // present in the CorDapp.
        val result = scanResult.getClassesWithSuperclass(NotaryService::class) +
                scanResult.getClassesWithSuperclass(SinglePartyNotaryService::class)
        if (result.isNotEmpty()) {
            logger.info("Found notary service CorDapp implementations: " + result.joinToString(", "))
        }
        return result.firstOrNull()
    }

    private fun findServices(scanResult: RestrictedScanResult): List<Class<out SerializeAsToken>> {
        return scanResult.getClassesWithAnnotation(SerializeAsToken::class, CordaService::class)
    }

    private fun findTelemetryComponents(scanResult: RestrictedScanResult): List<Class<out TelemetryComponent>> {
        return scanResult.getClassesImplementing(TelemetryComponent::class)
    }

    private fun findInitiatedFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, InitiatedBy::class)
    }

    private fun Class<out FlowLogic<*>>.isUserInvokable(): Boolean {
        return Modifier.isPublic(modifiers) && !isLocalClass && !isAnonymousClass && (!isMemberClass || Modifier.isStatic(modifiers))
    }

    private fun findRPCFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, StartableByRPC::class).filter { it.isUserInvokable() }
    }

    private fun findServiceFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, StartableByService::class)
    }

    private fun findSchedulableFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, SchedulableFlow::class)
    }

    private fun findAllFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getConcreteClassesOfType(FlowLogic::class)
    }

    private fun findAllCordappClasses(scanResult: RestrictedScanResult): List<String> {
        return scanResult.getAllStandardClasses() + scanResult.getAllInterfaces()
    }

    private fun findContractClassNamesWithVersionCheck(scanResult: RestrictedScanResult): List<String> {
        val contractClasses = coreContractClasses.flatMapTo(LinkedHashSet()) { scanResult.getNamesOfClassesImplementingWithClassVersionCheck(it) }.toList()
        for (contractClass in contractClasses) {
            contractClass.warnContractWithoutConstraintPropagation(appClassLoader)
        }
        return contractClasses
    }

    private fun validateContractStateClassVersion(scanResult: RestrictedScanResult) {
        coreContractClasses.forEach { scanResult.versionCheckClassesImplementing(it) }
    }

    private fun validateWhitelistClassVersion(scanResult: RestrictedScanResult) {
        scanResult.versionCheckClassesImplementing(SerializationWhitelist::class)
    }

    private fun findWhitelists(cordappJar: Path): List<SerializationWhitelist> {
        val whitelists = ServiceLoader.load(SerializationWhitelist::class.java, appClassLoader).toList()
        return whitelists.filter {
            it.javaClass.location.toPath().isSameFileAs(cordappJar)
        } + DefaultWhitelist // Always add the DefaultWhitelist to the whitelist for an app.
    }

    private fun findSerializers(scanResult: RestrictedScanResult): List<SerializationCustomSerializer<*, *>> {
        return scanResult.getClassesImplementingWithClassVersionCheck(SerializationCustomSerializer::class)
    }

    private fun findCheckpointSerializers(scanResult: RestrictedScanResult): List<CheckpointCustomSerializer<*, *>> {
        return scanResult.getClassesImplementingWithClassVersionCheck(CheckpointCustomSerializer::class)
    }

    private fun findCustomSchemas(scanResult: RestrictedScanResult): Set<MappedSchema> {
        return scanResult.getClassesWithSuperclass(MappedSchema::class).mapToSet { it.kotlin.objectOrNewInstance() }
    }

    private fun scanCordapp(cordappJar: Path): RestrictedScanResult {
        logger.info("Scanning CorDapp ${cordappJar.absolutePathString()}")
        val scanResult = ClassGraph()
            .filterClasspathElementsByURL { it.toPath().isSameFileAs(cordappJar) }
            .overrideClassLoaders(appClassLoader)
            .ignoreParentClassLoaders()
            .enableAllInfo()
            .pooledScan()
        return RestrictedScanResult(scanResult, cordappJar)
    }

    private fun <T : Any> loadClass(className: String, type: KClass<T>): Class<out T>? {
        return try {
            loadClassOfType(type.java, className, false, appClassLoader)
        } catch (e: ClassCastException) {
            logger.warn("As $className must be a sub-type of ${type.java.name}")
            null
        } catch (e: Exception) {
            logger.warn("Unable to load class $className", e)
            null
        }
    }

    private inner class RestrictedScanResult(private val scanResult: ScanResult, private val cordappJar: Path) : AutoCloseable {
        fun getNamesOfClassesImplementingWithClassVersionCheck(type: KClass<*>): List<String> {
            return scanResult.getClassesImplementing(type.java.name).map {
                validateClassFileVersion(it)
                it.name
            }
        }

        fun versionCheckClassesImplementing(type: KClass<*>) {
            return scanResult.getClassesImplementing(type.java.name).forEach {
                validateClassFileVersion(it)
            }
        }

        fun <T : Any> getClassesWithSuperclass(type: KClass<T>): List<Class<out T>> {
            return scanResult
                    .getSubclasses(type.java.name)
                    .names
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { it.isAbstractClass }
        }

        fun <T : Any> getClassesImplementingWithClassVersionCheck(type: KClass<T>): List<T> {
            return scanResult
                    .getClassesImplementing(type.java.name)
                    .mapNotNull {
                        validateClassFileVersion(it)
                        loadClass(it.name, type) }
                    .filterNot { it.isAbstractClass }
                    .map { it.kotlin.objectOrNewInstance() }
        }

        fun <T : Any> getClassesImplementing(type: KClass<T>): List<Class<out T>> {
            return scanResult
                    .getClassesImplementing(type.java.name)
                    .mapNotNull { loadClass(it.name, type) }
                    .filterNot { it.isAbstractClass }
        }

        fun <T : Any> getClassesWithAnnotation(type: KClass<T>, annotation: KClass<out Annotation>): List<Class<out T>> {
            return scanResult
                    .getClassesWithAnnotation(annotation.java.name)
                    .names
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { Modifier.isAbstract(it.modifiers) }
        }

        fun <T : Any> getConcreteClassesOfType(type: KClass<T>): List<Class<out T>> {
            return scanResult
                    .getSubclasses(type.java.name)
                    .names
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { it.isAbstractClass }
        }

        fun getAllStandardClasses(): List<String> = scanResult.allStandardClasses.names

        fun getAllInterfaces(): List<String> = scanResult.allInterfaces.names

        private fun validateClassFileVersion(classInfo: ClassInfo) {
            if (classInfo.classfileMajorVersion < JAVA_1_2_CLASS_FILE_FORMAT_MAJOR_VERSION ||
                classInfo.classfileMajorVersion > JAVA_17_CLASS_FILE_FORMAT_MAJOR_VERSION)
                    throw IllegalStateException("Class ${classInfo.name} from jar file $cordappJar has an invalid version of " +
                            "${classInfo.classfileMajorVersion}")
        }

        override fun close() = scanResult.close()
    }
}

/**
 * Thrown when scanning CorDapps.
 */
class MultipleCordappsForFlowException(
        message: String,
        flowName: String,
        jars: String
) : CordaRuntimeException(message), ErrorCode<CordappErrors> {
    override val code = CordappErrors.MULTIPLE_CORDAPPS_FOR_FLOW
    override val parameters = listOf(flowName, jars)
}

/**
 * Thrown if an exception occurs whilst parsing version identifiers within cordapp configuration
 */
class CordappInvalidVersionException(
        msg: String,
        override val code: CordappErrors,
        override val parameters: List<Any> = listOf()
) : CordaRuntimeException(msg), ErrorCode<CordappErrors>

/**
 * Thrown if duplicate CorDapps are installed on the node
 */
class DuplicateCordappsInstalledException(app: Cordapp, duplicates: Set<Cordapp>)
    : CordaRuntimeException("IllegalStateExcepion", "The CorDapp (name: ${app.info.shortName}, file: ${app.name}) " +
        "is installed multiple times on the node. The following files correspond to the exact same content: " +
        "${duplicates.map { it.name }}", null), ErrorCode<CordappErrors> {
    override val code = CordappErrors.DUPLICATE_CORDAPPS_INSTALLED
    override val parameters = listOf(app.info.shortName, app.name, duplicates.map { it.name })
}

/**
 * Thrown if an exception occurs during loading cordapps.
 */
class InvalidCordappException(message: String) : CordaRuntimeException(message)

abstract class CordappLoaderTemplate : CordappLoader {
    companion object {
        private val logger = contextLogger()
    }

    override val flowCordappMap: Map<Class<out FlowLogic<*>>, Cordapp> by lazy {
        cordapps.flatMap { corDapp -> corDapp.allFlows.map { flow -> flow to corDapp } }
                .groupBy { it.first }
                .mapValues { entry ->
                    if (entry.value.size > 1) {
                        logger.error("There are multiple CorDapp JARs on the classpath for flow " +
                                "${entry.value.first().first.name}: [ ${entry.value.joinToString { it.second.jarPath.toString() }} ].")
                        entry.value.forEach { (_, cordapp) ->
                            ZipInputStream(cordapp.jarPath.openStream()).use { zip ->
                                val ident = BigInteger(64, Random()).toString(36)
                                logger.error("Contents of: ${cordapp.jarPath} will be prefaced with: $ident")
                                var e = zip.nextEntry
                                while (e != null) {
                                    logger.error("$ident\t ${e.name}")
                                    e = zip.nextEntry
                                }
                            }
                        }
                        throw MultipleCordappsForFlowException("There are multiple CorDapp JARs on the classpath for flow " +
                                "${entry.value.first().first.name}: [ ${entry.value.joinToString { it.second.jarPath.toString() }} ].",
                                entry.value.first().first.name,
                                entry.value.joinToString { it.second.jarPath.toString() })
                    }
                    entry.value.single().second
                }
    }

    override val cordappSchemas: Set<MappedSchema> by lazy {
        cordapps.flatMap { it.customSchemas }.toSet()
    }
}
