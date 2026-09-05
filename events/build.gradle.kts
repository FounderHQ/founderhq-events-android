plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "com.getfounderhq"
version = "0.7.0"

android {
    namespace = "com.founderhq.events"
    compileSdk = 36
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Standalone releases carry the same golden fixtures at their root.
    val fixtures = rootProject.file("fixtures").takeIf { it.isDirectory }
        ?: rootProject.file("../events-core/fixtures")
    sourceSets.getByName("test").resources.srcDir(fixtures)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.14.1")
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
