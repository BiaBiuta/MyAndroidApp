
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.ksp)

    id("org.jetbrains.kotlin.plugin.parcelize")


}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.example.myandroidapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myandroidapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Adresa serverului (folderul server/ din repo). 10.0.2.2 = calculatorul gazda, vazut din emulator.
        // Pentru un telefon real: IP-ul calculatorului in reteaua Wi-Fi, ex. "192.168.1.7:3000"
        // (si adaugat in res/xml/network_security_config.xml).
        buildConfigField("String", "SERVER_HOST", "\"10.0.2.2:3000\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation (libs.kotlinx.coroutines.core.v172)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.converter.gson)
    implementation(libs.converter.moshi)
    implementation(libs.okhttp.logging.interceptor)
    // Room
    implementation("androidx.work:work-runtime-ktx:2.7.1")

    implementation(libs.play.services.cast)
//    implementation(libs.androidx.ui.desktop)
//     kapt("androidx.room:room-compiler:2.6.0")
//    implementation("androidx.room:room-runtime:2.6.0")
    implementation("com.google.accompanist:accompanist-permissions:0.23.1")

    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Harta: OpenStreetMap (osmdroid) - gratuita, fara cheie API (in locul Google Maps)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
//    implementation(libs.androidx.room.runtime)
//    implementation(libs.androidx.adapters)
//    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    // Data store
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    //imagine
    implementation(libs.coil.compose)
    implementation(libs.play.services.location)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation ("androidx.compose.ui:ui:1.4.0")
    implementation ("androidx.compose.material:material:1.4.0")
    implementation ("androidx.compose.material3:material3:1.0.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

}