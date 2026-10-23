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
    // Una copia aparte para probar sin tocar la instalación real: ./gradlew assembleDebug -PidSuffix=.prueba
    buildTypes { getByName("debug") { applicationIdSuffix = providers.gradleProperty("idSuffix").orNull } }
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
