# NetworkTables

A NetworkTables 4 server on the robot lets AdvantageScope watch the values
while the robot runs, the way it watches an FRC robot. The file is still
written; the server shows the same values live.

**For practice only.** FTC rule R704 forbids third-party telemetry over Wi-Fi at
competitions, so a competition OpMode opens its log with `open`, which starts no
server.

## Turning it on

```java
@Override public void start() { log = FtcFlightLog.openLive(this); }
```

`openLive` opens a log as `open` does, and sends every value the log records to
the server as well, under the same name and type. If the server cannot start,
the log still records, and the reason goes to the robot's log.

## Connecting AdvantageScope

With the laptop on the robot's Wi-Fi, set **Robot Address** to `192.168.43.1`
under **AdvantageScope > Settings…**, then choose **File > Connect to Robot >
NetworkTables 4**. The values appear under the same names as in the file.

## The server

The server is `Nt4Server`, in `corbelsflightlog-core`: plain Java with no
libraries, on port 5810. It starts on the first `openLive` and runs until the
Robot Controller app stops, so AdvantageScope stays connected from one OpMode
to the next. Each connection has its own thread, so a slow or lost connection
never holds up the robot's loop.

When a log starts sending to the server, it sends the struct schemas and the
last value of every channel it has already written. It sends nothing once it
stops recording.

## Off the robot

A program off the robot, such as a simulator, starts the server itself:

```java
FlightLog log = FlightLog.open("Sim");
log.mirrorTo(Nt4Server.shared());
```

`Nt4Server.shared()` is one server on port 5810 for the life of the program.
`Nt4Server.start(port)` starts another; port 0 picks a free one, which
`port()` gives. `server.set(name, type, value)` sends a value with no log
behind it, and `log.mirrorTo(null)` stops a log sending.

## What it leaves out

The server leaves out these parts of NetworkTables 4: the `all` subscribe
option (each send carries the latest value), meta-topics such as `$clients`,
saving `persistent` topics, TLS, and NetworkTables 3.

## Tested by

`Nt4ServerTest` and `MirrorTest` on a build computer, and Log 9 on a Control
Hub, run by `./gradlew :checks:usbGate`; see [Testing](testing.md).
