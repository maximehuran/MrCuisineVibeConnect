import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.toque"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.toque"
        minSdk = 23          // Android 6.0, celui du Monsieur Cuisine Connect
        targetSdk = 28
        // Pour publier une mise à jour : augmenter les deux valeurs puis pousser sur GitHub
        // (la version est publiée automatiquement, voir .github/workflows/release.yml).
        versionCode = 8
        versionName = "0.5.1"
    }

    signingConfigs {
        // Clé du projet, publique exprès : toutes les compilations (PC, GitHub) portent la même
        // signature, donc chaque mise à jour s'installe par-dessus la précédente.
        create("toque") {
            storeFile = file("signature/toque.p12")
            storeType = "PKCS12"
            storePassword = "toque-public"
            keyAlias = "toque"
            keyPassword = "toque-public"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("toque")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("toque")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

// BEGIN DISTRIBUTIONS: also appended to the pristine upstream reference checkout by CI.
android.buildFeatures.buildConfig = true
android.defaultConfig {
    buildConfigField("String", "TOQUE_EDITION", "\"${providers.gradleProperty("toqueEdition").orElse("fork").get()}\"")
    manifestPlaceholders["toqueEdition"] = providers.gradleProperty("toqueEdition").orElse("fork").get()
    testInstrumentationRunner = "app.toque.EditionSmoke"
}
// Fork release numbering is supplied by CI. Keep the upstream defaults above mergeable.
if (providers.gradleProperty("forkVersionCode").isPresent) {
    android.defaultConfig {
        versionCode = providers.gradleProperty("forkVersionCode").get().toInt()
        versionName = providers.gradleProperty("forkVersionName").get()
    }
}
// END DISTRIBUTIONS

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":core"))
}
