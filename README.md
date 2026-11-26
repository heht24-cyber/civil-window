# Civil Window

Compile a half-open local date or time range into the smallest union of UTC intervals with exactly the same membership. Use the generated SQL predicate on an indexed epoch-microsecond column. The planner handles missing days, clock rollbacks that cross midnight, non-hour transitions, and future recurring rules supplied by the JDK.

## A day with a 119-minute hole

In America/St_Johns on 1988-10-30 the civil clock reached Sunday, then moved back into Saturday. Sunday consisted of these two UTC intervals:

| Start (inclusive) | End (exclusive) |
|---|---|
| 1988-10-30 01:30Z | 1988-10-30 01:31Z |
| 1988-10-30 03:30Z | 1988-10-31 03:30Z |

A query from the start of Sunday to the start of Monday also selects 119 minutes of Saturday events. An OR of the two intervals preserves the calendar membership and allows two index searches.

## Use it

Requires JDK 17+ with the Java source launcher. The CLI accepts ISO dates or offset-free local timestamps on the microsecond grid and rejects finer precision. Apply the returned predicate with the returned bound parameters to a signed 64-bit UTC epoch-microsecond column named event_us. The Java API accepts nanosecond local times and returns immutable half-open Instant pairs. Dates use the ISO proleptic Gregorian calendar.

~~~sh
java CivilWindow.java plan America/St_Johns 1988-10-30 1988-10-31
java CivilWindow.java plan America/New_York 2050-11-06T01:30 2050-11-06T01:45
java CivilWindow.java test
python verify_sqlite.py
java -Xmx192m CivilWindow.java audit 1900 2051 > anomalies.csv
~~~

For each constant-offset UTC segment [u,v) with offset o, intersect it with [localStart−o, localEnd−o). Every UTC instant belongs to one segment and translation within a segment is order-preserving. The intersection therefore includes exactly the matching instants. Take the union in time order and coalesce touching intervals. The remaining gaps each contain an excluded instant, so no union with fewer intervals can represent the same result. The two extreme legal Java offsets bound the scan; work scales with the number of offset transitions in the covered UTC window.

## Full scan and independent verification

OpenJDK 21.0.13.1, TZDB 2026f, years 1900–2050 inclusive. The audit visits all calendar dates for all 604 zone IDs, including aliases. Counts are zone/date combinations and depend on the installed rules.

| Quantity | Count |
|---|---:|
| Zone/date combinations evaluated | 33,311,808 |
| Locally skipped dates | 7 |
| Dates split into two UTC intervals | 190 |
| Dates where independently converting both midnights gives a different set | 202 |
| Zone IDs with a skipped, split, or mismatched date | 23 |

The additional 12 mismatches had a single true interval. A skipped day may be handled correctly by the two-midnight shortcut and still appear in the anomaly output. The CSV is emitted in deterministic order. The SHA-256 of the LF-delimited output above was `f2959626c53187e9efea31f8d7ff6bf49a03198cbf3807db9bab1da721a935fb`.

The Java self-test checks forward local-time membership at every second in seven targeted windows and at nanosecond boundaries. The Python test executes each compiled predicate in SQLite and independently converts event times using Python zoneinfo. It covers Newfoundland, a skipped Samoan date, a 30-minute rollback on Lord Howe, a future New York overlap, and a subsecond window across the Unix epoch. It also inspects the actual SQLite query plans for index range searches. Python 3.9+ needs IANA zone data available to zoneinfo. Other databases can bind the parameters with their own placeholder syntax.

[Java ZoneRules](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/zone/ZoneRules.html) · [IANA time zone database](https://www.iana.org/time-zones)
