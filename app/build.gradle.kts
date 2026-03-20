plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    jacoco
}

android {
    namespace = "com.psjostrom.strimma"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystoreFile = providers.gradleProperty("STRIMMA_KEYSTORE_FILE")
                .orElse(providers.environmentVariable("STRIMMA_KEYSTORE_FILE"))
                .orNull
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = providers.gradleProperty("STRIMMA_KEYSTORE_PASSWORD")
                    .orElse(providers.environmentVariable("STRIMMA_KEYSTORE_PASSWORD")).get()
                keyAlias = providers.gradleProperty("STRIMMA_KEY_ALIAS")
                    .orElse(providers.environmentVariable("STRIMMA_KEY_ALIAS")).get()
                keyPassword = providers.gradleProperty("STRIMMA_KEY_PASSWORD")
                    .orElse(providers.environmentVariable("STRIMMA_KEY_PASSWORD")).get()
            }
        }
    }

    defaultConfig {
        applicationId = "com.psjostrom.strimma"
        minSdk = 33 // Only targets devices still receiving security updates — this is medical data
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
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
    val debugTree = fileTree("$buildDir/tmp/kotlin-classes/debug") {
        exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*",
            "**/Manifest*.*", "**/*_Hilt*.*", "**/Hilt_*.*",
            "**/*_Factory.*", "**/*_MembersInjector.*",
            "**/*Database_Impl*.*", "**/*Dao_Impl*.*")
    }
    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(debugTree)
    executionData.setFrom(fileTree(buildDir) { include("jacoco/testDebugUnitTest.exec") })
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

    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
