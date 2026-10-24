# H10 manual capture fixtures

Sanitised timelines from two runs of the 17-step recipe in
`docs/h10-test-recipe.md` with a Polar H10.

Each file contains:

- `duration_s`: session length.
- `contact`: the raw HR contact flag transitions, in seconds from the start.
- `events`: the `events.jsonl` lines, in seconds from the start, with the
  sensor id removed.

Deliberately not included:

- the raw HR, ECG and ACC logs (personal health data);
- absolute timestamps, timezone, phone model/OS and sensor id;
- diagnostics files.

Use them to replay the real link/contact sequence in state-machine tests.

`capture-b.json` predates the stale-watchdog fix, so it still contains the three
spurious `no data after 15 s` errors at +191.5 s. It is useful as a regression
fixture for that bug.
