plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "io.csync.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.csync.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true; compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Export resolved runtime libraries for the checked-in About inventory.
tasks.register("exportOpenSourceDependencies") {
    doLast {
        for (variant in listOf("debug", "release")) {
            val target = rootProject.file("../.build/android-open-source-$variant.tsv")
            target.parentFile.mkdirs()
            target.writeText(configurations.getByName("${variant}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }.distinctBy { "${it.group}:${it.name}:${it.version}" }
                .sortedBy { "${it.group}:${it.name}" }
                .joinToString("\n") { "${it.group}\t${it.name}\t${it.version}" })
        }
    }
}

