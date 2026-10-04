# Robot checks

The OpModes that exercise this library on a real Control Hub, and the
checklist for running them. They test the library -- where files land, whether a
crashed OpMode still closes its log, whether the disk budget prunes, whether the
download page refuses a path-traversal query -- not any particular robot.

## Not wired into the build yet

These are source files, deliberately outside `settings.gradle`. Nothing compiles
them, and CI does not see them.

They were written and run inside a team's own TeamCode module, where they
compiled against the FTC SDK and this library together. Making them build here
means deciding how:

- a separate opt-in artifact, say `corbelsflightlog-ftc-checks`, that a team
  adds for a session and removes afterwards -- OpModes are found by annotation
  scanning, so anything published as part of `-ftc` would put the whole
  `LogCheck` group on every user's Driver Station;
- or left as source to copy into a TeamCode module, which is how they were used.

**Future work.** Until then, copy `opmodes/` into your TeamCode module, change
the package declaration to match, and follow `CHECKLIST.md`.

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

None of them needs a drivetrain, or any hardware: they run on any configuration.

## Status, September 2026

All of them ran on a Control Hub and passed, which is what established that
`fieldQuarterTurns = 1` is correct for BIOBUZZ (2026-2027).

The loop-cost figures from that session belong to that robot and that OpMode,
not to the library -- data rate and loop cost depend entirely on what a team
logs and how fast it loops. Log 8 is there so a team can measure its own.
