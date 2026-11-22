plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjdk-release=11")
        freeCompilerArgs.add("-Wextra")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

sourceSets {
    main {
        // The EFF wordlist recovery phrases are made from, the color themes, and the reserved
        // @hafa.cc names, all shared with web (and the names with the server).
        resources.srcDir("../../shared/wordlists")
        resources.srcDir("../../shared/themes")
        resources.srcDir("../../shared/address")
        resources.include("*.txt", "themes.json", "css-colors.json", "label-icons.json", "reserved-names.json")
    }
    test {
        // Shared with web: shared/src/*.spec.ts read the same fixtures.
        resources.srcDir("../../shared/testdata")
    }
}

// PgpInteropTest checks Bouncy Castle against openpgp.js, with keys made on each run:
// shared/src/pgp/interop.ts writes web's messages before the test and checks Android's after it.
// The files hold private keys, so they stay in build/.
val sharedDir = rootProject.layout.projectDirectory.dir("../shared")
val interopDir = layout.buildDirectory.dir("generated/pgp-interop")
val webMessages = interopDir.map { it.file("web-messages.json") }
val androidMessages = interopDir.map { it.file("android-messages.json") }
val bunOnPath: Provider<String> = providers.environmentVariable("PATH").orElse("").map { path ->
    path.split(File.pathSeparator).flatMap { listOf(File(it, "bun"), File(it, "bun.exe")) }
        .firstOrNull { it.isFile && it.canExecute() }?.path.orEmpty()
}

fun Exec.runInterop(command: String, file: Provider<RegularFile>) {
    val bun = bunOnPath
    workingDir = sharedDir.asFile
    inputs.dir(sharedDir.dir("src/pgp"))
    inputs.file(sharedDir.file("testdata/pgp-fixtures.json"))
    argumentProviders.add { listOf("src/pgp/interop.ts", command, file.get().asFile.path) }
    doFirst {
        executable = bun.get().ifEmpty {
            throw GradleException(
                "bun is not on PATH, and :core:test needs it for the OpenPGP check against openpgp.js. " +
                    "Install bun (https://bun.sh), run `bun install` at the repo root, then run Gradle again.",
            )
        }
    }
}

val writeWebPgpMessages by tasks.registering(Exec::class) {
    description = "Makes keys and the PGP fixture messages with openpgp.js, for PgpInteropTest."
    runInterop("write", webMessages)
    outputs.file(webMessages)
}

val checkAndroidPgpMessages by tasks.registering(Exec::class) {
    description = "Checks that openpgp.js decrypts the PGP fixture messages PgpInteropTest made."
    val messages = androidMessages
    runInterop("check", messages)
    inputs.file(messages)
    // Not there when PgpInteropTest failed before writing it, or a test filter left it out.
    onlyIf { messages.get().asFile.exists() }
}

tasks.test {
    // The shared fixtures and web specs run in New York time (shared/test-setup.ts).
    systemProperty("user.timezone", "America/New_York")
    dependsOn(writeWebPgpMessages)
    inputs.file(webMessages)
    outputs.file(androidMessages)
    val web = webMessages
    val android = androidMessages
    jvmArgumentProviders.add {
        listOf(
            "-Dpgp.interop.web=${web.get().asFile.path}",
            "-Dpgp.interop.android=${android.get().asFile.path}",
        )
    }
    finalizedBy(checkAndroidPgpMessages)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // CSS parsing for dark mode's turning of mail colors, which web does with css-tree.
    implementation(libs.htmlunit.cssparser)
    // iCalendar parsing and VTIMEZONE resolution for invites (docs/shared-contract.md).
    implementation(libs.biweekly)
    // OpenPGP (keys, PGP/MIME) and Argon2id for recovery phrases (docs/pgp-contract.md).
    implementation(libs.bcpg)
    implementation(libs.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
