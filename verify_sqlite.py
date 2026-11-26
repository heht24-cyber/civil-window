"""Check the published SQL plans against Python zoneinfo and SQLite's query planner."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import sqlite3
import subprocess
from zoneinfo import ZoneInfo

JAVA = Path(__file__).with_name("CivilWindow.java")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
MICROS = timedelta(microseconds=1)
CASES = [
    ("America/St_Johns", "1988-10-30", "1988-10-31"),
    ("Pacific/Apia", "2011-12-30", "2011-12-31"),
    ("Australia/Lord_Howe", "2024-04-07T01:40", "2024-04-07T01:50"),
    ("America/New_York", "2050-11-06T01:30", "2050-11-06T01:45"),
    ("UTC", "1969-12-31T23:59:59.999999", "1970-01-01T00:00:00.000001"),
]


def run(zone, start, end):
    cmd = ["java", "-XX:-UsePerfData", str(JAVA), "plan", zone, start, end]
    plan = json.loads(subprocess.check_output(cmd, text=True))
    a, b = map(datetime.fromisoformat, [start, end])
    left = (a.replace(tzinfo=timezone.utc) - EPOCH - timedelta(hours=18)) // MICROS
    right = (b.replace(tzinfo=timezone.utc) - EPOCH + timedelta(hours=18)) // MICROS
    ticks = set(range(left, right + 1, 1_000_000))
    for bound in plan["params"]:
        ticks.update([bound - 1, bound, bound + 1])
    tz = ZoneInfo(zone)
    expected = {t for t in ticks if a <= (EPOCH + t * MICROS).astimezone(tz).replace(tzinfo=None) < b}
    db = sqlite3.connect(":memory:")
    try:
        db.execute("CREATE TABLE events(event_us INTEGER NOT NULL)")
        db.executemany("INSERT INTO events VALUES (?)", ((t,) for t in ticks))
        db.execute("CREATE INDEX events_by_time ON events(event_us)")
        sql = "SELECT event_us FROM events WHERE " + plan["where"]
        found = {row[0] for row in db.execute(sql, plan["params"])}
        if found != expected:
            raise AssertionError((zone, len(expected - found), len(found - expected)))
        details = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, plan["params"])]
        searches = sum("SEARCH" in item and "events_by_time" in item for item in details)
        if searches < len(plan["utc"]):
            raise AssertionError(details)
        print(f"{zone}: {len(ticks):,} event times, {len(found):,} matches, {searches} index searches")
    finally:
        db.close()


if __name__ == "__main__":
    for case in CASES:
        run(*case)
