plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.141"
}

group = "com.dsh.zinkgl"
version = "0.4.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

neoForge { version = "21.1.229" }

dependencies {
    compileOnly(files("<MC目录>/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar"))
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
tasks.withType<ProcessResources> { filesMatching("META-INF/neoforge.mods.toml") { expand(mapOf("version" to project.version)) } }
