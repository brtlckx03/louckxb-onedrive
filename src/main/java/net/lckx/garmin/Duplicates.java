package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.Util.*;

/**
 * The same outing recorded twice, e.g. on the watch and on the bike computer: two activities of the same sport group
 * that overlap for at least half of the shorter one. Only one counts, on every page: the one with heart rate, else the
 * one with GPS, else the longer one. The other stays in its day report with a note.
 *
 * <p>Activities of different sports that overlap (a walk during a ride, the parts of a triathlon) both count. The
 * downloaded and imported files (Garmin and Strava folders) are only read; a cache keeps what is needed from them.
 */
final class Duplicates {
    private static final String CACHE_FILE = "duplicates-cache.tsv";
    private static final String CACHE_VERSION = "d1";
    private static final Pattern JSON = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_.*\\.json");

    private Duplicates() {}

    /** What the rule needs from one activity file. */
    private record Rec(long mtime, long id, long start, double secs, String type, double metres, boolean hr, boolean gps,
                       boolean strava) {
        long end() {
            return start + Math.round(secs);
        }
    }

    private static String forDir;
    /** Per activity id the file name (without extension) of its newest download: a renamed activity is downloaded again. */
    private static Map<Long, String> newest = Map.of();
    /** Duplicate id → the id of the recording that counts. */
    private static Map<Long, Long> dropped = Map.of();

    /** The recording that counts instead of this one, or null when this one counts. */
    static Long keptFor(long id) {
        return dropped().get(id);
    }

    static boolean isDuplicate(long id) {
        return dropped().containsKey(id);
    }

    static boolean isDuplicate(String id) {
        return isDuplicate(SportCorrection.id(id));
    }

    /**
     * True for an older download of an activity that has a newer one under another name (it was renamed in Garmin
     * Connect, e.g. "Zwemmen…" and later "Koksijde Zwemmen…"). Those files are left alone, but nothing counts them.
     */
    static boolean isOldCopy(String fileName) {
        Matcher m = ANY.matcher(fileName);
        if (!m.matches()) return false;
        dropped();
        String keep = newest.get(Long.parseLong(m.group(2)));
        return keep != null && !keep.equals(m.group(1) + "_" + m.group(2) + "_" + m.group(3));
    }

    private static final Pattern ANY = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_(.*)\\.(json|gpx|fit|tcx)");

    /** For the current account; worked out once per run. */
    static synchronized Map<Long, Long> dropped() {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        if (dir.toString().equals(forDir)) return dropped;
        try {
            List<Rec> recs = read(dir);
            // One record per activity id: its newest download (by the JSON's time; ties by name).
            Map<Long, Rec> byId = new HashMap<>();
            Map<Long, String> names = new HashMap<>();
            for (var e : lastRead.entrySet()) {
                Rec r = e.getValue(), o = byId.get(r.id);
                String base = e.getKey().substring(e.getKey().lastIndexOf('/') + 1).replaceFirst("\\.json$", "");
                if (o == null || r.mtime > o.mtime || (r.mtime == o.mtime && base.compareTo(names.get(r.id)) > 0)) {
                    byId.put(r.id, r);
                    names.put(r.id, base);
                }
            }
            newest = names;
            dropped = find(new ArrayList<>(byId.values()));
        } catch (IOException e) {
            System.err.println("Duplicates: could not read " + dir + ": " + e.getMessage());
            dropped = Map.of();
        }
        forDir = dir.toString();
        return dropped;
    }

    private static Map<Long, Long> find(List<Rec> recs) {
        List<Rec> acts = new ArrayList<>();
        Map<Rec, String> group = new HashMap<>();
        for (Rec r : recs) {
            if (r.secs <= 0 || r.start <= 0 || r.type.equals("multi_sport")) continue;
            String sport = SportCorrection.sport(r.id, r.type, r.metres, r.secs);
            if (DescribeGarminFile.Overview.HIDDEN_SPORTS.contains(sport)) continue;
            group.put(r, DescribeGarminFile.Overview.sportGroup(sport));
            acts.add(r);
        }
        acts.sort(Comparator.comparingLong(Rec::start).thenComparingLong(Rec::id));
        Map<Long, Long> out = new HashMap<>();
        List<Rec> open = new ArrayList<>(); // the counted activities still running at this start
        for (Rec r : acts) {
            open.removeIf(o -> o.end() <= r.start);
            Rec twin = null;
            for (Rec o : open) {
                long overlap = Math.min(o.end(), r.end()) - Math.max(o.start, r.start);
                if (group.get(o).equals(group.get(r)) && overlap >= .5 * Math.min(o.secs, r.secs)) {
                    twin = o;
                    break;
                }
            }
            if (twin != null && twin.id == r.id) continue; // the same activity, not a second recording
            if (twin == null) {
                open.add(r);
            } else if (better(r, twin)) {
                long was = twin.id;
                out.replaceAll((k, v) -> v == was ? r.id : v);
                out.put(was, r.id);
                open.remove(twin);
                open.add(r);
            } else {
                out.put(r.id, twin.id);
            }
        }
        return out;
    }

    /** Heart rate, then GPS, then the longer recording; Garmin before a Strava import. */
    private static boolean better(Rec a, Rec b) {
        if (a.hr != b.hr) return a.hr;
        if (a.gps != b.gps) return a.gps;
        if (Math.abs(a.secs - b.secs) > 60) return a.secs > b.secs;
        if (a.strava != b.strava) return !a.strava;
        return a.id < b.id;
    }

    // ---- reading, with a cache of what the rule needs from each file

    private static Map<String, Rec> lastRead = Map.of();

    private static List<Rec> read(Path dir) throws IOException {
        Path cacheFile = dir.resolve(CACHE_FILE);
        Map<String, Rec> cache = readCache(cacheFile);
        TreeMap<String, Rec> now = new TreeMap<>();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher m = JSON.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                String key = dir.relativize(p).toString().replace('\\', '/');
                if (key.contains("/" + SwimCorrection.DIR + "/")) continue; // derived files, not activities
                long mtime = Files.getLastModifiedTime(p).toMillis();
                Rec r = cache.get(key);
                if (r == null || r.mtime != mtime) r = load(p, Long.parseLong(m.group(2)), mtime, key.startsWith(StravaImport.FOLDER + "/"));
                if (r != null) now.put(key, r);
            }
        }
        if (!now.equals(cache)) writeCache(cacheFile, now);
        lastRead = now;
        return new ArrayList<>(now.values());
    }

    private static Rec load(Path json, long id, long mtime, boolean strava) {
        try {
            Object j = DescribeGarminFile.Json.parse(Files.readString(json, StandardCharsets.UTF_8));
            String gmt = str(at(j, "summaryDTO", "startTimeGMT"));
            long start = gmt.length() >= 19 ? LocalDateTime.parse(gmt.substring(0, 19)).toEpochSecond(ZoneOffset.UTC) : 0;
            boolean gps = Files.exists(json.resolveSibling(json.getFileName().toString().replaceFirst("\\.json$", ".gpx")));
            return new Rec(mtime, id, start, numOr(at(j, "summaryDTO", "duration"), 0), str(at(j, "activityTypeDTO", "typeKey")),
                    numOr(at(j, "summaryDTO", "distance"), 0), num(at(j, "summaryDTO", "averageHR")) != null, gps, strava);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Rec> readCache(Path f) {
        Map<String, Rec> m = new HashMap<>();
        try {
            if (!Files.exists(f)) return m;
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals(CACHE_VERSION)) return m;
            for (String l : lines.subList(1, lines.size())) {
                String[] p = l.split("\t", -1);
                if (p.length != 10) continue;
                m.put(p[0], new Rec(Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), Double.parseDouble(p[4]), p[5],
                        Double.parseDouble(p[6]), p[7].equals("1"), p[8].equals("1"), p[9].equals("1")));
            }
        } catch (Exception e) {
            m.clear(); // read everything again
        }
        return m;
    }

    private static void writeCache(Path f, Map<String, Rec> recs) {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        recs.forEach((k, r) -> b.append(k).append('\t').append(r.mtime).append('\t').append(r.id).append('\t').append(r.start)
                .append('\t').append(r.secs).append('\t').append(r.type).append('\t').append(r.metres).append('\t')
                .append(r.hr ? 1 : 0).append('\t').append(r.gps ? 1 : 0).append('\t').append(r.strava ? 1 : 0).append('\n'));
        try {
            replace(f, b.toString());
        } catch (IOException e) {
            System.err.println("Duplicates: could not write " + f + ": " + e.getMessage());
        }
    }
}
