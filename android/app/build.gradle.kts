plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// 클라우드 피드 주소. 저장소를 포크했다면 FEED_URL 환경 변수로 바꿀 수 있습니다.
// -PlocalJvmOnly: Robolectric(네이티브 런타임 다운로드 필요)을 빼고 순수 JVM 테스트만 실행.
// 네트워크가 제한된 환경용. 화면·알림 테스트(AndroidTest)는 GitHub Actions에서 실행됩니다.
val localJvmOnly = project.hasProperty("localJvmOnly")

val feedUrl = System.getenv("FEED_URL")
    ?: "https://raw.githubusercontent.com/happyfamily1984/real-estate-news-finder/data/posts.json"

android {
    namespace = "com.realestatenewsfinder.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.realestatenewsfinder.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        buildConfigField("String", "FEED_URL", "\"$feedUrl\"")
    }

    signingConfigs {
        // 저장소에 포함된 공용 서명키: 어디서 빌드해도 같은 서명이라 덮어쓰기 업데이트가 됩니다.
        // (스토어 배포용이 아님. 플레이스토어에 올리려면 별도 키를 만드세요.)
        create("shared") {
            storeFile = file("app.keystore")
            storePassword = "communityfinder"
            keyAlias = "communityfinder"
            keyPassword = "communityfinder"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    if (!localJvmOnly) testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
}

if (localJvmOnly) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name.contains("UnitTest")) exclude("**/AndroidTest.kt")
    }
}
