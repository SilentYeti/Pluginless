plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.148"
}

fun prop(name: String): String = property(name) as String

val mcVersion = stonecutter.current.version

group = prop("mod.group")
version = "${prop("mod.version")}+$mcVersion"
base.archivesName = prop("mod.id")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(prop("java_version").toInt())
}

repositories {
    mavenCentral()
    maven("https://maven.firstdark.dev/snapshots") { name = "FirstDark" } // LDLib2
    maven("https://maven.firstdark.dev/releases") { name = "FirstDark Releases" }
    maven("https://modmaven.dev") { name = "ModMaven" } // AE2 Yoga (LDLib2 transitive)
}

neoForge {
    version = prop("deps.neoforge")

    // Parchment stops at the last obfuscated release; newer nodes leave deps.parchment blank.
    (findProperty("deps.parchment") as String?)?.takeIf { it.isNotBlank() }?.let {
        parchment {
            mappingsVersion = it
            minecraftVersion = mcVersion
        }
    }

    runs {
        configureEach {
            ideName = "${name.replaceFirstChar { it.uppercase() }} ($mcVersion)"
        }
        register("client") {
            client()
            gameDirectory = file("runs/client")
        }
        register("server") {
            server()
            programArgument("--nogui")
            gameDirectory = file("runs/server")
        }
    }

    // Headless feature checks, driven by tools/selftest.py. The property makes SelfTest run at server start.
    runs.register("selftest") {
        server()
        programArgument("--nogui")
        gameDirectory = file("runs/selftest")
        systemProperty("pluginless.selftest", "true")
    }

    mods {
        register(prop("mod.id")) { sourceSet(sourceSets["main"]) }
    }
}

dependencies {
    val ldlibClassifier = prop("deps.ldlib2_classifier").let { if (it.isBlank()) "" else ":$it" }
    implementation("com.lowdragmc.ldlib2:ldlib2-neoforge-${prop("deps.ldlib2_mc")}:${prop("deps.ldlib2")}$ldlibClassifier")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val props = mapOf(
        "mod_id" to prop("mod.id"),
        "mod_name" to prop("mod.name"),
        "mod_version" to prop("mod.version"),
        "mod_author" to prop("mod.author"),
        "mod_license" to prop("mod.license"),
        "mod_description" to prop("mod.description"),
        "minecraft_range" to prop("deps.minecraft_range"),
        "neoforge_version" to prop("deps.neoforge"),
        "ldlib2_version" to prop("deps.ldlib2"),
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

// Stonecutter rewrites the shared src/ tree for the active node; nothing may read it before that.
tasks.named("createMinecraftArtifacts") { dependsOn(tasks.named("stonecutterGenerate")) }
tasks.compileJava { dependsOn(tasks.named("stonecutterGenerate")) }
tasks.processResources { dependsOn(tasks.named("stonecutterGenerate")) }

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("stonecutterGenerateTest"))
}
