# Changelog

Versions follow [semantic versioning](https://semver.org). Tag a release as
`vMAJOR.MINOR.PATCH` and keep `version=` in `gradle.properties` matching --
the release workflow refuses a tag that doesn't.

## Unreleased

- `corbelsflightlog-core`: `Nt4Server`, a NetworkTables 4 server in plain Java with
  no libraries, and `FlightLog.mirrorTo(server)`, which sends every recorded value
  to it live.
- `corbelsflightlog-ftc`: `FtcFlightLog.openLive(opMode)`, which opens a log and
  serves it live to AdvantageScope on port 5810. For practice: FTC rule R704
  forbids it at competitions.

## 0.1.0

First release, split out of the Corbels robot code.

- `corbelsflightlog-core`: WPILOG 1.0 writer, byte-for-byte identical to WPILib's own
  native writer; `FlightLog` with change-only writing, `Pose2d` structs for
  AdvantageScope, and events.
- `corbelsflightlog-pedro`: the follower's pose, aim point, path and mode, plus
  Pedro's debug maps flattened into channels.
- `corbelsflightlog-ftc`: logging to `/sdcard/FIRST/logs`, and mirroring values to
  the Panels dashboard.
