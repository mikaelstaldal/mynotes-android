// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.openapi.generator)
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("${rootProject.projectDir}/../mynotes/openapi.yaml")
    outputDir.set("${layout.buildDirectory.get().asFile}/generated/openapi")
    apiPackage.set("nu.staldal.mynotes.data.api")
    modelPackage.set("nu.staldal.mynotes.data.api")
    invokerPackage.set("nu.staldal.mynotes.data.api")
    configOptions.set(mapOf(
        "library" to "jvm-retrofit2",
        "useCoroutines" to "true",
        "serializationLibrary" to "gson",
        "dateLibrary" to "string",
    ))
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

// Signing key shared by the My* apps, which reads notes through
// NotesProvider under a signature-level permission — so the two apps must be signed with the same
// key for that permission to be granted. The default ~/.android/debug.keystore would satisfy that,
// but it is a poor trust anchor: world-readable, fixed password "android", and shared by every
// debug APK built on the machine, any of which would then be able to read all your notes.
//
// Configure it in local.properties (kept out of version control), or through the matching
// environment variables for CI:
//
//     debugKeystore=/path/to/staldal-apps.keystore   DEBUG_KEYSTORE
//     debugKeystorePassword=…                        DEBUG_KEYSTORE_PASSWORD
//     debugKeyAlias=staldal-apps                     DEBUG_KEY_ALIAS
//     debugKeyPassword=…                             DEBUG_KEY_PASSWORD
//
// Absent or incomplete, the build still works but falls back to the default debug key and says so.
// Both apps then fall back alike, so the integration keeps working — it is the trust boundary that
// weakens, which is exactly the thing that must not happen quietly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(key: String, env: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "nu.staldal.mynotes"
    compileSdk = 36

    signingConfigs {
        // Overrides the built-in debug config, which debug and androidTest builds already use.
        getByName("debug") {
            val store = signingProperty("debugKeystore", "DEBUG_KEYSTORE")?.let(::file)
            val storePw = signingProperty("debugKeystorePassword", "DEBUG_KEYSTORE_PASSWORD")
            val alias = signingProperty("debugKeyAlias", "DEBUG_KEY_ALIAS")
            val keyPw = signingProperty("debugKeyPassword", "DEBUG_KEY_PASSWORD")
            if (store?.exists() == true && storePw != null && alias != null && keyPw != null) {
                storeFile = store
                storeType = "PKCS12"
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            } else {
                logger.warn(
                    "MyNotes: no shared debug signing key configured (see app/build.gradle.kts); " +
                        "falling back to the default debug keystore, which any debug app on this " +
                        "machine can impersonate to read your notes."
                )
            }
        }
    }

    defaultConfig {
        applicationId = "nu.staldal.mynotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets {
        getByName("main") {
            java.srcDir("${layout.buildDirectory.get().asFile}/generated/openapi/src/main/kotlin")
        }
    }
}

// The empty-string enum value "" generates a missing Kotlin identifier in the template.
// Patch it to EMPTY after generation.
// Also remove the HttpLoggingInterceptor from the generated ApiClient (it's dead code and
// logs Level.BODY unconditionally, which would expose credentials and request bodies).
tasks.named("openApiGenerate").configure {
    doLast {
        fileTree("${layout.buildDirectory.get().asFile}/generated/openapi/src/main/kotlin")
            .filter { it.name.endsWith(".kt") }
            .forEach { file ->
                val original = file.readText()
                var patched = original.replace(
                    """@SerializedName(value = "") (""",
                    """@SerializedName(value = "") EMPTY("""
                )
                patched = patched.replace(
                    "import okhttp3.logging.HttpLoggingInterceptor\n",
                    ""
                )
                patched = patched.replace(
                    """            .addInterceptor(HttpLoggingInterceptor { message -> logger?.invoke(message) }
                .apply { level = HttpLoggingInterceptor.Level.BODY }
            )
""",
                    ""
                )
                if (patched != original) file.writeText(patched)
            }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("openApiGenerate")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    // Markdown is rendered by the vendored MyNotes render kit in a WebView
    // (assets/renderer/, refreshed by tools/sync-renderer.sh), not on the JVM —
    // hence no commonmark/HTML-sanitizer dependencies. androidx.webkit supplies
    // WebViewAssetLoader, which serves that kit to the WebView over a real origin.
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

}
