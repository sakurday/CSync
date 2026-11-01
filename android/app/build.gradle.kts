import groovy.json.JsonSlurper
import groovy.json.JsonOutput

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }

android {
    namespace = "io.csync.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.csync.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { aidl = true; buildConfig = true; compose = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/csyncLocales"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    lint { abortOnError = true }
}

// The translator edits only /locales; the APK contains the generated catalog.
val embedCsyncLocales by tasks.registering {
    val sources = rootProject.file("../locales")
    val output = layout.buildDirectory.dir("generated/csyncLocales")
    inputs.dir(sources)
    outputs.dir(output)
    doLast {
        val parser = JsonSlurper()
        val schema = parser.parse(sources.resolve("messages.json")) as Map<*, *>
        val catalog = sortedMapOf<String, Any>()
        for (file in sources.listFiles()!!.filter { it.extension == "json" && it.name != "messages.json" }.sortedBy { it.name }) {
            val language = parser.parse(file) as Map<*, *>
            val meta = language["_meta"] as Map<*, *>
            val locale = meta["locale"] as String
            require(locale == file.nameWithoutExtension && !meta["nativeName"].toString().isBlank())
            require(meta["direction"] in listOf("ltr", "rtl"))
            val messages = language["messages"] as Map<*, *>
            require(locale != "qps-keys" || messages.isEmpty()) { "qps-keys must stay empty" }
            for ((key, value) in messages) {
                require(schema.containsKey(key)) { "Unknown translation key: $key" }
                val expected = ((schema[key] as Map<*, *>)["parameters"] as List<*>).toSet()
                val texts = if (value is String) listOf(value) else {
                    require("count" in expected)
                    val forms = value as Map<*, *>
                    require(forms.containsKey("other") && forms.keys.all { it in listOf("zero", "one", "two", "few", "many", "other") })
                    forms.values.map { it as String }
                }
                for (text in texts) if (text.isNotEmpty()) {
                    val actual = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").findAll(text).map { it.groupValues[1] }.toSet()
                    require(actual == expected) { "Translation parameters differ: $key in ${file.name}" }
                }
            }
            require(catalog.put(locale, language) == null)
        }
        require(catalog.containsKey("zh-Hans") && catalog.containsKey("qps-keys"))
        output.get().asFile.apply { mkdirs() }.resolve("csync-locales.json").writeText(JsonOutput.toJson(catalog), Charsets.UTF_8)
    }
}
tasks.named("preBuild").configure { dependsOn(embedCsyncLocales) }

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

