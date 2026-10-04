# Frames

Two coordinate systems meet in this library, and which one a value is in
depends on the call, not on the channel name. This page says which is which.

## The two frames

**Pedro's frame.** Inches, origin in a field corner, so the middle of the field
is (72, 72). Headings in radians, counter-clockwise, 0 facing +x. This is what
`follower.pose()` returns and what your path constants are written in.

**AdvantageScope's FTC field frame.** Metres, origin at the centre of the field,
and turned by `FlightLog.fieldQuarterTurns` so that what the picture calls "up"
matches the field image for the season.

## Which call uses which

| Call | Frame |
|---|---|
| `pose(key, xIn, yIn, headingRad)` | Pedro in, **converted** |
| `pose(key, xIn, yIn, heightIn, headingRad)` | Pedro in, **converted**, as a `Pose3d` |
| `poses(key, xyh[])` | Pedro in, **converted** |
| `pose2d`, `pose3d`, `translation2d`, `translation3d`, … | written exactly as given |
| `chassisSpeeds`, `twist2d`, `mecanumWheelSpeeds` | written exactly as given |

The rule: **`pose` converts, everything else does not.**

## Speeds are not converted

`chassisSpeeds` is a field-frame velocity, and this library writes whatever you
pass. `corbelsflightlog-pedro` passes Pedro's own velocity, converted to metres
per second but **not rotated** -- so with a non-zero `fieldQuarterTurns`,
`Pedro/Speeds` points along Pedro's axes while `Pedro/Pose` is drawn along
AdvantageScope's.

That is deliberate, and harmless for the usual uses: magnitudes, graphs and
tables read the same either way. It matters only if you draw the velocity as an
arrow on the field, or compare `vx` against the direction the drawn robot is
moving. If you need those to agree, rotate it yourself before logging.

`twist2d` and `mecanumWheelSpeeds` are unaffected by any of this: a twist is in
the robot's own frame -- forward and sideways relative to the robot -- and wheel
speeds are per wheel. Rotating the field doesn't change either.

## Recipe: for the field view

Use the converting calls. Everything drawn on the 2D or 3D field then agrees
with everything else, including paths.

```java
log.pose("Robot/Pose", follower.pose().x(), follower.pose().y(),
        follower.pose().heading());                      // 2D field
log.pose("Robot/Pose3d", x, y, 39.37, heading);          // 3D field, a metre up
log.poses("Robot/Path", pathPoints);                     // trajectory
```

Or let the Pedro adapter do it:

```java
PedroFlightLog.recordOutput(log, "Robot/Pose", follower.pose());
```

## Recipe: for the maths

When the numbers matter more than the picture -- comparing against path
constants, checking a localizer, tuning -- log Pedro's values unconverted, so
what you read is what your code computes.

As plain numbers, which graph cleanly and carry their units in the name:

```java
log.recordOutput("Robot/x_in", pose.x());
log.recordOutput("Robot/y_in", pose.y());
log.recordOutput("Robot/heading_deg", Math.toDegrees(pose.heading()));
```

Or as a struct, if you want AdvantageScope to group the three together:

```java
log.pose2d("Robot/PedroPose", pose.x(), pose.y(), pose.heading());
```

That writes a `Pose2d` holding Pedro's own numbers. Its table and graph values
are Pedro's, which is the point -- but **do not drag it onto the field view**:
the field expects metres from the centre, and these are inches from a corner, so
it will be drawn far off the field.

## Both at once

Nothing stops you logging the same pose twice, which is often the most useful
thing: one channel to look at, one to reason about.

```java
log.pose("Robot/Pose", x, y, heading);              // for the field view
log.pose2d("Robot/PedroPose", x, y, heading);       // for the numbers
```

Change-only writing means the second channel costs nothing while the robot is
still.

## Converting without logging

`FlightLog.fieldPose(xIn, yIn, headingRad)` returns `{x, y, headingRad}` in
AdvantageScope's frame: the same conversion `pose` applies, on its own, for code
that draws the robot somewhere this library does not write -- a NetworkTables
publisher, say.

```java
double[] p = FlightLog.fieldPose(pose.x(), pose.y(), pose.heading());
```

It is static and reads `fieldQuarterTurns`, so it turns the field the same way
the logs do.

## Checking `fieldQuarterTurns`

It is display-only -- nothing on the robot reads it -- and it must match the
season's field image. Drive or simulate a circle around the middle of the field
and watch the four axis crossings: each has a distinct position *and* heading, so
a wrong quarter turn or a mirrored axis shows up immediately.

| Pedro pose | Should be drawn |
|---|---|
| (96, 72, 90°) | right of centre, facing up |
| (72, 96, 180°) | above centre, facing left |
| (48, 72, 270°) | left of centre, facing down |
| (72, 48, 0°) | below centre, facing right |

Verified as **1** for BIOBUZZ (2026-2027).
