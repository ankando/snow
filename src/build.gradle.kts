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
    implementation("com.maxmind.geoip2:geoip2:4.3.0")
}

tasks.jar {
    archiveFileName.set("${project.name}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })
}

kotlin {
    jvmToolchain(17)
}