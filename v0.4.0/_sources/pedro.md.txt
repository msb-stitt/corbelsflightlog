# Pedro Pathing

`corbelsflightlog-pedro` logs two things, either or both.

```java
import io.github.mikestitt.corbelsflightlog.pedro.PedroFlightLog;

PedroFlightLog pedro = new PedroFlightLog(log);
follower = Constants.create(hardwareMap).withLogger(pedro::record);   // debug data

public void loop() {
    follower.update();
    pedro.record(follower);                                           // state
    log.endLoop();
}
```

## The follower's state — `record(Follower)`

| Channel | What |
|---|---|
| `Pedro/Pose` | Where the robot is. Drawn as **Robot** on the 2D field |
| `Pedro/AimPose` | The nearest point on the path, at the heading the path wants. **Ghost** |
| `Pedro/Path` | The segment being followed. **Trajectory** |
| `Pedro/Mode` | `FOLLOW`, `HOLD`, `MANUAL`, `IDLE` |
| `Pedro/vel/*` | World-frame, body-frame and along-path speeds |
| `Pedro/Speeds` | The same world-frame speeds as a `ChassisSpeeds`, in metres |
| `Pedro/Twist` | Body-frame speeds as a `Twist2d`, in metres |

The gap between `Pose` and `AimPose` is the tracking error. Because the aim
point is the *nearest* point on the path rather than a time-based target, it
sits beside the robot, never ahead of it: it shows how far off the line the
robot is, not whether it's behind schedule.

`Pedro/Path` is written only when the segment changes, so a whole path costs one
record, and an empty one when the path ends.

## Pedro's own debug data — `record(FollowerLog)`

Pedro hands this over on every `follower.update()`. Each entry becomes
`Pedro/<map>/<entry>`:

- **`follow`** — `mode` always; `isBusy`, `atParametricEnd`, `pathIndex` while
  following or holding
- **`algorithm`** — `headingError`, `translationalError`, `remainingDistance`,
  `closestT`, `tangentialSpeed`, the algorithm's `pose`, vectors such as
  `driveVector/x`
- **`localizer`** — `pose`, and `velocity/vx`, `twist/omega` and the rest
- **`drivetrain`** — whatever the drivetrain reports, e.g. wheel powers

Types are chosen from the value, and an unrecognised type is stored as text, so
entries a future Pedro adds appear on their own.

:::{note}
Each entry holds whatever Pedro reported on the most recent `update()`, so read
the `algorithm` entries alongside `Pedro/Mode`.
:::

## What gets logged when there is no path

Speed along the path needs a path: `Pedro/vel/tangential_ips` is logged while
the follower is following or holding, and `NaN` — which is skipped — otherwise.
`Pedro/Path` is written from the segment currently being followed, and cleared
when there is none.
