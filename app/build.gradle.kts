plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.getoff"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.getoff"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // La clave de release vive fuera del repo, en ~/.gradle/gradle.properties (offStoreFile, offStorePassword,
    // offKeyAlias, offKeyPassword). Es la misma para siempre: con otra, el APK nuevo no se instala encima.
    val storeFile = providers.gradleProperty("offStoreFile").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("offStorePassword").get()
            keyAlias = providers.gradleProperty("offKeyAlias").get()
            keyPassword = providers.gradleProperty("offKeyPassword").get()
        }
    }
    buildTypes {
        // Una copia aparte para probar sin tocar la instalación real: ./gradlew assembleDebug -PidSuffix=.prueba
        getByName("debug") { applicationIdSuffix = providers.gradleProperty("idSuffix").orNull }
        getByName("release") { signingConfig = signingConfigs.findByName("release") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.android.installreferrer:installreferrer:2.2") // invitaciones: el grupo viaja en el link de Play
    testImplementation("junit:junit:4.13.2")
}
