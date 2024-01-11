package io.ktor.plugins.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.plugins.registry.SemverUtils.semverString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*

@OptIn(
    ExperimentalSerializationApi::class,
    ExperimentalPathApi::class
)
class RegistryBuilder(
    private val logger: KLogger = KotlinLogging.logger("RegistryBuilder"),
    private val yaml: Yaml = Yaml.default,
    private val json: Json = Json { prettyPrint = true }
) {
    fun buildRegistry(pluginsRoot: Path, buildDir: Path, target: String) {
        val pluginsDir = pluginsRoot.resolve(target)
        val artifactsFile = buildDir.resolve("$target-artifacts.yaml")
        val outputDir = buildDir.resolve("registry").resolve(target)
        val manifestsDir = outputDir.resolve("manifests")
        if (!artifactsFile.exists())
            throw PluginsUnresolvedException()
        else {
            outputDir.apply {
                deleteRecursively()
                createDirectories()
                manifestsDir.createDirectory()
            }
        }
        logger.info { "Building registry for $target..." }
        with(ktorReleasesFromFile()) {
            resolvePluginVersions(pluginsDir)
            outputReleaseMappings(outputDir)
            outputManifestFiles(pluginsDir, artifactsFile, manifestsDir)
        }
    }

    private fun ktorReleasesFromFile() =
        Paths.get("build/ktor_releases").readLines().map(::KtorRelease)

    private fun List<KtorRelease>.resolvePluginVersions(pluginsDir: Path) {
        for (plugin in pluginsDir.readPluginFiles()) {
            try {
                val distributions = mapNotNull { release ->
                    release.pickVersion(plugin)?.let {
                        "${release.versionString}: $it"
                    }
                }
                logger.debug { "Plugin ${plugin.id}\n\t${distributions.joinToString("\n\t")}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to process plugin ${plugin.id}!" }
            }
        }
    }

    private fun List<KtorRelease>.outputReleaseMappings(distDir: Path) {
        distDir.resolve("features.json").outputStream().use { output ->
            json.encodeToStream(associate { release ->
                release.versionString to release.plugins.map { it.manifestOutputFile }
            }, output)
        }
    }

    private fun List<KtorRelease>.outputManifestFiles(pluginsDir: Path, artifactsFile: Path, manifestsDir: Path) {

        val artifactsByRelease: Map<String, Map<String, String>> =
            artifactsFile.inputStream().use(yaml::decodeFromStream)
        val snippetExtractor = CodeSnippetExtractor()

        for (release in this) {
            logger.info {
                if (release.plugins.isEmpty())
                    "No plugins available for ${release.versionString}"
                else
                    "Fetching manifests for ${release.versionString} ${release.plugins.map { it.id }.sorted()}"
            }
            val jars = when (val releaseArtifacts = artifactsByRelease[release.versionString]) {
                null -> {
                    logger.error { "No artifacts found for ${release.versionString}!" }
                    continue
                }
                else -> releaseArtifacts.values.map(::toPathUrl)
            }

            URLClassLoader(jars.toTypedArray()).use { classLoader ->
                with(PluginResolutionContext(snippetExtractor, release, classLoader, pluginsDir)) {
                    for (plugin in release.plugins) {
                        val outputFile = manifestsDir.resolve(plugin.manifestOutputFile)
                        if (plugin.isUnresolved() || outputFile.exists())
                            continue

                        when (val manifest = resolveManifest(plugin)) {
                            null -> logger.error { "Could not find manifest for ${plugin.group.id}:${plugin.id} for ktor ${plugin.versionRange}" }
                            else -> manifest.export(outputFile, json)
                        }
                    }
                }
            }
        }
    }


}

val PluginReference.manifestOutputFile: String get() = "$id$versionRange.json"
val PluginReference.identifier: String get() = "${group.id}:$id:$versionRange"
val PluginReference.versionRange: String get() = versions.keys.single()

// Should be exactly one applicable version per release
private fun PluginReference.isUnresolved() = versions.keys.size != 1
private fun toPathUrl(pathString: String) = Paths.get(pathString).toUri().toURL()

data class KtorRelease(
    val versionString: String,
    val plugins: MutableList<PluginReference> = mutableListOf(),
) {

    /**
     * Selects the first plugin version that satisfies this release and includes it in "plugins" list.
     */
    fun pickVersion(plugin: PluginReference): String? {
        val version = SemverUtils.parse(versionString)
        return plugin.versions.keys.firstOrNull {
            version.satisfies(it.semverString())
        }?.also { foundVersion ->
            plugins.add(plugin.copy(versions = mapOf(foundVersion to plugin.versions[foundVersion]!!)))
        }
    }

}

class PluginsUnresolvedException : IllegalArgumentException("Run resolvePlugins gradle task BEFORE executing")