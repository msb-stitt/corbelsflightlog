# On-robot checklist — corbelsflightlog

Everything here needs a Control Hub. Nothing in it needs a drivetrain: the
`LogCheck` OpModes run on any configuration.

Work top to bottom. Each step says what a pass looks like and what to do if it
doesn't.

---

## 0. Get the library into TeamCode

In the `corbelsflightlog` repo:

```sh
./gradlew publishToMavenLocal
```

That writes `corbelsflightlog-core`, `-ftc`, `-pedro` and `-wpilib` to `~/.m2`.
The robot project's `build.dependencies.gradle` now has `mavenLocal()`, and
`TeamCode/build.gradle` depends on `-core`, `-ftc` and `-pedro`.

Then build and deploy the robot project as usual.

**If `publishToMavenLocal` fails to resolve `org.nanohttpd:nanohttpd:2.3.1`** —
that coordinate is a guess; nothing here could reach Maven Central to check it.
The version is `nanohttpdVersion` in `gradle.properties`. The FTC SDK ships
NanoHTTPD, so the right coordinate exists; only the name or version may differ.

---

## 1. Did the library register itself?

Everything below depends on this. Start the Robot Controller app and look in
Logcat, or in `robotControllerLog.txt`, for:

```
corbelsflightlog: logs at http://192.168.43.1:8080/corbelsflightlog, files in <folder>
```

- **Present** — `@WebHandlerRegistrar` fired: the OpMode listener is attached,
  the download routes are up, and the line names the folder in use.
- **Absent** — registration didn't happen. Steps 4, 5 and 6 will fail too. The
  likely causes are the annotation not being scanned, or an exception inside
  `FtcFlightLog.register`, which is caught and logged as
  `corbelsflightlog: could not register`.

---

## 2. Where do the files go? (no SD card)

Run **Log 6: where are the logs?**

It writes nothing. It reports the folder, whether it exists and is writable,
free space, how many logs are there, and the budget.

- **Expected:** a `logs` folder inside the SDK's FIRST folder, usually
  `/sdcard/FIRST/logs`. On a Control Hub that is **internal** storage; the name
  is historical and no card is involved.
- **Fallback:** if it shows a path under the app's own storage
  (`/data/user/0/com.qualcomm.ftcrobotcontroller/files/logs` or similar), the
  FIRST folder wasn't usable. Logging still works, and the download page still
  serves the files, but `adb pull` won't reach them.
- **Writable: false** — stop here and tell me what the folder is.

---

## 3. Does it write a log?

Run **Log 1: basics** for a few seconds. Push the sticks, press A, B and X.

- Driver Station shows `Log` as a file name, not `off (...)`.
- `Logs on disk` increments after the first run.
- Loop time looks sane, with a spike about once a second — that's the flush.

**Then download it:** open `http://192.168.43.1:8080/corbelsflightlog` on a
laptop on the robot's Wi-Fi and click the newest file. Open it in
AdvantageScope; `stick/leftY` should track what you did, and `/Events` should
have an entry for each X press.

---

## 4. Auto-close: the crash case

Run **Log 2: crash mid-run**. It throws on purpose after three seconds, and its
`stop()` deliberately does nothing.

- **Pass:** the Driver Station reports the crash, and the log downloads and
  opens with about three seconds of data, ending with the
  `crashing now, at loop N` event.
- **Fail:** the file is missing, zero bytes, or AdvantageScope won't open it.
  That means the post-stop hook didn't run — report back with step 1's result.

This is the case that fails without the lifecycle hook, so it is the one that
matters most.

## 5. Auto-close: the forgetful case

Run **Log 3: forgot to close** for a few seconds, then press stop normally.

- **Pass:** the file's last `/Events` entry matches roughly the last loop count
  shown on the Driver Station. Nothing lost at the end.

Then, for the third path: start Log 3, press stop, and **immediately init
another OpMode**. The previous file should still be complete — that's the
pre-init backstop.

---

## 6. The download page

At `http://192.168.43.1:8080/corbelsflightlog`:

- Newest first, with sizes and timestamps.
- A file downloads and opens in AdvantageScope.
- The folder shown at the top matches step 2.

Then try, in the address bar:

```
http://192.168.43.1:8080/corbelsflightlog/download?file=../secret.wpilog
```

**Expected: a "400" plain-text message**, not a file and not a stack trace.
Same for `?file=notes.txt`.

---

## 7. Disk budget

Run **Log 4: disk budget** three or four times. It drops the budget to 1 MB
while it runs, and restores it on stop.

- **Pass:** `Files` stops growing and the oldest logs disappear; `Total` stays
  near 1 MB.
- Drop a `notes.txt` into the log folder first if you want to confirm that
  non-log files are never deleted.

After the last run, **Log 6** should show the budget back at 10240 MB.

---

## 8. Geometry, in AdvantageScope

Run **Log 5: geometry** for ten seconds or so. No hardware needed; it logs an
imaginary robot going in a circle. If an IMU named `imu` is configured, its real
orientation is logged too.

In AdvantageScope:

- `Circle/Pose` on the **2D Field** — should go round in a circle, facing the
  way it travels.
- `Circle/Pose3d` on the **3D Field** — same circle, a metre up.
- `Circle/Speeds`, `Circle/Wheels`, `Circle/Twist` in a **Table**.
- `imu/Rotation` and `imu/Heading` if the IMU was there.

**Unverified, and this is where we find out:**

- **`fieldQuarterTurns`.** If the circle is rotated or mirrored relative to the
  field image, set `FlightLog.fieldQuarterTurns` to 0, 1, 2 or 3 until it
  matches. It only affects the display, and only for logs written afterwards.
- **The 3D axis mapping** from the SDK's yaw/pitch/roll. Tilt the hub by hand
  with Log 5 running and watch `imu/Rotation` on the 3D field: pitch should
  tip it nose-up, roll should tip it sideways. If those swap, the SDK's axis
  convention for your IMU differs from what its docs describe.

---

## 9. Loop cost

With **Log 1** running, watch `loop/ms` and its spikes.

- A spike roughly once a second is the storage flush; note how big it is.
- Run once with Panels enabled and once with it disabled
  (the "Enable/Disable Panels" OpMode) for comparison.

These numbers are the input to the remaining decision about batching writes per
frame.

---

## 10. Is the storage write visible at all? (Log 8)

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

- [ ] Run it for 20 to 30 seconds. **It writes about 4 MB in 20 seconds** --
      keep it short and delete the files afterwards.
- [ ] Download the log and run an FFT of `/loop/ms` against time.

**A line at 3 Hz with harmonics at 6, 9, 12** means storage writes do show up in
loop time, and their size tells you what one costs. **No line at 3 Hz** means
they are below the noise even at sixty times the normal rate -- in which case
nothing about how writes are batched could affect loop time, and Phase C is
closed.

Either way, expect the 5 Hz comb from the Driver Station to be the largest thing
in the spectrum.

## What to bring back

- Step 1: present or absent, and the folder it named.
- Step 2: the folder, writable or not.
- Step 4: did the crashed run leave a readable log?
- Step 6: does `?file=../secret.wpilog` get refused?
- Step 8: which `fieldQuarterTurns` matched, and whether pitch/roll behaved.
- Step 9: typical `loop/ms` and the size of the once-a-second spike.
