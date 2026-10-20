import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.agronomy.phytomonitor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.agronomy.phytomonitor"
        minSdk = 26
        targetSdk = 37
        versionCode = 21
        versionName = "0.21"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("field") {
            dimension = "distribution"
            versionNameSuffix = "-field"
            buildConfigField("boolean", "IS_LAB", "false")
        }
        create("lab") {
            dimension = "distribution"
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            buildConfigField("boolean", "IS_LAB", "true")
        }
    }
    // Source weights are shared locally; generated flavor trees contain only selected files.
    sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    val signingProperties = providers.gradleProperty("releaseSigningProperties").orNull?.let { path ->
        Properties().apply { file(path).inputStream().use { load(it) } }
    }
    if (signingProperties != null) {
        signingConfigs.create("localRelease") {
            storeFile = file(requireNotNull(signingProperties.getProperty("storeFile")))
            storePassword = requireNotNull(signingProperties.getProperty("storePassword"))
            keyAlias = requireNotNull(signingProperties.getProperty("keyAlias"))
            keyPassword = requireNotNull(signingProperties.getProperty("keyPassword"))
        }
    }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("isolateApp").orNull == "true") {
                applicationIdSuffix = ".verification"
            }
        }
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProperties != null) signingConfig = signingConfigs.getByName("localRelease")
            if (providers.gradleProperty("isolateApp").orNull == "true") applicationIdSuffix = ".verification"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            // Merge runs before abiFilters: sherpa's x86 fallback bundles a second ORT.
            // This app supports ARM only; exclude unsupported ABIs instead of choosing an ORT.
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
        }
    }
}

ksp {
    // Exported schemas are the reference for future Room migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

abstract class PrepareKnowledgeAssets : DefaultTask() {
    @get:InputFile abstract val sourceFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyKnowledge() {
        val target = outputDirectory.get().file("knowledge/kb_v1.json").asFile
        target.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(target, overwrite = true)
    }
}
val copyKnowledge = tasks.register<PrepareKnowledgeAssets>("copyKnowledge") {
    sourceFile.set(rootProject.layout.projectDirectory.file("../kb/kb_v1.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/knowledgeAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyKnowledge, PrepareKnowledgeAssets::outputDirectory)
}

abstract class PrepareFlavorAssets : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val python = providers.gradleProperty("pythonExecutable").getOrElse("python")
val flavorAssets = listOf("field", "lab").associateWith { flavor ->
    tasks.register<PrepareFlavorAssets>("prepare${flavor.replaceFirstChar { it.uppercase() }}Assets") {
        inputs.dir("src/main/assets")
        inputs.files(rootProject.file("prepare_flavor_assets.py"), rootProject.file("fetch_assets.py"))
        inputs.file(rootProject.file("../ml-pipeline/allcrops/taxonomy_v1.json"))
        outputDirectory.set(layout.buildDirectory.dir("generated/flavorAssets/$flavor"))
        commandLine(python, "-X", "utf8", rootProject.file("prepare_flavor_assets.py"), "--flavor", flavor)
    }
}
androidComponents.onVariants { variant ->
    val flavor = variant.productFlavors.first { it.first == "distribution" }.second
    val prepare = flavorAssets.getValue(flavor)
    variant.sources.assets?.addGeneratedSourceDirectory(prepare, PrepareFlavorAssets::outputDirectory)
    val verify = tasks.register<Exec>("verify${variant.name.replaceFirstChar { it.uppercase() }}Assets") {
        dependsOn(prepare)
        commandLine(python, "-X", "utf8", rootProject.file("prepare_flavor_assets.py"), "--flavor", flavor, "--check")
    }
    // JVM/compile checks may omit weights, but an installable APK must never do so.
    tasks.matching { it.name == "package${variant.name.replaceFirstChar { it.uppercase() }}" ||
        it.name == "package${variant.name.replaceFirstChar { it.uppercase() }}Bundle" }.configureEach {
        dependsOn(verify)
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
