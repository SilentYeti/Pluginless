plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1" /* [SC] DO NOT EDIT */

// Builds every registered Minecraft version in one invocation.
tasks.register("chiseledBuild") {
    group = "build"
    description = "Builds every registered version node."
    dependsOn(stonecutter.tasks.named("build"))
}

stonecutter parameters {
    // Mechanical renames, declared 1.21.1 -> newer because 1.21.1 is the source of truth. Only names
    // that cannot occur in the other era belong here; anything else gets a `//? if` branch.
    replacements {
        string(current.parsed >= "26.1") {
            replace("ResourceLocation", "Identifier")
        }
    }
}
