# Viewing a log

## Getting the file off the robot

With the laptop on the robot's Wi-Fi, open
**<http://192.168.43.1:8080/corbelsflightlog>** and click a log. That page is
served by the Robot Controller itself, on the same port as Program & Manage, so
nothing needs installing.

Or, over ADB (`adb connect 192.168.43.1:5555`):

```sh
adb pull /sdcard/corbelsflightlog .
```

Files live in a `corbelsflightlog` folder in the Robot Controller's own storage
-- internal storage on a Control Hub, not a removable card, despite `/sdcard`.
The folder is named after the library so its size measures exactly what the
logger is using. If it can't be used, `corbelsflightlog-ftc` falls back to the
app's own private storage; the page and the Driver Station both show which
folder is in use. To log somewhere else entirely -- a USB stick, say -- call
`FtcFlightLog.useDirectory(folder)` before opening.

The hub's **Download Logs** button, on its Manage page, fetches the SDK's
`robotControllerLog.txt`, not these.

## Watching live

`FtcFlightLog.openLive(this)` opens a log as `open` does and also sends every
value it records to AdvantageScope as it is recorded, over NetworkTables 4. In
AdvantageScope, choose **File > Connect to Robot**, with the robot address set
to `192.168.43.1`; the values appear under the same names as in the file.

The server is `Nt4Server`, in `corbelsflightlog-core`: plain Java with no
libraries, on port 5810. It starts on the first `openLive` and runs until the
Robot Controller app stops. A program off the robot, such as a simulator, uses
`Nt4Server.shared()` and `FlightLog.mirrorTo(server)` directly.

**For practice only.** FTC rule R704 forbids third-party telemetry over Wi-Fi at
competitions, so a competition OpMode opens its log with `open`, which starts no
server.

What the server leaves out of NetworkTables 4: the `all` subscribe option (each
send carries the latest value), meta-topics such as `$clients`, saving
`persistent` topics, TLS, and NetworkTables 3.

## Housekeeping

Logs are capped at **2 GiB** in total. When a log is opened and the folder is
over that, the oldest `.wpilog` files are deleted until it fits. Change it with
`FlightLog.maxDirectoryBytes`. Files that aren't `.wpilog` are never touched.

## What it costs

Small, but it depends entirely on how many channels you log, how often they
change, and how fast your loop runs -- so measure it on your own robot rather
than trusting a number from someone else's.

`Log 8` in `checks/` makes storage writes visible in the loop-time spectrum if
you want to see what they cost on your hardware. On the robot it was written
for, they were far below the Driver Station's own telemetry transmission, which
`telemetry.setMsTransmissionInterval()` controls.

This library writes each value as it is given, with its own timestamp, rather
than accumulating a frame.
