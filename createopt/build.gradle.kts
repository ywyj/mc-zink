plugins {
    id("java")
    id("net.neoforged.moddev") version "2.0.141"
}

group = "com.dsh.createopt"
version = "1.1.0"

// -Pprofile=lite 构建零 mixin 的事件剔除包（createopt-zink-lite-1.1.0.jar）
val isLite = (findProperty("profile") as String?)?.equals("lite", ignoreCase = true) ?: false

base {
    archivesName = if (isLite) "createopt-zink-lite" else "createopt-zink"
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

neoForge {
    version = "21.1.229"
}

if (isLite) {
    sourceSets.main.get().resources.srcDir("src/lite/resources")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

tasks.withType<ProcessResources> {
    if (isLite) exclude("createopt.mixins.json")
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            mapOf(
                "version" to project.version,
                "mixins_block" to if (isLite) "" else "[[mixins]]\nconfig = \"createopt.mixins.json\""
            )
        )
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
    // lite 包是"零 mixin"版本：不让未被引用的 mixin class 混进 jar，便于审核
    if (isLite) exclude("com/dsh/createopt/mixin/**")
}