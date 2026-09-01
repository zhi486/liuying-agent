plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val webUiOverride = providers.gradleProperty("webUiEnabled").orNull?.let { raw ->
    raw.toBooleanStrictOrNull()
        ?: throw GradleException("webUiEnabled 只能是 true 或 false，实际为：$raw")
}
val frontendDir = rootProject.layout.projectDirectory.dir("frontend")
val generatedWebAssets = layout.buildDirectory.dir("generated/webAssets")
val corepackExecutable = if (System.getProperty("os.name").startsWith("Windows")) {
    "corepack.cmd"
} else {
    "corepack"
}

android {
    namespace = "com.healthyagent.running"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.healthyagent.running"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "WEB_UI_ENABLED", (webUiOverride ?: false).toString())
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "WEB_UI_ENABLED", (webUiOverride ?: true).toString())
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
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedWebAssets)
}

dependencies {
    // Compose BOM — 统一管理版本
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose 核心
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Koin 依赖注入
    val koinVersion = "4.0.2"
    implementation("io.insert-koin:koin-android:$koinVersion")
    implementation("io.insert-koin:koin-androidx-compose:$koinVersion")

    // Room 持久化没反应
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore 偏好设置
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // 网络：Retrofit + OkHttp + kotlinx-serialization
    val retrofitVersion = "2.11.0"
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Timber 日志
    implementation("com.jakewharton.timber:timber:5.0.1")

    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val installFrontendDependencies by tasks.registering(Exec::class) {
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "install", "--frozen-lockfile")
    inputs.files(
        frontendDir.file(".node-version"),
        frontendDir.file(".npmrc"),
        frontendDir.file("package.json"),
        frontendDir.file("pnpm-lock.yaml"),
        frontendDir.file("pnpm-workspace.yaml"),
    )
    outputs.dir(frontendDir.dir("node_modules"))
}

val checkFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontendDependencies)
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "run", "check")
    inputs.dir(frontendDir.dir("scripts"))
    inputs.dir(frontendDir.dir("src"))
    inputs.files(
        frontendDir.file(".prettierignore"),
        frontendDir.file(".prettierrc.json"),
        frontendDir.file("eslint.config.mjs"),
        frontendDir.file("index.html"),
        frontendDir.file("package.json"),
        frontendDir.file("tsconfig.app.json"),
        frontendDir.file("tsconfig.json"),
        frontendDir.file("tsconfig.node.json"),
        frontendDir.file("vite.config.ts"),
        frontendDir.file("vitest.config.ts"),
    )
}

val verifyFrontendBundle by tasks.registering(Exec::class) {
    dependsOn(checkFrontend)
    workingDir(frontendDir.asFile)
    commandLine(corepackExecutable, "pnpm", "run", "verify:bundle")
}

val syncWebAssets by tasks.registering(Sync::class) {
    dependsOn(verifyFrontendBundle)
    from(frontendDir.dir("dist"))
    into(generatedWebAssets.map { it.dir("web") })
}

tasks.named("preBuild").configure {
    dependsOn(syncWebAssets)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn(syncWebAssets)
        systemProperty("web.assets.dir", generatedWebAssets.get().asFile.absolutePath)
    }
}
