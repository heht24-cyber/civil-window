import java.time.*;
import java.time.zone.*;
import java.util.*;

/** Invert a half-open wall-clock range into minimal ordered UTC intervals. */
public class CivilWindow {
    public record Span(Instant start, Instant end) {
        public Span {
            if (!start.isBefore(end)) throw new IllegalArgumentException("span must be nonempty");
        }
        boolean contains(Instant time) {
            return !time.isBefore(start) && time.isBefore(end);
        }
    }

    public static List<Span> compile(ZoneId zone, LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) throw new IllegalArgumentException("reversed local range");
        if (start.equals(end)) return List.of();
        ZoneRules rules = zone.getRules();
        Instant cursor = start.toInstant(ZoneOffset.MAX);
        Instant limit = end.toInstant(ZoneOffset.MIN);
        List<Span> spans = new ArrayList<>();
        while (cursor.isBefore(limit)) {
            ZoneOffset offset = rules.getOffset(cursor);
            ZoneOffsetTransition next = rules.nextTransition(cursor);
            Instant stop = next == null || next.getInstant().isAfter(limit) ? limit : next.getInstant();
            Instant a = start.toInstant(offset);
            Instant b = end.toInstant(offset);
            if (a.isBefore(cursor)) a = cursor;
            if (b.isAfter(stop)) b = stop;
            if (a.isBefore(b)) {
                if (!spans.isEmpty() && spans.get(spans.size() - 1).end().equals(a)) {
                    a = spans.remove(spans.size() - 1).start();
                }
                spans.add(new Span(a, b));
            }
            cursor = stop;
        }
        return List.copyOf(spans);
    }

    private static LocalDateTime parse(String value) {
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDate.parse(value).atStartOfDay();
    }

    private static long micros(Instant instant) {
        if (instant.getNano() % 1000 != 0) throw new IllegalArgumentException("SQL bounds must align to microseconds");
        long sec = instant.getEpochSecond();
        long part = instant.getNano() / 1000;
        // Shift a negative second toward zero before multiplying near Long.MIN_VALUE.
        if (sec < 0) return Math.addExact(Math.multiplyExact(sec + 1, 1_000_000L), part - 1_000_000L);
        return Math.addExact(Math.multiplyExact(sec, 1_000_000L), part);
    }

    private static void plan(ZoneId zone, LocalDateTime a, LocalDateTime b) {
        if (a.getNano() % 1000 != 0 || b.getNano() % 1000 != 0)
            throw new IllegalArgumentException("CLI accepts at most microsecond precision");
        var spans = compile(zone, a, b);
        var args = new ArrayList<Long>();
        var utc = new StringJoiner(", ", "[", "]");
        for (var s : spans) {
            utc.add("[\"" + s.start() + "\", \"" + s.end() + "\"]");
            args.add(micros(s.start()));
            args.add(micros(s.end()));
        }
        var clause = "(event_us >= ? AND event_us < ?)";
        var sql = spans.isEmpty() ? "0=1" : "(" + String.join(" OR ", Collections.nCopies(spans.size(), clause)) + ")";
        System.out.println("{\"utc\":" + utc + ",\"where\":\"" + sql + "\",\"params\":" + args + "}");
    }

    private static void audit(int first, int until) {
        if (until <= first) throw new IllegalArgumentException("need an exclusive end year after start");
        var from = LocalDate.of(first, 1, 1);
        var end = LocalDate.of(until, 1, 1);
        long days = 0, empty = 0, split = 0, naiveMismatch = 0;
        var affected = new TreeSet<String>();
        var ids = new TreeSet<>(ZoneId.getAvailableZoneIds());
        System.out.println("zone,date,pieces,seconds,naive_mismatch");
        for (String id : ids) {
            ZoneId z = ZoneId.of(id);
            for (LocalDate d = from; d.isBefore(end); d = d.plusDays(1)) {
                var a = d.atStartOfDay();
                var b = a.plusDays(1);
                var spans = compile(z, a, b);
                var naiveA = a.atZone(z).toInstant();
                var naiveB = b.atZone(z).toInstant();
                var naive = naiveA.isBefore(naiveB) ? List.of(new Span(naiveA, naiveB)) : List.of();
                boolean mismatch = !naive.equals(spans);
                days++;
                if (spans.isEmpty()) empty++;
                if (spans.size() > 1) split++;
                if (mismatch) naiveMismatch++;
                if (mismatch || spans.size() != 1) {
                    affected.add(id);
                    long seconds = spans.stream().mapToLong(s -> Duration.between(s.start(), s.end()).getSeconds()).sum();
                    System.out.printf("%s,%s,%d,%d,%b%n", id, d, spans.size(), seconds, mismatch);
                }
            }
        }
        System.err.printf("tzdb=%s zone_ids=%d zone_days=%d empty=%d split=%d naive_mismatch=%d affected_ids=%d%n",
                ZoneRulesProvider.getVersions("UTC").lastKey(), ids.size(), days, empty, split, naiveMismatch, affected.size());
    }

    private static void check(ZoneId zone, String lo, String hi, int pieces) {
        var a = parse(lo);
        var b = parse(hi);
        var spans = compile(zone, a, b);
        if (spans.size() != pieces) throw new AssertionError(zone + " " + spans);
        // Independent forward oracle at each second, and immediately on both sides of every boundary.
        var probes = new ArrayList<Instant>();
        for (var s : spans) {
            for (var t : List.of(s.start(), s.end())) {
                probes.add(t.minusNanos(1));
                probes.add(t);
                probes.add(t.plusNanos(1));
            }
        }
        var limit = b.toInstant(ZoneOffset.MIN);
        for (var t = a.toInstant(ZoneOffset.MAX); t.isBefore(limit); t = t.plusSeconds(1))
            verify(zone, a, b, spans, t);
        for (var t : probes) verify(zone, a, b, spans, t);
    }

    private static void verify(ZoneId z, LocalDateTime a, LocalDateTime b, List<Span> spans, Instant t) {
        var local = LocalDateTime.ofInstant(t, z);
        boolean expected = !local.isBefore(a) && local.isBefore(b);
        boolean actual = spans.stream().anyMatch(s -> s.contains(t));
        if (actual != expected) throw new AssertionError(z + " " + t);
    }

    private static void test() {
        check(ZoneId.of("Pacific/Apia"), "2011-12-30", "2011-12-31", 0);
        check(ZoneId.of("America/St_Johns"), "1988-10-30", "1988-10-31", 2);
        check(ZoneId.of("America/New_York"), "2050-11-06T01:30", "2050-11-06T01:45", 2);
        check(ZoneId.of("Australia/Lord_Howe"), "2024-04-07T01:40", "2024-04-07T01:50", 2);
        check(ZoneId.of("Asia/Kathmandu"), "1986-01-01", "1986-01-02", 1);
        check(ZoneOffset.UTC, "1969-12-31T23:59:59.999999", "1970-01-01", 1);
        check(ZoneOffset.UTC, "2020-01-01", "2020-01-01", 0);
        if (micros(Instant.ofEpochSecond(-1, 999999000)) != -1) throw new AssertionError();
        for (long v : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
            var instant = Instant.ofEpochSecond(Math.floorDiv(v, 1_000_000L), Math.floorMod(v, 1_000_000L) * 1000);
            if (micros(instant) != v) throw new AssertionError();
        }
        System.out.println("Forward membership and SQL unit checks passed.");
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("test")) test();
        else if (args.length == 3 && args[0].equals("audit")) audit(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        else if (args.length == 4 && args[0].equals("plan")) plan(ZoneId.of(args[1]), parse(args[2]), parse(args[3]));
        else throw new IllegalArgumentException("Use: plan ZONE LOCAL_START LOCAL_END | audit FIRST_YEAR EXCLUSIVE_END_YEAR | test");
    }
}
