import java.io.File

plugins {
    java
    kotlin("jvm") version "2.1.20"
}

group = "ity.snow"
version = "1.1"

repositories {
    mavenCentral()
    maven(url = "https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    maven(url = "https://www.jitpack.io")
}

dependencies {
    compileOnly("com.github.Anuken.Arc:arc-core:v150")
    compileOnly("com.github.Anuken.Mindustry:core:v150")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

kotlin {
    jvmToolchain(17)
}

fun File.readUtf8Lines(): List<String> {
    val bytes = readBytes()
    return when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, Charsets.UTF_8).lines()
        else -> {
            try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes)
            }.lines()
        }
    }
}

fun File.writeUtf8Lines(lines: List<String>) {
    outputStream().use { os ->
        lines.forEachIndexed { idx, line ->
            os.write(line.toByteArray(Charsets.UTF_8))
            if (idx != lines.size - 1) os.write('\n'.code)
        }
    }
}

val supportedLangs = listOf("en", "ru", "ja", "ko", "zh")

val syncI18nBundles by tasks.registering {
    group = "build"
    description = "Sync and align all i18n bundles"

    doLast {
        val srcDirs = listOf("src/main/kotlin", "src/main/java").map { file(it) }
        val resourcesDir = file("src/main/resources")

        val keyRegex = Regex("""I18nManager\.get\(\s*["']([^"']+)["']|["'](helpCmd\.[^"']+)["']""")

        val usedKeys = mutableSetOf<String>()
        srcDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    keyRegex.findAll(file.readText()).forEach { match ->
                        val key = match.groups[1]?.value ?: match.groups[2]?.value
                        if (!key.isNullOrBlank()) {
                            usedKeys += key
                        }
                    }
                }
        }

        val validKeys = usedKeys.filterNot { it.contains('$') }.sorted()

        val langMaps = mutableMapOf<String, LinkedHashMap<String, String>>()
        for (lang in supportedLangs) {
            val file = if (lang == "en")
                File(resourcesDir, "bundle.properties")
            else
                File(resourcesDir, "bundle_${lang}.properties")

            val lines = if (file.exists()) file.readUtf8Lines() else emptyList()
            val map = linkedMapOf<String, String>()
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                val idx = trimmed.indexOf("=")
                val key = trimmed.take(idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                map[key] = value
            }
            langMaps[lang] = map
        }

        for ((lang, map) in langMaps) {
            val file = if (lang == "en")
                File(resourcesDir, "bundle.properties")
            else
                File(resourcesDir, "bundle_${lang}.properties")

            val newLines = validKeys.map { key ->
                "$key=${map[key] ?: ""}"
            }

            file.writeUtf8Lines(newLines)
            println("Synced: ${file.name}, keys: ${newLines.size}")
        }
    }
}

tasks.jar {
    dependsOn(syncI18nBundles)
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
}
