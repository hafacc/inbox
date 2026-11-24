import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.play.publisher)
}

// Firebase config comes from FirebaseConfig.kt; app/google-services.json (never committed) takes
// over when it is present.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// The Android framework jars Robolectric runs the tests on, one configuration each, since in one Gradle would keep
// only the newest version.
val robolectricSdk35: Configuration by configurations.creating { isTransitive = false }
val robolectricSdk36: Configuration by configurations.creating { isTransitive = false }

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(name: String): String? =
    keystoreProperties.getProperty(name) ?: providers.gradleProperty("inbox.$name").orNull

android {
    namespace = "cc.hafa.inbox"
    // Bouncy Castle's jars each carry the same license files.
    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.hafa.inbox"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }

    // storeFile, storePassword, keyAlias and keyPassword come from android/keystore.properties
    // (git-ignored), or from -Pinbox.<name> in CI. Without them release is debug-signed, which
    // installs locally but Play won't accept.
    val uploadStoreFile = signingSetting("storeFile")
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = signingSetting("storePassword")
                keyAlias = signingSetting("keyAlias")
                keyPassword = signingSetting("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(
                if (uploadStoreFile != null) "upload" else "debug"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // Shared with web: the composer's typing fixtures (shared/testdata/rich-fixtures.json).
        getByName("test").resources.srcDir("../../shared/testdata")
    }
    lint {
        warningsAsErrors = true
        // Version nags appear on their own as time passes.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Wextra")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// publishReleaseBundle uploads to Play; credentials come from ANDROID_PUBLISHER_CREDENTIALS (.github/workflows/publish-android.yml).
play {
    track.set("internal")
    defaultToAppBundles.set(true)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room writes its DAO code in Java, which Kotlin's -Wextra checks don't cover; its Kotlin output trips them.
ksp {
    arg("room.generateKotlin", "false")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)

    // Unsubscribe pages open in a Custom Tab.
    implementation(libs.androidx.browser)

    // The composer's rich text, which markdown typed into it turns into.
    implementation(libs.rich.editor)

    // Contact photos, cached on disk by URL.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Passkeys with the PRF extension unlock encrypted mail (docs/pgp-contract.md).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)

    // AuthorizationClient: Gmail scopes with an offline server auth code.
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    // Compose UI tests on the JVM, for list animations frame by frame.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    robolectricSdk35(libs.robolectric.android.all.sdk35)
    robolectricSdk36(libs.robolectric.android.all.sdk36)
}

// Robolectric would download these when a test starts; Gradle fetches and caches them instead, and the tests
// download nothing.
val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricSdk35, robolectricSdk36)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

/** JVM flags that point Robolectric at the jars Gradle fetched. */
abstract class RobolectricOffline : CommandLineArgumentProvider {
    /** folder holding the jars, named as Robolectric looks for them */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jarDir: DirectoryProperty

    override fun asArguments(): List<String> =
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${jarDir.get().asFile.path}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(
        objects.newInstance<RobolectricOffline>().apply { jarDir.fileProvider(syncRobolectricJars.map { it.destinationDir }) },
    )
}
