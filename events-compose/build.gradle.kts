plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}
group = "com.getfounderhq"
version = "0.8.0"
android {
    namespace = "com.founderhq.events.compose"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":events"))
    implementation("androidx.compose.runtime:runtime:1.8.3")
    implementation("androidx.navigation:navigation-compose:2.9.2")
}
mavenPublishing {
    coordinates(group.toString(), "events-compose", version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("FounderHQ Events Compose")
        description.set("FounderHQ Events Compose SDK for Android")
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
