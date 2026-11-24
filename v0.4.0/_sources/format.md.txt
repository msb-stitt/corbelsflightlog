# The file format

WPILOG 1.0, as specified in WPILib's
[`datalog.adoc`](https://github.com/wpilibsuite/allwpilib/blob/main/datalog/doc/datalog.adoc):
a 12-byte header, then records whose first byte gives the widths of the entry
ID, payload size and timestamp that follow. Everything is little-endian, and
every field uses as few bytes as its value needs.

Given the same records, `WpiLogWriter` produces **byte-for-byte the same file**
as WPILib's own native writer. `tools/logcheck` checks that on every CI run.

## Poses

Poses are written as WPILib `Pose2d` structs — three little-endian doubles, x,
y and rotation — with the schema entries AdvantageScope needs
(`/.schema/struct:Pose2d` and its parts), exactly as WPILib's `addStructSchema`
writes them.
