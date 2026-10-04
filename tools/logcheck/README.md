# logcheck — WPILib parity tests

Checks our WPILOG logging against **WPILib's own code**, not our reading of the
spec:

| Test | Compares against |
|---|---|
| `test_files_are_byte_for_byte_identical` | WPILib's native C++ log writer, given the same records |
| `test_wpilib_reads_every_record` | WPILib's native log reader |
| `test_schema_entries_match_wpilib_exactly` | WPILib's `addStructSchema` for `Pose2d` |
| `test_schema_dependencies_come_before_the_types_that_use_them` | No WPILib code: each schema comes before the schemas that use it, `Pose2d`'s after `Translation2d`'s and `Rotation2d`'s |
| `test_poses_decode_with_wpilib_to_the_expected_field_pose` | WPILib's struct decoder and `wpimath` geometry |
| `test_pose_bytes_reencode_identically_with_wpilib` | WPILib's struct encoder |
| `test_pose_array_matches_the_single_poses` | WPILib's struct-array decoder |

## Running

One-time setup (Python 3.9+):

```sh
pip install -r tools/logcheck/requirements.txt
```

Then, from the repository root:

```sh
./gradlew :core:test                             # writes core/build/logcheck/
python -m unittest discover -s tools/logcheck -v
```

The Java step matters: `ParityFilesTest` writes `scenario.tsv` with our writer
into `core/build/logcheck/`. The Python tests write the same scenario with
WPILib and compare. If the Java output is missing, the Python tests are
skipped, not failed. `LOGCHECK_DIR` overrides where they look.

## The scenario

`scenario.tsv` is the single source of truth for both sides — every value type,
header-field widths from 1 to 8 bytes, UTF-8 names and metadata, Finish
records, and a set of Pedro poses. To test something new, add lines there; both
halves pick it up. Timestamps must be greater than 0, because WPILib's writer
replaces 0 with the current time.

## When a test fails

The byte comparison reports the **first differing record**, e.g.
`('data', 2000, '/double', '...f03f') != ('data', 2000, '/double', '...f83f')`
— which entry, at what time, and both payloads.
