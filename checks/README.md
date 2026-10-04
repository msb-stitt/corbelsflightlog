# Robot checks

The OpModes that exercise this library on a real Control Hub, and the
checklist for running them. They test the library -- where files land, whether a
crashed OpMode still closes its log, whether the disk budget prunes, whether the
download page refuses a path-traversal query -- not any particular robot.

## Built here, not published

`./gradlew build` compiles `opmodes/` as the `:checks` module whenever it builds
`:ftc`, so CI sees them. They are not published: OpModes are found by
annotation scanning, so a published copy would put the whole `LogCheck` group
on every Driver Station that depended on it.

To run them, copy `opmodes/` into your TeamCode module and change the package
declaration to match. `host/` holds the programs that run on the computer the
robot is plugged into: `:checks:usbGate`, and `:checks:ntApiCheck`.
[`docs/testing.md`](../docs/testing.md) has the steps for each.

## What they cover

| OpMode | Checks |
|---|---|
| Log 1: basics | writes every value type; download and open in AdvantageScope |
| Log 2: crash mid-run | throws on purpose, `stop()` does nothing -- the file must still be complete |
| Log 3: forgot to close | normal stop, no `close()` call |
| Log 4: disk budget | oldest logs pruned, non-log files untouched, cap restored |
| Log 5: geometry | the struct types on the 2D and 3D field; confirms `fieldQuarterTurns` |
| Log 6: where are the logs? | writes nothing; folder, writability, free space, budget |
| Log 8: 3 Hz flush beat | makes storage writes visible in the loop-time spectrum |
| Log 9: NetworkTables API | every type on the NetworkTables server, read back by WPILib's client; run by `:checks:usbGate` |

None of them needs a drivetrain, or any hardware: they run on any configuration.

## Status, September 2026

All of them ran on a Control Hub and passed, which is what established that
`fieldQuarterTurns = 1` is correct for BIOBUZZ (2026-2027).

The loop-cost figures from that session belong to that robot and that OpMode,
not to the library -- data rate and loop cost depend entirely on what a team
logs and how fast it loops. Log 8 is there so a team can measure its own.
