import java.util.Properties

val updateBundleWithUsedKeys by tasks.registering {
    group = "build"
    description = "Scan code for I18nManager in bundle.properties"

    val srcDirs = listOf("src/main/kotlin", "src/main/java").map { file(it) }
    val resourcesDir = file("src/main/resources")
    val bundleFile = File(resourcesDir, "bundle.properties")

    val keyRegex = Regex("""I18nManager\.get\(\s*"(.*?)"""")
    val allKeys = mutableSetOf<String>()
    srcDirs.filter { it.exists() }.forEach { dir ->
        dir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt") }
            .forEach { file ->
                file.forEachLine { line ->
                    keyRegex.findAll(line).forEach { match ->
                        allKeys += match.groupValues[1]
                    }
                }
            }
    }

    val bundleProps = Properties().apply {
        if (bundleFile.exists()) bundleFile.inputStream().use { load(it) }
    }

    var changed = false
    allKeys.forEach { key ->
        if (!bundleProps.containsKey(key)) {
            bundleProps.setProperty(key, "")
            println("Add i18n key: $key")
            changed = true
        }
    }

    if (changed) {
        bundleFile.outputStream().use {
            bundleProps.store(it, "Auto-added keys from code scan")
        }
        println("bundle.properties updated!")
    } else {
        println("No missing i18n keys found.")
    }
}
val supportedLangs = listOf("en", "ru", "ja", "zh_CN")

val alignI18nBundles by tasks.registering {
    group = "build"
    description = "Ensure all i18n bundles match the keys and order of bundle.properties"

    val resourcesDir = file("src/main/resources")
    val baseFile = File(resourcesDir, "bundle.properties")
    val baseProps = LinkedHashMap<String, String>().apply {
        if (baseFile.exists()) {
            baseFile.inputStream().use {
                Properties().apply { load(it) }
                    .forEach { k, v -> put(k as String, v as String) }
            }
        }
    }

    supportedLangs.filter { it != "en" }.forEach { lang ->
        val langFile = File(resourcesDir, "bundle_${lang}.properties")
        val langProps = Properties().apply {
            if (langFile.exists()) langFile.inputStream().use { load(it) }
        }
        val langMap = langProps.stringPropertyNames().associateWith { langProps.getProperty(it) }

        val output = StringBuilder()
        output.append("# Synced with bundle.properties\n")
        baseProps.forEach { (key, _) ->
            val value = langMap[key] ?: ""
            output.append("$key=$value\n")
        }
        langFile.writeText(output.toString())
        println("Aligned: bundle_${lang}.properties")
    }
}
plugins {
    java
    kotlin("jvm") version "2.1.20"
}

group = "ity.snow"
version = "1.0"

repositories {
    mavenCentral()
    maven(url = "https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository")
    maven(url = "https://www.jitpack.io")
}


dependencies {
    compileOnly("com.github.Anuken.Arc:arc-core:v149")
    compileOnly("com.github.Anuken.Mindustry:core:v149")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

tasks.jar {
    dependsOn(updateBundleWithUsedKeys)
    dependsOn(alignI18nBundles)
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
}

kotlin {
    jvmToolchain(17)
}