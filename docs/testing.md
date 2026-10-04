# Testing

The tests are sorted by where they run: on any computer, on a Control Hub joined
by USB with nobody at it, and with a person at the robot. Each says which part
of the library it covers.

## On a build computer, and on GitHub Actions

```sh
./gradlew build                                   # every module's tests
pip install -r tools/logcheck/requirements.txt
python -m unittest discover -s tools/logcheck -v  # after the Gradle tests
```

`./gradlew build` builds `:ftc` and `:checks` only when an Android SDK is found,
through `local.properties`, `ANDROID_HOME` or `ANDROID_SDK_ROOT`; the rest
builds anywhere. CI runs the same: `ci.yml`'s `jvm` job runs `./gradlew build`
on a runner that has an Android SDK, so `:ftc` and `:checks` with it; its
`wpilib-parity` job runs the Python tests, and its `android` job builds `:ftc`.

**Against WPILib itself, not our reading of the spec.** The Java tests read
every file back with WPILib's own Java reader, copied unmodified into
`core/src/test` under its BSD licence. The Python tests in `tools/logcheck`
write the same scenario with WPILib's *native* writer and require the bytes to
match, and check the struct schemas, encoding and pose geometry against
WPILib's own code through `robotpy`. `tools/logcheck/README.md` lists them and
says what each compares against.

**Pedro is exercised for real.** The `pedro` tests drive an actual Pedro
`Follower` with a simulated drivetrain through a full path and through teleop,
including the end-of-path window that used to throw.

| Feature | Tests |
| --- | --- |
| `WpiLogWriter`, the WPILOG format | `WpiLogWriterTest`; `ParityFilesTest` with `tools/logcheck` |
| `FlightLog` recording, types, change detection, failures | `FlightLogTest`, `StringArrayAndRawTest` |
| Structs and the field frame, `fieldPose`, `fieldQuarterTurns` | `StructTypesTest`, `FieldPoseTest`, `PedroPose3dTest`; `tools/logcheck` |
| Disk budget | `DiskBudgetTest` |
| Flush cost | `FlightLogTest`, `endLoopFlushesAboutOnceASecond` |
| `PedroFlightLog` | `PedroFlightLogTest` |
| `FtcFlightLog`: folder, auto-close, registration | `FtcFlightLogTest` |
| The download page, `LogWebHandlers` | `FtcFlightLogTest` |
| `FtcGeometry` | `FtcGeometryTest` |
| `WpiGeometry` | `WpiGeometryTest` |
| `Nt4Server` | `Nt4ServerTest` |
| `FlightLog.mirrorTo`, `FtcFlightLog.openLive` | `MirrorTest` |
| The robot checks compile against the FTC SDK | `:checks`, built by `./gradlew build` |

`PanelsMirror` has no test.

### Adding to the parity scenario

`tools/logcheck/scenario.tsv` is read by both halves — the Java test writes it
with our writer, the Python test with WPILib's. Add a line and both pick it up.
A field missing at the end of a line counts as empty, so editors that strip
trailing whitespace can't change its meaning.

## On a Control Hub by USB, with nobody at it

```sh
./gradlew :checks:usbGate
```

It needs the Android SDK, for `adb`, and a Control Hub joined to this computer
by USB running a robot project that has:

- `checks/opmodes/` copied into its TeamCode, with the package declarations
  changed to match;
- this library's `-ftc` module, and Panels, which `-ftc` already depends on.

The gate forwards ports 5810 and 8002 over `adb`, then starts **Log 9:
NetworkTables API** through Panels' own socket, the one its **OpModes Control**
uses. It clicks nothing and needs no browser. It reads Log 9 back with WPILib's
NetworkTables client, stops it and starts it again, and reads it again. Then it
pulls that run's log with `adb pull` and checks it holds every value the client
received. After an install or a reboot the Robot Controller app takes about
40 s to bring Panels up; the gate waits up to 3 minutes.

A pass prints `ok` on every line and ends `N passed, 0 failed`, exit 0. Any
failure prints `FAILED` with what was expected and what came, and exits 1.

| Feature | What the gate checks |
| --- | --- |
| `Nt4Server` | Every type the log records, by name, type and value; `prefix` and `topicsonly` subscriptions at each level down to `/apicheck/deep/a/b/c/d/`; the struct schemas; values that change every loop; a second run starting over |
| `FlightLog.mirrorTo` | A value recorded before the server started is still sent |
| `FtcFlightLog` folder and `FlightLog` recording | The pulled log is a valid WPILOG holding every value sent live |

Log 9 turns the NetworkTables server on. FTC rule R704 forbids third-party
telemetry over Wi-Fi at competitions, so it is for practice only.

## With a person at the robot

Everything here needs a Control Hub. Nothing in it needs a drivetrain: the
`LogCheck` OpModes run on any configuration. A GUI driven by Claude counts as a
person. Work top to bottom; each step says what a pass looks like.

### Getting the checks onto a robot

In this repository:

```sh
./gradlew publishToMavenLocal
```

That writes `corbelsflightlog-core`, `-ftc`, `-pedro` and `-wpilib` to `~/.m2`.
A robot project with `mavenLocal()` in its repositories, depending on `-core`
and `-ftc`, picks them up. Copy `checks/opmodes/` into its TeamCode and change
the package declarations to match, then build and deploy as usual.

### `FtcFlightLog` registration

Everything below depends on this. Start the Robot Controller app and look in
Logcat, or in `robotControllerLog.txt`, for:

```
corbelsflightlog: logs at http://192.168.43.1:8080/corbelsflightlog, files in <folder>
```

- **Present** — `@WebHandlerRegistrar` fired: the OpMode listener is attached,
  the download routes are up, and the line names the folder in use.
- **Absent** — registration didn't happen. The auto-close steps and the download
  page will fail too. The likely causes are the annotation not being scanned,
  or an exception inside `FtcFlightLog.register`, which is caught and logged as
  `corbelsflightlog: could not register`.

### Where the files go: Log 6

Run **Log 6: where are the logs?**

It writes nothing. It reports the folder, whether it exists and is writable,
free space, how many logs are there, and the budget.

- **Expected:** a `corbelsflightlog` folder in the Robot Controller's storage,
  `/sdcard/corbelsflightlog`. On a Control Hub that is **internal** storage; the
  name is historical and no card is involved.
- **Fallback:** if it shows a path under the app's own storage
  (`/data/user/0/com.qualcomm.ftcrobotcontroller/files/corbelsflightlog` or
  similar), the preferred folder wasn't usable. Logging still works, and the
  download page still serves the files, but `adb pull` won't reach them.
- **Writable: false** — stop here; nothing below can pass.

### `FlightLog` recording: Log 1

Run **Log 1: basics** for a few seconds. Push the sticks, press A, B and X.

- Driver Station shows `Log` as a file name, not `off (...)`.
- `Logs on disk` increments after the first run.
- Loop time looks sane, with a spike about once a second — that's the flush.

**Then download it:** open `http://192.168.43.1:8080/corbelsflightlog` on a
laptop on the robot's Wi-Fi and click the newest file. Open it in
AdvantageScope; `stick/leftY` should track what you did, and `/Events` should
have an entry for each X press.

### `FtcFlightLog` auto-close, the crash case: Log 2

Run **Log 2: crash mid-run**. It throws on purpose after three seconds, and its
`stop()` deliberately does nothing.

- **Pass:** the Driver Station reports the crash, and the log downloads and
  opens with about three seconds of data, ending with the
  `crashing now, at loop N` event.
- **Fail:** the file is missing, zero bytes, or AdvantageScope won't open it.
  That means the post-stop hook didn't run; check registration, above.

This is the case that fails without the lifecycle hook.

### `FtcFlightLog` auto-close, the forgetful case: Log 3

Run **Log 3: forgot to close** for a few seconds, then press stop normally.

- **Pass:** the file's last `/Events` entry matches roughly the last loop count
  shown on the Driver Station. Nothing lost at the end.

Then, for the third path: start Log 3, press stop, and **immediately init
another OpMode**. The previous file should still be complete — that's the
pre-init backstop.

### The download page

At `http://192.168.43.1:8080/corbelsflightlog`:

- Newest first, with sizes and timestamps.
- A file downloads and opens in AdvantageScope.
- The folder shown at the top matches Log 6's.

Then try, in the address bar:

```
http://192.168.43.1:8080/corbelsflightlog/download?file=../secret.wpilog
```

**Expected: a "400" plain-text message**, not a file and not a stack trace.
Same for `?file=notes.txt`.

### Disk budget: Log 4

Run **Log 4: disk budget** three or four times. It drops the budget to 1 MB
while it runs, and restores it on stop.

- **Pass:** `Files` stops growing and the oldest logs disappear; `Total` stays
  near 1 MB.
- Drop a `notes.txt` into the log folder first if you want to confirm that
  non-log files are never deleted.

After the last run, **Log 6** should show the budget back at 2048 MB.

### Structs and the field frame: Log 5

Run **Log 5: geometry** for ten seconds or so. No hardware needed; it logs an
imaginary robot going in a circle.

In AdvantageScope:

- `Circle/Pose` on the **2D Field** — should go round in a circle, facing the
  way it travels.
- `Circle/Pose3d` on the **3D Field** — same circle, a metre up.
- `Circle/Speeds`, `Circle/Wheels`, `Circle/Twist` in a **Table**.

**`fieldQuarterTurns`.** If the circle is rotated or mirrored relative to the
field image, set `FlightLog.fieldQuarterTurns` to 0, 1, 2 or 3 until it
matches. It only affects the display, and only for logs written afterwards.
In September 2026, 1 matched the 2026-2027 field.

`FtcGeometry`'s mapping of a real IMU's yaw, pitch and roll is checked only by
`FtcGeometryTest`: Log 5 here logs no IMU, so tilting a hub shows nothing.

### Flush cost: loop time with Log 1

With **Log 1** running, watch `loop/ms` and its spikes.

- A spike roughly once a second is the storage flush; note how big it is.
- Run once with Panels enabled and once with it disabled
  (the "Enable/Disable Panels" OpMode) for comparison.

These numbers are the input to the remaining decision about batching writes per
frame.

### Flush cost: is the storage write visible at all? Log 8

Only worth running if you want the loop-cost question closed properly.

The first loop-cost run showed a strong impulse train at **3.94 Hz** -- one brief
cost every 254 ms, with harmonics out to 35 Hz -- while the logger's own
once-a-second flush measured **9.7 microseconds** against a 4 microsecond noise
floor. 254 ms is almost certainly the Driver Station telemetry transmission, not
us.

**Log 8: 3 Hz flush beat** writes 192 KB/s so the 64 KB buffer fills exactly
three times a second, moves the Driver Station transmission to 200 ms (putting
the SDK's comb at 5, 10, 15 Hz, clear of ours), and skips `endLoop()` so its
one-second flush cannot smear the beat.

- Run it for 20 to 30 seconds. **It writes about 4 MB in 20 seconds** -- keep it
  short and delete the files afterwards.
- Download the log and run an FFT of `/loop/ms` against time.

**A line at 3 Hz with harmonics at 6, 9, 12** means storage writes do show up in
loop time, and their size tells you what one costs. **No line at 3 Hz** means
they are below the noise even at sixty times the normal rate -- in which case
nothing about how writes are batched could affect loop time.

Either way, expect the 5 Hz comb from the Driver Station to be the largest thing
in the spectrum.

### `Nt4Server` and `FlightLog.mirrorTo`, started by hand: Log 9

The same check as the USB gate, with a person starting the OpMode. Forward the
port, or use `--host 192.168.43.1` on the robot's Wi-Fi:

```sh
adb forward tcp:5810 tcp:5810
./gradlew :checks:ntApiCheck --args="--runs 2 --save received.txt"
```

Start **Log 9: NetworkTables API** from the Driver Station; when the checker
asks, stop it and start it again. Then pull that run's log and check it:

```sh
./gradlew :checks:ntApiCheck --args="--log <the pulled .wpilog> --against received.txt"
```

The Driver Station shows `NetworkTables on, port 5810`, the log's name, and the
step counting up. A pass ends `N passed, 0 failed`. To watch the values in
AdvantageScope instead, see [NetworkTables](networktables.md).

### What to bring back

- Registration: present or absent, and the folder it named.
- Log 6: the folder, writable or not.
- Log 2: did the crashed run leave a readable log?
- The download page: does `?file=../secret.wpilog` get refused?
- Log 5: which `fieldQuarterTurns` matched.
- Loop time: typical `loop/ms` and the size of the once-a-second spike.
