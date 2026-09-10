plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "com.getfounderhq"
version = "1.0.0"

android {
    namespace = "com.founderhq.events"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Instrumented tests run against the variant that ships, not a debug build.
    testBuildType = "release"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        // Without this the test APK inherits minSdk as its target, and Android
        // then puts a legacy notification permission dialog over the Activity
        // the instrumented tests are trying to tap.
        targetSdk = 36
        unitTests.isReturnDefaultValues = true
        // Every instrumented test method gets its own process. It keeps the
        // suite isolated, and it is the only honest way to prove the retry
        // ladder's final rung, which waits for a genuinely new process.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
    // Standalone releases carry the same golden fixtures at their root.
    val fixtures = rootProject.file("fixtures").takeIf { it.isDirectory }
        ?: rootProject.file("../events-core/fixtures")
    sourceSets.getByName("test").resources.srcDir(fixtures)
}

dependencies {
    // OkHttp only ships with the app that already uses it: FounderHQOkHttpInterceptor
    // is the single class that touches it, so apps on another HTTP client are
    // unaffected and never pull OkHttp in through this SDK.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestUtil("androidx.test:orchestrator:1.5.1")
}

mavenPublishing {
    coordinates(group.toString(), "events", version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("FounderHQ Events")
        description.set("FounderHQ Events SDK for Android")
        url.set("https://github.com/FounderHQ/founderhq-events-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/FounderHQ/founderhq-events-android/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("FounderHQ")
                name.set("FounderHQ")
                email.set("tech@getfounderhq.com")
            }
        }
        scm {
            url.set("https://github.com/FounderHQ/founderhq-events-android")
            connection.set("scm:git:https://github.com/FounderHQ/founderhq-events-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/FounderHQ/founderhq-events-android.git")
            tag.set("v${project.version}")
        }
    }
}
