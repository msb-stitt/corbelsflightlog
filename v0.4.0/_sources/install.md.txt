# Installing

## JitPack (no setup)

In your FTC project's `TeamCode/build.gradle`:

```groovy
repositories {
    maven { url = "https://jitpack.io" }
}

dependencies {
    implementation 'com.github.msb-stitt.corbelsflightlog:corbelsflightlog-core:VERSION'
    // optional, if you use Pedro Pathing:
    implementation 'com.github.msb-stitt.corbelsflightlog:corbelsflightlog-pedro:VERSION'
    // optional, if you already use WPILib geometry types:
    implementation 'com.github.msb-stitt.corbelsflightlog:corbelsflightlog-wpilib:VERSION'
    // the Control Hub and Panels glue -- see the note below:
    implementation 'com.github.msb-stitt.corbelsflightlog:corbelsflightlog-ftc:VERSION'
}
```

`VERSION` is a release tag, such as `v1.2.3`;
[the releases page](https://github.com/msb-stitt/corbelsflightlog/releases)
lists them, newest first.

### About `corbelsflightlog-ftc`

It is an Android library rather than a plain jar, so JitPack builds it with an
Android SDK. It has built there since v0.2.1. If a version will not resolve,
its build log says why:

```
https://jitpack.io/com/github/msb-stitt/corbelsflightlog/VERSION/build.log
```

To build it yourself instead, clone this repository and run
`./gradlew publishToMavenLocal`, then add `mavenLocal()` to your repositories.

JitPack builds a tag the first time someone asks for it, so the first download
of a new version is slow. After that it's cached.

## Versions

`corbelsflightlog-core` needs nothing. `corbelsflightlog-pedro` and `corbelsflightlog-ftc` declare
Pedro Pathing, the FTC SDK and Panels as `compileOnly`, so **your project
chooses those versions** and this library can't force a different one on you.

Built and tested against Pedro Pathing 3.0.1, FTC SDK 12.0.0 and Panels 1.0.13.

## Java version

The jars are built for Java 8 bytecode, which is what FTC projects compile to,
so they work whatever JDK your laptop runs.
