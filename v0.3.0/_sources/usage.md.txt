# Logging values

## Opening and closing a log

One file per run. On an FTC robot:

```java
import io.github.mikestitt.corbelsflightlog.FlightLog;
import io.github.mikestitt.corbelsflightlog.ftc.FtcFlightLog;

private FlightLog log;

@Override public void start() { log = FtcFlightLog.open(this); }   // /sdcard/corbelsflightlog
@Override public void loop()  { /* ... */ log.endLoop(); }
@Override public void stop()  { log.close(); }
```

Without the FTC module, set the folder yourself:

```java
FlightLog.directory = new File("logs");
FlightLog log = FlightLog.open("MyRun");
```

`open` never throws. If the folder can't be used, logging turns itself off and
`log.status()` says why — useful on a Driver Station line.

## The value types

| Call | Stored as | In AdvantageScope |
|---|---|---|
| `number(key, double)` | `double` | Line Graph |
| `float32(key, float)` | `float` | Line Graph |
| `integer(key, long)` | `int64` | Line Graph |
| `bool(key, boolean)` | `boolean` | Line Graph, Table |
| `text(key, String)` | `string` | Table, Console |
| `pose(key, xIn, yIn, headingRad)` | `Pose2d` | **2D Field** |
| `poses(key, double[])` | `Pose2d[]` | 2D Field, as a trajectory |
| `numbers` / `integers` / `bools` | arrays | Table |
| `FlightLog.event(String)` | `string`, in `/Events` | Table, Console |

`float32` is deliberately not an overload of `number`: Java would resolve
`number("x", 5)` — an `int` — to a float overload and quietly store a float.

### Strings and bytes

`recordOutput(key, String[])` writes a `string[]`; `recordOutput(key, byte[])`
writes `raw`, the escape hatch for anything without a type of its own.

`recordOutput` is overloaded, so the Java type decides what is stored: `5` is
an integer, `5.0` a double, `5f` a float. One consequence -- a bare `null` is
ambiguous between the array overloads, so cast it:
`recordOutput("k", (double[]) null)`.

Which calls convert from Pedro's frame and which write what you give them is
set out in [Frames](frames.md), along with recipes for logging a pose for the
field view, for the maths, or both.

### A pose off the floor

`pose(key, xIn, yIn, heightIn, headingRad)` writes a `Pose3d` from the same
Pedro coordinates as `pose(...)` -- inches, corner origin, radians -- with a
height in inches, and applies the same `fieldQuarterTurns` rotation. Use it
rather than converting by hand: a `pose3d` built from raw metres lands in a
different place on the field than the 2D pose beside it.

## Geometry

WPILib struct types, which AdvantageScope draws on its 2D and 3D field tabs and
treats as one value in tables and graphs. Units are WPILib's: metres, radians,
metres per second. These are written as given -- `pose(...)` above is the one
that converts from Pedro's inches and corner origin.

| Call | Written as |
|---|---|
| `translation2d(key, x, y)` | `Translation2d` |
| `rotation2d(key, radians)` | `Rotation2d` |
| `pose2d(key, x, y, radians)` | `Pose2d` |
| `twist2d(key, dx, dy, dtheta)` | `Twist2d` |
| `chassisSpeeds(key, vx, vy, omega)` | `ChassisSpeeds` |
| `mecanumWheelSpeeds(key, fl, fr, rl, rr)` | `MecanumDriveWheelSpeeds` |
| `translation3d(key, x, y, z)` | `Translation3d` |
| `quaternion(key, w, x, y, z)` | `Quaternion` |
| `rotation3d(key, w, x, y, z)` | `Rotation3d` |
| `pose3d(key, x, y, z, qw, qx, qy, qz)` | `Pose3d` |
| `pose3d(key, x, y, z, yaw, pitch, roll)` | `Pose3d`, angles converted to a quaternion |

A 3D rotation is a quaternion, not three angles: `Rotation3d` is
`Quaternion q`, and `Pose3d` is 56 bytes. The yaw/pitch/roll form applies roll,
then pitch, then yaw.

### From the FTC SDK

`corbelsflightlog-ftc` converts the SDK's own geometry:

```java
FtcGeometry.pose3d(log, "AprilTag/Robot", detection.robotPose);   // Pose3D -> Pose3d
FtcGeometry.rotation3d(log, "imu/Rotation", imu.getRobotYawPitchRollAngles());
FtcGeometry.heading(log, "imu/Heading", imu.getRobotYawPitchRollAngles());
```

A `Position` is converted to metres from whatever unit it carries.
`YawPitchRollAngles` is documented as yaw, then pitch, then roll, applied
intrinsically, with **pitch about X and roll about Y**; WPILib's own
yaw/pitch/roll constructor names those axes the other way round, so the angles
are composed here in the SDK's order and axes and written as a quaternion.
The SDK notes that a `Pose3D`'s axis mapping is defined by whatever produced
it, so check the docs of the API you got it from.

## Only changes are recorded

A value is written when it differs from the last one under that key.
AdvantageScope holds each value until the next record, so the graph looks the
same while a boolean that stays true all match costs one record instead of
thousands.

`FlightLog.event(...)` is the exception: every call is recorded, because two
identical events are two things that happened.

## One type per key

A key's type is fixed by its first value. A later call with a different type is
ignored and a one-time note naming the key appears in `/Events`.

## Flushing

`endLoop()` writes to storage about once a second. Call it once a loop. If the
robot loses power, the last second or so is lost; `close()` finishes cleanly.
