# corbelsflightlog

WPILOG logging for FIRST Tech Challenge robots — the log format
[AdvantageScope](https://docs.advantagescope.org) opens.

Write a value once and it lands in a file on the robot, timestamped and
recorded only when it changes:

```java
log.recordOutput("shooter/rpm", rpm);
log.recordOutput("intake/hasSample", hasSample);
log.pose("Robot/Pose", x, y, heading);     // drawn on AdvantageScope's field
FlightLog.event("kicker fired");           // one-off, with its exact time
```

## What it does

- **Writes WPILOG files on the robot.** Each value is timestamped and written
  only when it changes, one file per run, and the files download from the
  robot's own web page. See [Logging values](usage.md) and
  [Viewing a log](viewing.md).
- **Serves the same values live, for practice.** A NetworkTables 4 server on
  the robot lets AdvantageScope watch as the robot runs. See
  [NetworkTables](networktables.md).
- **Takes values in the types you already have.** Pedro Pathing's follower, the
  FTC SDK's poses and IMU angles, and WPILib's own geometry are converted or
  written straight through, and Panels can show each value as it is logged.

## Four modules, taken separately

| Module | Depends on | For |
|---|---|---|
| `corbelsflightlog-core` | nothing | The WPILOG writer, the logger and the NetworkTables 4 server. Any Java project. |
| `corbelsflightlog-pedro` | Pedro Pathing | The follower's pose, path, aim point and debug data: [Pedro Pathing](pedro.md) |
| `corbelsflightlog-ftc` | FTC SDK, Panels | Logging to the Control Hub, the download page, `openLive`, the SDK's geometry, and mirroring to Panels |
| `corbelsflightlog-wpilib` | WPILib geometry | Logging WPILib's own `Pose2d`, `Pose3d`, `ChassisSpeeds` and the rest: *From WPILib* in [Logging values](usage.md) |

```{toctree}
:maxdepth: 2

install
usage
frames
pedro
viewing
networktables
format
testing
```

## API reference

Javadoc, built by Gradle and published beside these pages with each version:

<a href="javadoc/core/index.html">corbelsflightlog-core</a> &middot;
<a href="javadoc/pedro/index.html">corbelsflightlog-pedro</a> &middot;
<a href="javadoc/ftc/index.html">corbelsflightlog-ftc</a> &middot;
<a href="javadoc/wpilib/index.html">corbelsflightlog-wpilib</a>

(Those links work on the published site; Gradle copies the Javadoc in when the
site is built, so they are dead in a local `sphinx` build.)

## Why it exists

A dashboard shows what a robot is doing *now*, and is gone when the match ends.
Panels samples periodically and forgets.
A WPILOG file keeps every change, survives the match, and opens later in a tool
built for reading it. The two answer different questions,
and this library is the second one.
