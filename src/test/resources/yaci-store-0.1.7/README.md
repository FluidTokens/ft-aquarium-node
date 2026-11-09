# Yaci Store 0.1.7 H2 migrations — frozen test fixture

These three SQL files are **byte-for-byte copies** of Yaci Store's H2 library migrations at tag
`v0.1.7` (commit `a76b65a6fb8474d4136978bd36a9fead1026f2cf`,
https://github.com/bloxbean/yaci-store), taken with `git show v0.1.7:<path>`:

| file | original path at `v0.1.7` | sha256 |
|---|---|---|
| `db/h2/V0_000_1__init.sql` | `components/core/src/main/resources/db/store/h2/V0_000_1__init.sql` | `cdd9b958d4b0863d598c398e66b2508f2f202425ae6408b5ca6e480d143eb380` |
| `db/h2/V0_000_2__error_table.sql` | `components/core/src/main/resources/db/store/h2/V0_000_2__error_table.sql` | `946ba946a40556e0cff72e05523927629bf92676af93baddc98fd151a8b60e08` |
| `db/h2/V0_200_1__init.sql` | `stores/utxo/src/main/resources/db/store/h2/V0_200_1__init.sql` | `45f71779a029d93933b1b7811d0048f239fd3ffd20427089b580c1b1ef2e765b` |

## Why they are frozen

They stand in for **a database written by a node running Yaci Store 0.1.7**. Flyway records a
checksum for every migration it applies, and these files resolve to exactly the checksums the
published 0.1.7 jars carry (`0.000.1 = 15733750`, `0.000.2 = 180837475`, `0.200.1 = 694847724`) —
the values every 0.1.7 node wrote into its `flyway_schema_history`. Yaci Store 2.0.7 edited
`V0_200_1__init.sql` in place (its H2 copy resolves to `-27599824`), which is why Flyway refuses to
start the node on a 0.1.7 database, and why an image built before the upgrade refuses a database
this release wrote. `Yaci017DatabaseRefusalTest` pins both directions against these files.

**Do not edit, reformat or add a header to them.** Any byte changed changes their Flyway checksum,
and the fixture then no longer represents a real 0.1.7 database — the refusal tests would be
measuring a database no operator has. The test asserts each file's sha256 and resolved checksum.

## Licence

Yaci Store is distributed under the MIT License:

```
MIT License

Copyright (c) 2022 BloxBean Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
