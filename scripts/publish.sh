#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Gradle's publishing plugin reads project properties through these standard aliases.
export ORG_GRADLE_PROJECT_mavenCentralUsername="${MAVEN_CENTRAL_USERNAME:?Missing Sonatype token username}"
export ORG_GRADLE_PROJECT_mavenCentralPassword="${MAVEN_CENTRAL_PASSWORD:?Missing Sonatype token password}"
export ORG_GRADLE_PROJECT_signingInMemoryKey="${MAVEN_SIGNING_KEY:?Missing armored signing key}"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="${MAVEN_SIGNING_PASSWORD:?Missing signing passphrase}"
exec ./gradlew publishAndReleaseToMavenCentral --no-daemon --max-workers=2 --console=plain "$@"
