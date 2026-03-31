plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    jacoco
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.psjostrom.strimma"
    compileSdk = 36

    signingConfigs {
        create("release") {
            fun prop(key: String): String? =
                providers.gradleProperty(key).orNull
                    ?: providers.environmentVariable(key).orNull
            val keystoreFile = prop("STRIMMA_KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = prop("STRIMMA_KEYSTORE_PASSWORD")!!
                keyAlias = prop("STRIMMA_KEY_ALIAS")!!
                keyPassword = prop("STRIMMA_KEY_PASSWORD")!!
            }
        }
    }

    defaultConfig {
        applicationId = "com.psjostrom.strimma"
        minSdk = 33 // Only targets devices still receiving security updates — this is medical data
        targetSdk = 36
        versionCode = 2
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        manifestPlaceholders["appAuthRedirectScheme"] = "strimma"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val mainSrc = "$projectDir/src/main/java"
    val debugTree = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*",
            "**/Manifest*.*", "**/*_Hilt*.*", "**/Hilt_*.*",
            "**/*_Factory.*", "**/*_MembersInjector.*",
            "**/*Database_Impl*.*", "**/*Dao_Impl*.*")
    }
    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(debugTree)
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") })
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.bottomsheetdialog.compose)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.androidx.appcompat)
    implementation(libs.appauth)
    implementation(libs.health.connect)

    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}
