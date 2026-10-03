# corbelsflightlog

WPILOG logging for FIRST Tech Challenge robots -- the format
[AdvantageScope](https://docs.advantagescope.org) opens.

```java
log.recordOutput("shooter/rpm", rpm);
log.recordOutput("intake/hasSample", hasSample);
FlightLog.event("kicker fired");
```

Values are written **only when they change**, so a boolean that holds all match
costs one record rather than thousands, and a file survives the match for
review afterwards -- unlike a dashboard, which samples and forgets.

For practice, `FtcFlightLog.openLive(this)` also sends the same values live to
AdvantageScope over NetworkTables 4; see [Viewing a log](docs/viewing.md).

| Module | Depends on | For |
|---|---|---|
| `corbelsflightlog-core` | nothing | The WPILOG writer and logger |
| `corbelsflightlog-pedro` | Pedro Pathing | Pose, path, aim point and Pedro's debug data |
| `corbelsflightlog-ftc` | FTC SDK, Panels | The Control Hub's storage; mirroring to Panels |
| `corbelsflightlog-wpilib` | WPILib geometry | WPILib's own `Pose2d`, `Pose3d`, `ChassisSpeeds` |

## Install

```groovy
repositories { maven { url = "https://jitpack.io" } }

dependencies {
    implementation 'com.github.MikeStitt.corbelsflightlog:corbelsflightlog-core:v0.1.0'
    implementation 'com.github.MikeStitt.corbelsflightlog:corbelsflightlog-pedro:v0.1.0'  // optional
    implementation 'com.github.MikeStitt.corbelsflightlog:corbelsflightlog-ftc:v0.1.0'    // optional
}
```

Pedro Pathing, the FTC SDK and Panels are `compileOnly`, so your project picks
their versions.

## Documentation

<https://mikestitt.github.io/corbelsflightlog/> -- one copy per release, plus the
Javadoc.

## Building

```sh
./gradlew build                  # core and pedro
./gradlew :ftc:assembleRelease   # needs the Android SDK
```

`:ftc` is only included when an Android SDK is present, so the rest builds
anywhere.

## Testing

The tests check our files against **WPILib's own code**: the Java tests read
them back with WPILib's Java reader, and `tools/logcheck` requires the bytes to
match WPILib's native writer exactly.

```sh
./gradlew build
pip install -r tools/logcheck/requirements.txt
python -m unittest discover -s tools/logcheck -v
```

## Licence

BSD 3-Clause. See `LICENSE`.
