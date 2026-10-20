// Third-party notice collection for the complete APK.
// Applied from androidApp/build.gradle.kts. Run:
//   gradlew :androidApp:collectThirdPartyNotices
// Produces build/third-party-notices/<config>/ with one directory per Maven
// artifact containing: pom license metadata and every LICENSE/NOTICE/COPYING
// file shipped inside the jar/aar. Nothing here asserts a license for MINI's
// own code, and nothing here establishes compliance on its own.

import java.security.MessageDigest
import java.util.zip.ZipFile

val noticeConfigurations = listOf("releaseRuntimeClasspath", "debugRuntimeClasspath")
val noticeOutRoot = layout.buildDirectory.dir("third-party-notices")

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

val noticeFileRegex = Regex("""(^|/)(META-INF/)?([^/]*)(LICEN[CS]E|NOTICE|COPYING|THIRD[_-]?PARTY)[^/]*$""", RegexOption.IGNORE_CASE)

tasks.register("collectThirdPartyNotices") {
    group = "compliance"
    description = "Collects POM license metadata and embedded LICENSE/NOTICE files for every APK dependency."
    outputs.dir(noticeOutRoot)
    outputs.upToDateWhen { false }
    doLast {
        val root = noticeOutRoot.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        noticeConfigurations.forEach { cfgName ->
            val cfg = configurations.findByName(cfgName) ?: return@forEach
            val cfgDir = File(root, cfgName).apply { mkdirs() }
            val index = StringBuilder()
            index.append("coordinates\tpom_licenses\tembedded_notice_files\n")
            val artifacts = cfg.incoming.artifactView {
                lenient(true)
                attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
            }.artifacts.artifacts.toMutableList()
            // Also collect raw aar/jar files so embedded notices in non-class resources are seen.
            val raw = cfg.incoming.artifactView { lenient(true) }.artifacts.artifacts
            val seen = mutableSetOf<String>()
            (raw + artifacts).forEach { art ->
                val id = art.id.componentIdentifier
                val coords = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                    "${id.group}:${id.module}:${id.version}"
                } else {
                    id.displayName
                }
                val dir = File(cfgDir, coords.replace(':', '_').replace('/', '_').replace('\\', '_'))
                dir.mkdirs()
                val found = mutableListOf<String>()
                val f = art.file
                if (f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".aar"))) {
                    runCatching {
                        ZipFile(f).use { zip ->
                            zip.entries().asSequence()
                                .filter { !it.isDirectory && noticeFileRegex.containsMatchIn(it.name) }
                                .forEach { entry ->
                                    val bytes = zip.getInputStream(entry).readBytes()
                                    val outName = entry.name.replace('/', '_')
                                    File(dir, outName).writeBytes(bytes)
                                    found += "${entry.name}#${sha256(bytes).take(16)}"
                                }
                        }
                    }
                }
                if (seen.add(coords)) {
                    var pomLicenses = ""
                    if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        // Walk the <parent> chain: many POMs (guava, zxing, protobuf) inherit <licenses>.
                        var coord: String? = "${id.group}:${id.module}:${id.version}"
                        var depth = 0
                        val licenseRe = Regex("""<license>\s*<name>([^<]+)</name>(?:\s*<url>([^<]+)</url>)?""")
                        val parentRe = Regex("""<parent>\s*(?:<!--.*?-->\s*)*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>""", RegexOption.DOT_MATCHES_ALL)
                        while (coord != null && pomLicenses.isBlank() && depth < 6) {
                            val pomDep = dependencies.create("$coord@pom")
                            val pomCfg = configurations.detachedConfiguration(pomDep).apply { isTransitive = false }
                            val pom = runCatching { pomCfg.resolve().firstOrNull() }.getOrNull() ?: break
                            pom.copyTo(File(dir, if (depth == 0) "pom.xml" else "parent-$depth.pom.xml"), overwrite = true)
                            val text = pom.readText()
                            val licensesBlock = Regex("""<licenses>(.*?)</licenses>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()
                            pomLicenses = licenseRe.findAll(licensesBlock).joinToString("; ") { m ->
                                m.groupValues[1].trim() + (m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { " <$it>" } ?: "")
                            }
                            if (pomLicenses.isNotBlank() && depth > 0) pomLicenses += " (inherited)"
                            coord = parentRe.find(text)?.let { "${it.groupValues[1].trim()}:${it.groupValues[2].trim()}:${it.groupValues[3].trim()}" }
                            depth++
                        }
                    }
                    index.append(coords).append('\t').append(pomLicenses.ifBlank { "-" }).append('\t')
                        .append(if (found.isEmpty()) "-" else found.joinToString(",")).append('\n')
                } else if (found.isNotEmpty()) {
                    index.append(coords).append("\t(dup)\t").append(found.joinToString(",")).append('\n')
                }
            }
            File(cfgDir, "INDEX.tsv").writeText(index.toString())
            logger.lifecycle("$cfgName: ${seen.size} artifacts -> ${cfgDir}")
        }
    }
}
