/*
 * Copyright (C) 2019-2024 qwq233 <qwq233@qwq2333.top>
 * https://github.com/qwq233/Nullgram
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this software.
 *  If not, see
 * <https://www.gnu.org/licenses/>
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import org.telegram.tasks.GenerateIdentityResTask
import org.telegram.tasks.TelegramStringsTask
import org.telegram.tasks.localization.GenerateLocalizationUtilsJavaTask

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.triplet.play)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    id("org.telegram.build-plugin")
    id("org.telegram.build-app-plugin")
    //alias(libs.plugins.rust)
}

configurations {
    all {
        exclude(group = "androidx.recyclerview", module = "recyclerview")
    }
}

val abiName = mapOf("arm64-v8a" to "arm64")


val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

// Install identity. Changing NNN_APP_ID (gradle.properties or -PNNN_APP_ID=...) produces an
// APK that installs alongside the others: every OS-visible identifier (providers, account
// type, contact mime types, FileProvider, upgrade broadcast, data dir fallback) is derived
// from applicationId. The launcher name follows the suffix: nnngramx -> NnngramX,
// nnngrama -> NnngramX-a. res/xml templates cannot use ${applicationId}, so they are
// expanded by GenerateIdentityResTask.
val appId: String = (findProperty("NNN_APP_ID") as String?)
    ?: localProperties.getProperty("NNN_APP_ID")
    ?: error("NNN_APP_ID is missing: set it in gradle.properties (default install identity)")
val appBaseName = "NnngramX"
val appIdSuffix = Regex("""nnngram(\w*)$""").find(appId)?.groupValues?.get(1).orEmpty()
val appLabel: String =
    if (appIdSuffix.isEmpty() || appIdSuffix == "x") appBaseName else "$appBaseName-$appIdSuffix"
val XRAY_AAR = "libs/xray/build/xraybridge.aar"
val GOMOBILE_MODULE = "golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867"

// Builds the Xray-core bridge AAR with the pinned gomobile version. Invoked
// through `go run` so no shell and no global tool install is required on either
// Linux CI or Windows; libs/xray/build.sh is the equivalent manual entry point.
val buildXrayBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Xray-core bridge AAR with gomobile"
    workingDir = rootProject.file("libs/xray")
    commandLine(
        "go", "run", GOMOBILE_MODULE, "bind",
        "-target", "android/arm64",
        "-androidapi", "27",
        "-trimpath",
        "-o", "build/xraybridge.aar",
        "-ldflags=-checklinkname=0 -s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384",
        ".",
    )
    inputs.files(
        rootProject.fileTree("libs/xray") {
            include("**/*.go", "go.mod", "go.sum", "build.sh", "licenses/**")
            exclude("build/**")
        }
    )
    outputs.file(rootProject.layout.projectDirectory.file(XRAY_AAR))
    // Empty values would wipe the inherited environment, so only forward real ones.
    listOf("ANDROID_HOME", "ANDROID_NDK_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { environment(key, it) }
    }
    // gomobile picks the NDK from ANDROID_NDK_HOME; mirror the app's pinned NDK.
    if (System.getenv("ANDROID_NDK_HOME").isNullOrBlank()) {
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let {
            environment("ANDROID_NDK_HOME", "$it/ndk/28.2.13676358")
        }
    }
}

val nnngramBuildArgs: String = localProperties.getProperty("NNNGRAM_BUILD_ARGS") ?: System.getenv("NNNGRAM_BUILD_ARGS") ?: ""

//fun setupPlay(stable: Boolean) {
//    val targetTrace = if (stable) "production" else "beta"
//    play {
//        track.set(targetTrace)
//        defaultToAppBundles.set(true)
//    }
//}

//cargo {
//    module  = "../libs/rust"
//    libname = "rust"
//    targets = listOf("arm64", "arm")
//
//    prebuiltToolchains = true
//    profile = "release"
//}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.palette.ktx)
    implementation(libs.exifinterface)
    implementation(libs.dynamicanimation)
    implementation(libs.interpolator)
    implementation(libs.fragment)
    implementation(libs.sharetarget)
    implementation(libs.biometric)
    implementation(libs.webkit)

    compileOnly(libs.checker.compat.qual)
    compileOnly(libs.checker.qual)
    implementation(libs.play.services.vision)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    implementation(libs.play.services.wallet)
    implementation(libs.play.services.mlkit.vision)
    implementation(libs.play.services.mlkit.imageLabeling)
    implementation(libs.play.services.cast.framework)
    implementation(libs.isoparser)
    implementation(files("libs/stripe.aar"))
    implementation(libs.language.id)
    implementation(files("libs/libgsaverification-client.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.process.phoenix)
    implementation(libs.hiddenapibypass)
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    implementation(libs.nanohttpd)
    implementation(libs.mediarouter)
    implementation(libs.zxingcore)
    implementation(libs.nv.websocket.client)
    implementation(libs.recaptcha)
    implementation(libs.multidex)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.ext.latex) {
        exclude("ru.noties", "jlatexmath-android")
    }

    implementation(libs.kotlin.stdlib.common)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.osmdroid.android)
    implementation(libs.guava)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.client.contentNegotiation)
    implementation(libs.ktor.serialization.json)

    implementation(libs.lottie)

    implementation(project(":libs:tcp2ws"))
    implementation(project(":libs:pangu"))
    ksp(project(":libs:ksp"))

    // Xray-core bridge: Go sources live in libs/xray, the AAR is produced by
    // :TMessagesProj:buildXrayBridge (gomobile bind, arm64 only).
    implementation(files(rootProject.layout.projectDirectory.file(XRAY_AAR)).builtBy(buildXrayBridge))

    api(project(":TMessagesProj_Modules:media3:media-lib-exoplayer"))
    api(project(":TMessagesProj_Modules:media3:media-lib-exoplayer-dash"))
    api(project(":TMessagesProj_Modules:media3:media-lib-exoplayer-hls"))
    api(project(":TMessagesProj_Modules:media3:media-lib-decoder-ffmpeg"))
    api(project(":TMessagesProj_Modules:media3:media-lib-session"))
    api(project(":jlatexmath"))
}

// Captured for the variant-scoped identity task; inside onVariants { } `appLabel`
// would resolve against the variant receiver.
val identityAppLabel = appLabel

android {
    defaultConfig.applicationId = appId
    namespace = "org.telegram.messenger"

    androidComponents {
        onVariants { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val identityRes = tasks.register<GenerateIdentityResTask>("generate${suffix}IdentityRes") {
                applicationId.set(variant.applicationId)
                appLabel.set(identityAppLabel)
                templates.set(layout.projectDirectory.dir("identity/xml"))
                outputDir.set(layout.buildDirectory.dir("generated/identityRes/${variant.name}/res"))
            }
            variant.sources.res?.addGeneratedSourceDirectory(identityRes, GenerateIdentityResTask::outputDir)
        }
    }

    sourceSets.getByName("main") {
        java.srcDir("src/main/java")
        jniLibs.srcDirs("./jni/")
    }

    externalNativeBuild {
        cmake {
            path = File(projectDir, "jni/CMakeLists.txt")
        }
    }

    lint {
        checkReleaseBuilds = true
        disable += listOf(
            "MissingTranslation", "ExtraTranslation", "BlockedPrivateApi"
        )
    }

    androidResources {
        noCompress += "pack"
        localeFilters += "zz"
    }

    packaging {
        resources.excludes += "**"
        jniLibs.pickFirsts.add("lib/arm64-v8a/libtmessages.*.so")
    }

    kotlin {
        jvmToolchain(Version.java.toString().toInt())
    }

    signingConfigs {
        create("release") {
            storeFile = File(projectDir, "config/release.keystore")
            gradleLocalProperties(rootDir, providers).apply {
                storePassword = getProperty("RELEASE_STORE_PASSWORD", System.getenv("KEYSTORE_PASS"))
                keyAlias = getProperty("RELEASE_KEY_ALIAS", System.getenv("ALIAS_NAME"))
                keyPassword = getProperty("RELEASE_KEY_PASSWORD", System.getenv("ALIAS_PASS"))
            }

            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(File(projectDir, "proguard-rules.pro"))
        }

        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
            isDefault = true
            isDebuggable = true
            isJniDebuggable = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-27",
                    "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                    "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    "-DNDK_CCACHE=ccache",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
        buildConfigField("String", "BUILD_TIME", "\"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\"")
        buildConfigField("String", "OFFICIAL_APP_VERSION_NAME", "\"${properties["APP_VERSION_NAME"]}\"")
        buildConfigField("int", "OFFICIAL_APP_VERSION_CODE", "${properties["APP_VERSION_CODE"]}")
        // Read directly by upstream code; same values as upstream's release build types.
        buildConfigField("boolean", "DEBUG_VERSION", "false")
        buildConfigField("boolean", "DEBUG_PRIVATE_VERSION", "false")
    }

    tasks.withType<JavaCompile> {
        options.forkOptions.jvmArgs = listOf("-Xmx4g")
    }

    splits {
        abi {
            isEnable = true
            reset()
            //noinspection ChromeOsAbiSupport
            include("arm64-v8a")
        }
    }

//    androidComponents {
//        onVariants { variant ->
//            variant.buildConfigFields!!.put("isPlay", BuildConfigField("boolean", variant.name.lowercase() == "play", null))
//        }
//    }

    tasks.configureEach {
        when {
            (name.contains("configureCMakeDebug") || name.contains("buildCMakeDebug")) && nnngramBuildArgs.contains("skipBuildCMakeDebug") -> {
                enabled = false
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            val abi = this.filters.find { it.filterType == FilterConfiguration.FilterType.ABI.name }?.identifier
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val outputFileName = "${appLabel}-${defaultConfig.versionName}-${abiName[abi]}.apk"
            output?.outputFileName = outputFileName
        }
    }
}

kotlin {
    sourceSets.configureEach {
        kotlin.srcDir("${layout.buildDirectory.asFile.get().absolutePath}/generated/ksp/$name/kotlin/")
    }
}

// Upstream packs strings.xml into binary localization assets and strips the string resources.
// Fork strings live in strings_nullgram.xml, so they have to go through the same pipeline.
tasks.withType<TelegramStringsTask>().configureEach {
    stringsXml.from(fileTree("src/main/res/values") { include("strings_nullgram.xml") })
    localizationFiles.from(fileTree("src/main/res") { include("values-*/strings_nullgram.xml") })
}

tasks.withType<GenerateLocalizationUtilsJavaTask>().configureEach {
    localizationFiles.from(fileTree("src/main/res") { include("values-*/strings_nullgram.xml") })
}
