// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.play.publisher) apply false
}

// detekt 1.23 can't run on the Gradle daemon's JDK 25, so its CLI runs on a JDK 21 toolchain.
val detektCli = libs.detekt.cli
subprojects {
    val detektClasspath = configurations.create("detekt")
    dependencies.addProvider(detektClasspath.name, detektCli)
    val sources = files("src/main/java", "src/main/kotlin")
    val config = rootProject.file("detekt.yml")
    val report = layout.buildDirectory.file("reports/detekt/detekt.txt")
    tasks.register<JavaExec>("detekt") {
        group = "verification"
        description = "Checks that public declarations are documented."
        classpath = detektClasspath
        mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(config).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(report)
        outputs.cacheIf { true }
        args(
            "--input", sources.filter { it.exists() }.joinToString(",") { it.path },
            "--config", config.path,
            "--build-upon-default-config",
            // Copied from another project and kept identical to it.
            "--excludes", "**/core/snooze/SnoozeSuggestions.kt,**/core/snooze/SnoozeStatsWire.kt",
            "--report", "txt:${report.get().asFile.path}",
        )
    }
}
