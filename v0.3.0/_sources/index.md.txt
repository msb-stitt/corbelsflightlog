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

## Three parts, taken separately

| Module | Depends on | For |
|---|---|---|
| `corbelsflightlog-core` | nothing | The WPILOG writer and the logger. Any Java project. |
| `corbelsflightlog-pedro` | Pedro Pathing | The follower's pose, path, aim point and debug data |
| `corbelsflightlog-ftc` | FTC SDK, Panels | Logging to the Control Hub; mirroring values to Panels; SDK geometry |
| `corbelsflightlog-wpilib` | WPILib geometry | Logging WPILib's own `Pose2d`, `Pose3d`, `ChassisSpeeds`… |

```{toctree}
:maxdepth: 2

install
usage
frames
pedro
viewing
format
testing
```

## API reference

Javadoc, built by Gradle and published beside these pages with each version:

<a href="javadoc/core/index.html">corbelsflightlog-core</a> &middot;
<a href="javadoc/pedro/index.html">corbelsflightlog-pedro</a>

(Those links work on the published site; Gradle copies the Javadoc in when the
site is built, so they are dead in a local `sphinx` build.)

## Why it exists

A dashboard shows what a robot is doing *now*, and is gone when the match ends.
Panels samples periodically and forgets.
A WPILOG file keeps every change, survives the match, and opens later in a tool
built for reading it. The two answer different questions,
and this library is the second one.
