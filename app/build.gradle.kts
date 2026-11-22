import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pocketgateway.app"
    compileSdk = 35

    // 签名配置：优先读取环境变量（CI 用 Secrets 注入），
    // 其次读本地 keystore.properties（已 gitignore）。
    // 仓库中不存放密钥文件与明文密码。
    signingConfigs {
        create("stable") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }

            val storePath = System.getenv("SIGNING_STORE_FILE")
                ?: props.getProperty("storeFile")
                ?: "keystore/pocketgateway.jks"
            val sf = rootProject.file(storePath)
            if (sf.exists()) {
                storeFile = sf
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    ?: props.getProperty("storePassword") ?: ""
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    ?: props.getProperty("keyAlias") ?: "pocketgateway"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: props.getProperty("keyPassword") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.pocketgateway.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "0.21.14"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // 有可用签名配置时用固定签名（保证可覆盖安装），否则回退默认 debug 签名
            signingConfigs.getByName("stable").storeFile?.let {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.getByName("stable").storeFile?.let {
                signingConfig = signingConfigs.getByName("stable")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Ktor/Netty 等依赖的签名文件与重复资源，避免打包冲突
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

val KTOR = "2.3.12"
val COMPOSE_BOM = "2024.10.00"
val LIFECYCLE = "2.8.6"

dependencies {
    // --- Ktor Server（内嵌 HTTP 网关，CIO 引擎为纯 Kotlin，可在 Android 运行）---
    implementation("io.ktor:ktor-server-core:$KTOR")
    implementation("io.ktor:ktor-server-cio:$KTOR")
    implementation("io.ktor:ktor-server-cors:$KTOR")
    implementation("io.ktor:ktor-server-content-negotiation:$KTOR")
    implementation("io.ktor:ktor-server-status-pages:$KTOR")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$KTOR")

    // --- Ktor Client（向上游转发请求）---
    implementation("io.ktor:ktor-client-core:$KTOR")
    implementation("io.ktor:ktor-client-cio:$KTOR")
    implementation("io.ktor:ktor-client-content-negotiation:$KTOR")

    // --- Kotlin 生态 ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // --- WorkManager（每日签到 / 任务领取的可靠后台调度）---
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- AndroidX 基础 ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$LIFECYCLE")
    implementation("androidx.lifecycle:lifecycle-service:$LIFECYCLE")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$LIFECYCLE")
    implementation("androidx.activity:activity-compose:1.9.3")

    // --- DataStore（配置持久化）---
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- 二维码生成（ZXing core 为纯 Java，无 Android 平台依赖）---
    implementation("com.google.zxing:core:3.5.3")

    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:$COMPOSE_BOM")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
