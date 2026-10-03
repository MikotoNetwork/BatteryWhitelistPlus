
plugins {
    id("com.android.application")
   
    id("org.jetbrains.kotlin.plugin.compose")
    
}

android {
    namespace = "com.batterywhitelist.plus"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.batterywhitelist.plus"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    
    
     // 签名配置
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用自己的签名
            signingConfig = signingConfigs.getByName("release")
            
        }
    }
       
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    
    compileOnly("io.github.libxposed:api:102.0.0@aar")

   
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}