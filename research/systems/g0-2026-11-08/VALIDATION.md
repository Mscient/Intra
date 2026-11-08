# G0 validation record — version 1

Validation is in progress. Exact final source commit, commands, counts, package
hashes and selected Windows offline matrix results will be recorded after completion.
Hosted GitHub Actions has not been claimed successful. Actual G0 remains INCOMPLETE.

Pinned environment: Microsoft JDK `21.0.13.0.2+1-LTS`, implementor Microsoft,
implementor version `Microsoft-15780113`, installed directory label
`jdk-21.0.13.21-hotspot`. The directory label is not the runtime version.

The first sandbox attempt compiled G0 but failed Java canonical-path/JAR inspection
with AccessDeniedException in temporary output. It was rerun with approved normal
filesystem access; no path-security requirement was weakened to accommodate sandboxing.
