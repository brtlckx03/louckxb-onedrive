package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * A heatmap of every GPS track of an account: {@code heatmap.html} next to the overview, with the tracks in
 * {@code heatmap-data.js}. Each GPX file is read once; its simplified track is kept in {@code heatmap-cache.tsv},
 * so later runs only read new or changed files. The downloaded files themselves are never changed.
 *
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.Heatmap [--account NAME]
 * </pre>
 */
public final class Heatmap {
    static final String PAGE_FILE = "heatmap.html";
    private static final String DATA_FILE = "heatmap-data.js";
    private static final String CACHE_FILE = "heatmap-cache.tsv";
    /** Bump when the simplification changes, so every track is read again. */
    private static final String CACHE_VERSION = "v2";

    private static final Pattern GPX_FILE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})_(\\d+)_.*\\.gpx");
    private static final Pattern TRKPT = Pattern.compile("<trkpt\\s+lat=\"([-0-9.eE]+)\"\\s+lon=\"([-0-9.eE]+)\"");
    /** Points closer together than this are dropped before simplifying (metres). */
    private static final double MIN_STEP = 4;
    /** Douglas–Peucker tolerance: the simplified line stays within this distance of the track (metres). */
    private static final double TOLERANCE = 3;
    /** A jump larger than this between two recorded points is a GPS gap; the line is broken there (metres). */
    private static final double MAX_JUMP = 400;

    private Heatmap() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: Heatmap [--account NAME]");
                System.exit(1);
            }
        }
        Path page = write();
        System.out.println("Heatmap updated: " + page.toAbsolutePath());
    }

    /** One activity with GPS: what the page shows about it and its simplified track (encoded polylines). */
    record Track(long mtime, String date, String time, String sport, String name, double km, double secs, List<String> lines) {}

    /** Brings the cache up to date with the GPX files on disk and rewrites the page and its data. */
    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        SportCorrection.dropIfStale(dir.resolve(CACHE_FILE));
        Map<String, Track> cache = readCache(dir.resolve(CACHE_FILE));
        TreeMap<String, Track> tracks = new TreeMap<>();
        int read = 0, failed = 0;
        long t0 = System.nanoTime();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher m = GPX_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                String key = dir.relativize(p).toString().replace('\\', '/');
                long mtime = Files.getLastModifiedTime(p).toMillis();
                Track t = cache.get(key);
                if (t == null || t.mtime != mtime) {
                    try {
                        t = load(p, m.group(1), mtime);
                        read++;
                    } catch (Exception e) {
                        failed++;
                        System.err.println("Heatmap: could not read " + p + ": " + e.getMessage());
                        continue;
                    }
                    if (read % 500 == 0) System.out.printf("Heatmap: %d tracks read…%n", read);
                }
                tracks.put(key, t);
            }
        }
        writeCache(dir.resolve(CACHE_FILE), tracks);
        Set<String> groups = writeData(dir.resolve(DATA_FILE), tracks);
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page(groups));
        long withGps = tracks.values().stream().filter(t -> !t.lines.isEmpty()).count();
        System.out.printf("Heatmap: %d activities with GPS (%d tracks read, %d unreadable) in %.1fs.%n",
                withGps, read, failed, (System.nanoTime() - t0) / 1e9);
        return page;
    }

    // ---- reading -------------------------------------------------------------------------------------------

    private static Track load(Path gpx, String date, long mtime) throws IOException {
        String sport = "", name = "", time = "";
        double km = 0, secs = 0;
        Path json = gpx.resolveSibling(gpx.getFileName().toString().replaceFirst("\\.gpx$", ".json"));
        if (Files.exists(json)) {
            try {
                Map<?, ?> j = (Map<?, ?>) DescribeGarminFile.Json.parse(Files.readString(json));
                sport = str(get(j, "activityTypeDTO", "typeKey"));
                name = str(get(j, "activityName"));
                String start = str(get(j, "summaryDTO", "startTimeLocal"));
                if (start.length() >= 16) time = start.substring(11, 16);
                km = number(get(j, "summaryDTO", "distance")) / 1000;
                secs = number(get(j, "summaryDTO", "duration"));
            } catch (Exception e) {
                System.err.println("Heatmap: could not read " + json + ": " + e.getMessage());
            }
        }
        Matcher idm = GPX_FILE.matcher(gpx.getFileName().toString());
        sport = SportCorrection.sport(idm.matches() ? SportCorrection.id(idm.group(2)) : 0, sport, km * 1000, secs);
        String xml = Files.readString(gpx);
        if (sport.isEmpty()) {
            int i = xml.indexOf("<type>"), k = xml.indexOf("</type>");
            if (i >= 0 && k > i) sport = xml.substring(i + 6, k).trim();
        }
        List<String> lines = new ArrayList<>();
        List<double[]> part = new ArrayList<>();
        double[] prev = null;
        Matcher m = TRKPT.matcher(xml);
        while (m.find()) {
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
            if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) continue; // "no fix" points at 0,0
            double[] p = {lat, lon};
            if (prev != null) {
                double d = metres(prev, p);
                if (d > MAX_JUMP) {
                    addLine(lines, part);
                    part = new ArrayList<>();
                } else if (d < MIN_STEP) {
                    continue;
                }
            }
            part.add(p);
            prev = p;
        }
        addLine(lines, part);
        return new Track(mtime, date, time, sport, name.replaceAll("[\\t\\r\\n]+", " ").trim(), km, secs, lines);
    }

    private static void addLine(List<String> lines, List<double[]> pts) {
        if (pts.size() < 2) return;
        List<double[]> s = simplify(pts);
        if (s.size() >= 2) lines.add(encode(s));
    }

    /** Douglas–Peucker on a local flat projection (fine at the scale of one activity). */
    static List<double[]> simplify(List<double[]> pts) {
        int n = pts.size();
        double lat0 = Math.toRadians(pts.getFirst()[0]), mx = 111_320 * Math.cos(lat0), my = 110_540;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = pts.get(i)[1] * mx;
            y[i] = pts.get(i)[0] * my;
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        int[] stack = new int[2 * n];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            double best = TOLERANCE;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double d = segDist(x[i], y[i], x[a], y[a], x[b], y[b]);
                if (d > best) {
                    best = d;
                    idx = i;
                }
            }
            if (idx >= 0) {
                keep[idx] = true;
                stack[sp++] = a;
                stack[sp++] = idx;
                stack[sp++] = idx;
                stack[sp++] = b;
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
        double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    private static double metres(double[] a, double[] b) {
        double la = Math.toRadians(a[0]), lb = Math.toRadians(b[0]);
        double dl = lb - la, dn = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dl / 2) * Math.sin(dl / 2) + Math.cos(la) * Math.cos(lb) * Math.sin(dn / 2) * Math.sin(dn / 2);
        return 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Google's encoded polyline format at 1e-5° (about 1 m): compact plain ASCII that is easy to decode in JS. */
    static String encode(List<double[]> pts) {
        StringBuilder b = new StringBuilder();
        long plat = 0, plon = 0;
        for (double[] p : pts) {
            long lat = Math.round(p[0] * 1e5), lon = Math.round(p[1] * 1e5);
            encodeValue(b, lat - plat);
            encodeValue(b, lon - plon);
            plat = lat;
            plon = lon;
        }
        return b.toString();
    }

    static void encodeValue(StringBuilder b, long v) {
        v = v < 0 ? ~(v << 1) : v << 1;
        while (v >= 0x20) {
            b.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        b.append((char) (v + 63));
    }

    private static Object get(Object o, String... path) {
        for (String k : path) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(k);
        }
        return o;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // ---- cache ---------------------------------------------------------------------------------------------

    private static Map<String, Track> readCache(Path f) {
        Map<String, Track> m = new HashMap<>();
        if (!Files.exists(f)) return m;
        try {
            List<String> rows = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (rows.isEmpty() || !rows.getFirst().equals(CACHE_VERSION)) return m;
            for (String row : rows.subList(1, rows.size())) {
                String[] c = row.split("\t", -1);
                if (c.length < 9) continue;
                List<String> lines = c[8].isEmpty() ? List.of() : List.of(c[8].split(" "));
                m.put(c[0], new Track(Long.parseLong(c[1]), c[2], c[3], c[4], c[5], Double.parseDouble(c[6]),
                        Double.parseDouble(c[7]), lines));
            }
        } catch (Exception e) {
            System.err.println("Heatmap: cache unreadable, reading every track again (" + e.getMessage() + ")");
            m.clear();
        }
        return m;
    }

    private static void writeCache(Path f, Map<String, Track> tracks) throws IOException {
        StringBuilder b = new StringBuilder(CACHE_VERSION).append('\n');
        for (var e : tracks.entrySet()) {
            Track t = e.getValue();
            b.append(e.getKey()).append('\t').append(t.mtime).append('\t').append(t.date).append('\t').append(t.time)
                    .append('\t').append(t.sport).append('\t').append(t.name).append('\t').append(t.km).append('\t')
                    .append(t.secs).append('\t').append(String.join(" ", t.lines)).append('\n');
        }
        replace(f, b.toString());
    }

    // ---- output --------------------------------------------------------------------------------------------

    /** {@code window.HEATMAP=[[date, time, sport, group, name, km, seconds, [polyline…], garmin id], …]}, oldest first. */
    private static Set<String> writeData(Path f, TreeMap<String, Track> tracks) throws IOException {
        Set<String> groups = new TreeSet<>();
        // The Garmin activity id is in the file name; the day report has an anchor #a<id> on that activity.
        Map<Track, String> ids = new HashMap<>();
        tracks.forEach((key, t) -> {
            Matcher m = GPX_FILE.matcher(key.substring(key.lastIndexOf('/') + 1));
            ids.put(t, m.matches() ? m.group(2) : "");
        });
        List<Track> list = tracks.values().stream()
                .filter(t -> !t.lines.isEmpty() && !DescribeGarminFile.Overview.HIDDEN_SPORTS.contains(t.sport))
                .sorted((a, b) -> (a.date + a.time).compareTo(b.date + b.time)).toList();
        StringBuilder b = new StringBuilder("window.HEATMAP=[\n");
        for (Track t : list) {
            b.append('[').append(jsStr(t.date)).append(',').append(jsStr(t.time)).append(',').append(jsStr(t.sport)).append(',')
                    .append(jsStr(DescribeGarminFile.Overview.sportGroup(t.sport))).append(',').append(jsStr(t.name)).append(',')
                    .append(DescribeGarminFile.trimNum(Math.round(t.km * 100) / 100.0)).append(',').append(Math.round(t.secs)).append(",[");
            for (int i = 0; i < t.lines.size(); i++) b.append(i == 0 ? "" : ",").append(jsStr(t.lines.get(i)));
            b.append("],").append(jsStr(ids.get(t))).append("],\n");
        }
        replace(f, b.append("];\n").toString());
        for (Track t : list) groups.add(DescribeGarminFile.Overview.sportGroup(t.sport));
        return groups;
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String page(Set<String> groups) {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        // Sport names and icons for the group buttons, computed here so they match the overview.
        StringBuilder names = new StringBuilder("{");
        for (String g : groups) {
            names.append(names.length() > 1 ? "," : "").append(jsStr(g)).append(":[")
                    .append(jsStr(DescribeGarminFile.Overview.sportName(g))).append(',').append(jsStr(DescribeGarminFile.sportIcon(g))).append(']');
        }
        names.append('}');
        String updated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#ea580c")
                + "<title>Heatmap · " + esc(label) + "</title>" + Site.HEAD
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<style>" + DescribeGarminFile.CSS + PAGE_CSS + "</style></head><body>"
                + "<header class=\"hm\">" + Site.nav(PAGE_FILE, 0) + "<div class=\"hm-top\"><div><div class=\"eyebrow\" data-nl=\"Garmin-heatmap · " + esc(label) + "\">Garmin heatmap · " + esc(label) + "</div>"
                + "<h1>Heatmap</h1></div></div>"
                + "<div class=\"filters\"><span class=\"ranges\" id=\"groups\"></span></div>"
                + "<div class=\"filters\"><label><span data-nl=\"Van\">From</span> <input id=\"dfrom\" type=\"date\" onchange=\"changed()\"></label>"
                + "<label><span data-nl=\"tot\">to</span> <input id=\"dto\" type=\"date\" onchange=\"changed()\"></label><span class=\"ranges\" id=\"ranges\">"
                + "<a href=\"#\" data-range=\"all\" data-nl=\"Altijd\">All time</a><a href=\"#\" data-range=\"12\" data-nl=\"Laatste 12 maanden\">Last 12 months</a>"
                + "<a href=\"#\" data-range=\"year\" data-nl=\"Dit jaar\">This year</a><a href=\"#\" data-range=\"lastyear\" data-nl=\"Vorig jaar\">Last year</a></span>"
                + "<span class=\"ranges\" id=\"modes\"><a href=\"#\" data-mode=\"heat\" data-nl=\"🔥 Hitte\">🔥 Heat</a><a href=\"#\" data-mode=\"count\" data-nl=\"🎨 Aantal keer\">🎨 Times visited</a></span>"
                + "<label id=\"alphaLabel\"><span data-nl=\"Intensiteit\">Intensity</span> <input id=\"alpha\" type=\"range\" min=\"1\" max=\"20\" value=\"6\" oninput=\"saveAndDraw()\"></label>"
                + "<span class=\"ranges\" id=\"bases\"><a href=\"#\" data-base=\"dark\" data-nl=\"Donkere kaart\">Dark map</a><a href=\"#\" data-base=\"light\" data-nl=\"Lichte kaart\">Light map</a></span>"
                + "<span id=\"filterStatus\"></span></div><div class=\"legend\" id=\"countLegend\" hidden></div></header>"
                + "<div id=\"map\"></div>"
                + "<p class=\"hm-foot\" data-nl=\"Bijgewerkt " + updated + " uit de GPX-bestanden · klik op de kaart voor de activiteiten die daar langskwamen; "
                + "uitgezoomd een stip per plaats (klik om in te zoomen) · activiteiten zonder GPS (binnen, zwembad) staan niet op de kaart.\">Updated " + updated
                + " from the GPX files · click the map to list the activities that passed there; zoomed out, a dot per place (click to zoom in) · "
                + "activities without GPS (indoor, pool swims) are not on the map.</p>"
                + "<script src=\"" + DATA_FILE + "\"></script><script>var NAMES=" + names + ",STORE="
                + jsStr("garmin-heatmap" + (DescribeGarminFile.account().equals(DescribeGarminFile.MAIN_ACCOUNT) ? "" : "-" + DescribeGarminFile.account()))
                + ";" + PAGE_JS + "</script></body></html>";
    }

    private static final String PAGE_CSS = """
            html,body{height:100%}body{display:flex;flex-direction:column}
            header.hm{padding:12px 16px 4px;margin:0}
            .hm-top{display:flex;justify-content:space-between;align-items:flex-end;gap:12px}
            .hm-top h1{font-size:24px;margin:0 0 6px}
            header.hm .site-nav{margin:0 -9px 6px;padding-bottom:6px;border-bottom:1px solid var(--line)}
            header.hm .filters{margin:2px 0 6px}
            .ranges a .c{color:var(--muted);font-weight:400;margin-left:4px;font-variant-numeric:tabular-nums}
            .ranges a.off{opacity:.45}
            #filterStatus{font-variant-numeric:tabular-nums}
            input[type=range]{vertical-align:middle;width:110px}
            #map{flex:1;min-height:360px;margin:0 16px;border:1px solid var(--line);border-radius:12px;background:#0b0e11}
            #countLegend{display:flex;flex-wrap:wrap;gap:4px 14px;margin:0 0 6px;font-size:13px;color:var(--muted)}
            #countLegend[hidden]{display:none}
            #countLegend i{display:inline-block;width:22px;border-radius:2px;vertical-align:middle;margin-right:6px}
            .hm-foot{color:var(--muted);font-size:12.5px;margin:6px 16px 12px}
            .leaflet-container{font:inherit}
            .hm-ctl a{font-size:16px;line-height:30px}.hm-ctl a[hidden]{display:none}
            #hmBack,#hmFwd{font-weight:700;color:#2563eb}#hmSel.on{background:#dbe7fd;color:#2563eb}
            .hm-box{position:absolute;z-index:800;border:2px solid #2563eb;background:rgba(37,99,235,.12);pointer-events:none}
            #map .leaflet-tile-pane{filter:grayscale(1) contrast(.9) brightness(1.04)}
            #map.dark .leaflet-tile-pane{filter:grayscale(1) invert(1) brightness(.42) contrast(1.25)}
            .hm-pop{max-height:260px;overflow:auto;min-width:230px}
            .hm-pop a{display:block;padding:2px 0;text-decoration:none;color:#2563eb;white-space:nowrap}
            .hm-pop a span{color:#6b7682}
            .hm-pop b{display:block;margin-bottom:4px}
            """;

    private static final String PAGE_JS = """
            var ACTS=[],map,canvas,ctx,base,frame=0,ST={};
            try{ST=JSON.parse(localStorage.getItem(STORE)||'{}')||{}}catch(e){ST={}}
            function store(){try{localStorage.setItem(STORE,JSON.stringify(ST))}catch(e){}}
            // Polyline → Web Mercator coordinates in 0..1, with the bounding box for skipping what is off screen.
            function decode(s){
              var pts=[],i=0,lat=0,lon=0;
              while(i<s.length){
                for(var k=0;k<2;k++){
                  var r=0,sh=0,b;
                  do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);
                  var d=(r&1)?~(r>>1):(r>>1);
                  if(k==0)lat+=d;else lon+=d;
                }
                pts.push(lon/1e5,lat/1e5);
              }
              var xy=new Float64Array(pts.length),x0=1,y0=1,x1=0,y1=0;
              for(var j=0;j<pts.length;j+=2){
                var x=(pts[j]+180)/360,sn=Math.sin(pts[j+1]*Math.PI/180),y=.5-Math.log((1+sn)/(1-sn))/(4*Math.PI);
                xy[j]=x;xy[j+1]=y;
                if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y;
              }
              return {xy:xy,b:[x0,y0,x1,y1]};
            }
            (window.HEATMAP||[]).forEach(function(r){
              ACTS.push({date:r[0],time:r[1],sport:r[2],group:r[3],name:r[4],km:r[5],secs:r[6],lines:r[7].map(decode),id:r[8]});
            });
            function fmtDur(s){var h=Math.floor(s/3600),m=Math.floor(s%3600/60);if(h>=100)return fmtInt(s/3600)+' h';return h?h+':'+String(m).padStart(2,'0')+' h':m+' min'}
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,' ')}
            function href(d){return d.slice(0,4)+'/'+d.slice(5,7)+'/'+d+'-report.html'}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            // ---- filters
            var counts={};ACTS.forEach(function(a){counts[a.group]=(counts[a.group]||0)+1});
            var groups=Object.keys(counts).sort(function(a,b){return (a=='others')-(b=='others')||counts[b]-counts[a]||(a<b?-1:1)});
            // Open-water GPS is poor and virtual rides have made-up coordinates, so both start switched off.
            if(!ST.off)ST.off=['swimming','indoor_cycling'];
            var NL_GROUPS={cycling:'Fietsen',indoor_cycling:'Binnen fietsen',others:'Overige',rowing_and_paddling:'Roeien en peddelen',running:'Hardlopen',
              swimming:'Zwemmen',walking:'Wandelen',hiking:'Wandeltochten'};
            function groupName(g){return NAMES[g]?NAMES[g][1]+' '+L(NAMES[g][0],NL_GROUPS[g]):g}
            document.getElementById('groups').innerHTML='<a href="#" data-g="*">'+L('All','Alles')+'</a>'+groups.map(function(g){
              return '<a href="#" data-g="'+esc(g)+'">'+esc(groupName(g))+'<span class="c">'+fmtInt(counts[g])+'</span></a>'}).join('');
            document.getElementById('groups').onclick=function(e){
              var a=e.target.closest('a');if(!a)return;e.preventDefault();var g=a.dataset.g;
              if(g=='*')ST.off=ST.off.length?[]:groups.slice();
              else if(e.altKey||e.metaKey)ST.off=groups.filter(function(x){return x!=g});
              else ST.off=ST.off.indexOf(g)>=0?ST.off.filter(function(x){return x!=g}):ST.off.concat([g]);
              changed();
            };
            function iso(d){return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')}
            function rangeOf(r){var t=new Date(),y=t.getFullYear();
              if(r=='12'){var f=new Date(t);f.setFullYear(y-1);f.setDate(f.getDate()+1);return [iso(f),iso(t)]}
              if(r=='year')return [y+'-01-01',iso(t)];if(r=='lastyear')return [(y-1)+'-01-01',(y-1)+'-12-31'];return ['',''];}
            document.getElementById('ranges').onclick=function(e){
              var a=e.target.closest('a');if(!a)return;e.preventDefault();var v=rangeOf(a.dataset.range);
              dfrom.value=v[0];dto.value=v[1];changed();
            };
            document.getElementById('bases').onclick=function(e){
              var a=e.target.closest('a');if(!a)return;e.preventDefault();ST.base=a.dataset.base;setBase();store();draw();
            };
            var dfrom=document.getElementById('dfrom'),dto=document.getElementById('dto'),alpha=document.getElementById('alpha');
            dfrom.value=ST.from||'';dto.value=ST.to||'';if(ST.alpha)alpha.value=ST.alpha;
            function shown(a){return ST.off.indexOf(a.group)<0&&(!dfrom.value||a.date>=dfrom.value)&&(!dto.value||a.date<=dto.value)}
            function changed(){ST.from=dfrom.value;ST.to=dto.value;store();
              document.querySelectorAll('#groups a').forEach(function(a){var g=a.dataset.g;
                a.classList.toggle('on',g=='*'?!ST.off.length:ST.off.indexOf(g)<0);a.classList.toggle('off',g!='*'&&ST.off.indexOf(g)>=0)});
              document.querySelectorAll('#ranges a').forEach(function(a){var v=rangeOf(a.dataset.range);
                a.classList.toggle('on',v[0]==dfrom.value&&v[1]==dto.value)});
              var n=0,km=0,s=0;ACTS.forEach(function(a){if(shown(a)){n++;km+=a.km;s+=a.secs}});
              document.getElementById('filterStatus').textContent=fmtInt(n)+L(' activities · ',' activiteiten · ')+fmtInt(km)+' km · '+fmtDur(s);
              if(ST.mode=='count')countPasses();legend();draw();
            }
            function saveAndDraw(){ST.alpha=alpha.value;store();draw()}
            // ---- "times visited": how many activities passed through each cell of about 25 m (one count per activity)
            var CLASSES=[[1,L('once','één keer'),'#4f6df5'],[2,L('2–9 times','2–9 keer'),'#20c0b0'],[10,L('10–99 times','10–99 keer'),'#c8d62a'],
              [100,L('100–999 times','100–999 keer'),'#ff7a1f'],[1000,L('1000 times or more','1000 keer of meer'),'#d6307e']];
            function classOf(n){for(var c=CLASSES.length-1;c>0;c--)if(n>=CLASSES[c][0])return c;return 0}
            var CELL=1048576,countKey=null;
            function filterKey(){return JSON.stringify([ST.off,dfrom.value,dto.value])}
            function cellKey(x,y){return Math.floor(x)*4194304+Math.floor(y)}
            // Calls f(x,y) every half cell along the line, so no cell it crosses is missed.
            function walk(xy,i,f){
              var x=xy[i]*CELL,y=xy[i+1]*CELL;if(i==0){f(x,y);return}
              var px=xy[i-2]*CELL,py=xy[i-1]*CELL,n=Math.max(1,Math.ceil(Math.max(Math.abs(x-px),Math.abs(y-py))*2));
              for(var k=1;k<=n;k++)f(px+(x-px)*k/n,py+(y-py)*k/n);
            }
            function countPasses(){
              var key=filterKey();if(key==countKey)return;countKey=key;
              var cnt=new Map(),last=new Map(),id=0;
              function mark(x,y){var k=cellKey(x,y);if(last.get(k)!==id){last.set(k,id);cnt.set(k,(cnt.get(k)||0)+1)}}
              ACTS.forEach(function(act){if(!shown(act))return;id++;
                act.lines.forEach(function(l){for(var i=0;i<l.xy.length;i+=2)walk(l.xy,i,mark)})});
              // Each piece of a track gets the class of the typical (median) cell along it.
              ACTS.forEach(function(act){if(!shown(act))return;
                act.lines.forEach(function(l){var xy=l.xy,cls=l.cls||(l.cls=new Uint8Array(xy.length/2));
                  for(var i=2;i<xy.length;i+=2){var v=[];walk(xy,i,function(x,y){v.push(cnt.get(cellKey(x,y))||1)});
                    v.sort(function(a,b){return a-b});cls[i/2]=classOf(v[v.length>>1]);}
                })});
            }
            function legend(){
              var el=document.getElementById('countLegend'),on=ST.mode=='count';el.hidden=!on;
              document.getElementById('alphaLabel').style.display=on?'none':'';
              document.querySelectorAll('#modes a').forEach(function(a){a.classList.toggle('on',a.dataset.mode==(on?'count':'heat'))});
              if(!on)return;
              el.innerHTML=L('Passed:','Gepasseerd:')+CLASSES.map(function(c,i){
                return '<span><i style="background:'+c[2]+';height:'+(2+i)+'px"></i>'+c[1]+'</span>'}).join('');
            }
            document.getElementById('modes').onclick=function(e){
              var a=e.target.closest('a');if(!a)return;e.preventDefault();ST.mode=a.dataset.mode;store();legend();draw();
            };
            // ---- map
            map=L.map('map',{zoomAnimation:false,preferCanvas:true,worldCopyJump:false});
            // OpenStreetMap's own tiles (no key needed), grey so the heat stands out; inverted for the dark map.
            var OSM=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'});
            var BASES={dark:OSM,light:OSM};
            function setBase(){var b=ST.base=='light'?'light':'dark';
              Object.keys(BASES).forEach(function(k){if(k!=b&&map.hasLayer(BASES[k]))map.removeLayer(BASES[k])});
              if(!map.hasLayer(BASES[b]))BASES[b].addTo(map);base=b;
              document.querySelectorAll('#bases a').forEach(function(a){a.classList.toggle('on',a.dataset.base==b)});
              var m=document.getElementById('map');m.classList.toggle('dark',b=='dark');m.style.background=b=='dark'?'#0b0e11':'#f2f2f0';}
            // The heat layer lives in its own pane: above the tiles, below popups and controls.
            map.createPane('heat').style.zIndex=450;map.getPane('heat').style.pointerEvents='none';
            canvas=document.createElement('canvas');canvas.style.cssText='position:absolute;left:0;top:0';
            map.getPane('heat').appendChild(canvas);ctx=canvas.getContext('2d');
            function draw(){if(!frame)frame=requestAnimationFrame(paint)}
            function paint(){
              frame=0;var el=map.getContainer(),dpr=window.devicePixelRatio||1,sz=map.getSize();
              if(sz.x!=el.clientWidth||sz.y!=el.clientHeight){map.invalidateSize({pan:false});sz=map.getSize()}
              if(canvas.width!=sz.x*dpr||canvas.height!=sz.y*dpr){canvas.width=sz.x*dpr;canvas.height=sz.y*dpr;canvas.style.width=sz.x+'px';canvas.style.height=sz.y+'px'}
              L.DomUtil.setPosition(canvas,map.containerPointToLayerPoint([0,0]));
              ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);
              var z=map.getZoom(),scale=256*Math.pow(2,z),o=map.getPixelBounds().min;
              var vx0=o.x/scale,vy0=o.y/scale,vx1=(o.x+sz.x)/scale,vy1=(o.y+sz.y)/scale;
              var a=alpha.value/40,dark=base=='dark';
              ctx.setTransform(dpr,0,0,dpr,0,0);
              if(ST.mode=='count'){paintCounts(z,scale,o,vx0,vy0,vx1,vy1,dark);paintPlaces(z,scale,o,sz);return}
              ctx.globalCompositeOperation=dark?'lighter':'source-over';
              // Overlapping lines add up: few passes stay red, often-used streets go orange, yellow, then white.
              ctx.strokeStyle=dark?'rgba(255,72,24,'+a+')':'rgba(205,30,20,'+Math.min(1,a*.35)+')';
              ctx.lineWidth=z>=15?2.5:z>=13?2:z>=11?1.5:1;ctx.lineJoin='round';ctx.lineCap='round';
              var minLen=1/scale;
              ACTS.forEach(function(act){
                if(!shown(act))return;
                ctx.beginPath();var any=false;
                act.lines.forEach(function(l){
                  var b=l.b;if(b[2]<vx0||b[0]>vx1||b[3]<vy0||b[1]>vy1)return;
                  var xy=l.xy,px=xy[0]*scale-o.x,py=xy[1]*scale-o.y;ctx.moveTo(px,py);any=true;
                  for(var i=2;i<xy.length;i+=2){var x=xy[i]*scale-o.x,y=xy[i+1]*scale-o.y;
                    if(Math.abs(x-px)+Math.abs(y-py)<.7&&i<xy.length-2)continue;ctx.lineTo(x,y);px=x;py=y}
                });
                if(any)ctx.stroke();
              });
              paintPlaces(z,scale,o,sz);
            }
            // Zoomed out, a short trip far away is only a few pixels of line; a numbered dot per place shows where
            // in the world there are activities. Clicking a dot zooms in on it.
            var PLACES=[],PLACE_ZOOM=9;
            function placesAt(scale,o,sz){
              var cells={},out=[],G=46;
              ACTS.forEach(function(act){if(!shown(act)||!act.lines.length)return;
                var xy=act.lines[0].xy,x=xy[0]*scale-o.x,y=xy[1]*scale-o.y;
                if(x<-G||y<-G||x>sz.x+G||y>sz.y+G)return;
                var k=Math.floor(x/G)+','+Math.floor(y/G),c=cells[k];
                if(!c)c=cells[k]={n:0,sx:0,sy:0,x0:1,y0:1,x1:0,y1:0},out.push(c);
                c.n++;c.sx+=x;c.sy+=y;
                act.lines.forEach(function(l){var b=l.b;c.x0=Math.min(c.x0,b[0]);c.y0=Math.min(c.y0,b[1]);c.x1=Math.max(c.x1,b[2]);c.y1=Math.max(c.y1,b[3])});
              });
              function rad(c){c.x=c.sx/c.n;c.y=c.sy/c.n;c.r=7+Math.min(11,Math.log2(c.n)*1.6)}
              out.forEach(rad);out.sort(function(a,b){return b.n-a.n});
              // Dots that would touch are merged into the bigger one.
              for(var i=0;i<out.length;i++)for(var j=out.length-1;j>i;j--){var a=out[i],b=out[j];
                if(Math.hypot(a.x-b.x,a.y-b.y)<a.r+b.r+2){a.n+=b.n;a.sx+=b.sx;a.sy+=b.sy;a.x0=Math.min(a.x0,b.x0);a.y0=Math.min(a.y0,b.y0);
                  a.x1=Math.max(a.x1,b.x1);a.y1=Math.max(a.y1,b.y1);out.splice(j,1);rad(a);j=out.length}}
              return out.reverse();
            }
            function paintPlaces(z,scale,o,sz){
              PLACES=z<PLACE_ZOOM?placesAt(scale,o,sz):[];
              if(!PLACES.length)return;
              ctx.globalCompositeOperation='source-over';ctx.textAlign='center';ctx.textBaseline='middle';
              PLACES.forEach(function(c){
                ctx.beginPath();ctx.arc(c.x,c.y,c.r,0,2*Math.PI);
                ctx.fillStyle='#2563eb';ctx.fill();ctx.lineWidth=2;ctx.strokeStyle='#fff';ctx.stroke();
                ctx.fillStyle='#fff';ctx.font='600 '+(c.n>999?9:11)+'px -apple-system,BlinkMacSystemFont,sans-serif';
                ctx.fillText(c.n>9999?Math.round(c.n/1000)+'k':String(c.n),c.x,c.y+.5);
              });
            }
            function ll(x,y){return [Math.atan(Math.sinh(Math.PI*(1-2*y)))*180/Math.PI,x*360-180]}
            // Every jump (a dot, 🌍, 🇪🇺, 🇧🇪, a selected rectangle) is a step in the browser history, so ←/→,
            // the browser's back/forward buttons and Esc move through them.
            var HIST=[],POS=0;
            function view(){var c=map.getCenter();return [c.lat,c.lng,map.getZoom()]}
            try{history.replaceState({heatmap:0},'')}catch(e){}
            function pushView(from){HIST=HIST.slice(0,POS+1);HIST[POS]=from||view();POS++;
              try{history.pushState({heatmap:POS},'')}catch(e){}showNav()}
            function jump(b){pushView();map.fitBounds(b,{padding:[30,30],maxZoom:14})}
            window.addEventListener('popstate',function(e){var st=e.state;if(!st||st.heatmap==null)return;
              HIST[POS]=view();POS=st.heatmap;var v=HIST[POS];if(v)map.setView([v[0],v[1]],v[2]);showNav()});
            function showNav(){document.getElementById('hmBack').hidden=POS<=0;document.getElementById('hmFwd').hidden=POS>=HIST.length-1}
            document.addEventListener('keydown',function(e){
              if(e.key=='Escape'&&selecting){setSelect(false);return}
              if(e.key=='Escape'&&POS>0&&!document.querySelector('.leaflet-popup'))history.back()});
            function showAll(){var x0=1,y0=1,x1=0,y1=0;
              ACTS.forEach(function(a){if(!shown(a))return;a.lines.forEach(function(l){var b=l.b;
                x0=Math.min(x0,b[0]);y0=Math.min(y0,b[1]);x1=Math.max(x1,b[2]);y1=Math.max(y1,b[3])})});
              if(x1>=x0)jump([ll(x0,y1),ll(x1,y0)])}
            var REGIONS={eu:[[35,-11],[61,31]],be:[[49.5,2.55],[51.5,6.4]]};
            // Select: drag a rectangle to zoom into it (the button, or Shift+drag at any time).
            var selecting=false,box=null,boxStart=null,noClickUntil=0;
            function setSelect(on){selecting=on;document.getElementById('hmSel').classList.toggle('on',on);
              map.getContainer().style.cursor=on?'crosshair':''}
            map.boxZoom.disable();
            map.getContainer().addEventListener('mousedown',function(e){
              if(!(selecting||e.shiftKey)||e.button!==0||e.target.closest('.leaflet-control'))return;
              e.preventDefault();e.stopPropagation();map.dragging.disable();
              var r=map.getContainer().getBoundingClientRect();boxStart=[e.clientX-r.left,e.clientY-r.top];
              box=document.createElement('div');box.className='hm-box';map.getContainer().appendChild(box);
              function mv(ev){var x=ev.clientX-r.left,y=ev.clientY-r.top;
                box.style.left=Math.min(x,boxStart[0])+'px';box.style.top=Math.min(y,boxStart[1])+'px';
                box.style.width=Math.abs(x-boxStart[0])+'px';box.style.height=Math.abs(y-boxStart[1])+'px'}
              function up(ev){document.removeEventListener('mousemove',mv);document.removeEventListener('mouseup',up);
                var x=ev.clientX-r.left,y=ev.clientY-r.top;box.remove();box=null;noClickUntil=Date.now()+400;map.dragging.enable();setSelect(false);
                if(Math.abs(x-boxStart[0])<8||Math.abs(y-boxStart[1])<8)return;
                var a=map.containerPointToLatLng([boxStart[0],boxStart[1]]),b=map.containerPointToLatLng([x,y]);
                pushView();map.fitBounds([a,b]);}
              document.addEventListener('mousemove',mv);document.addEventListener('mouseup',up);
            },true);
            var Nav=L.Control.extend({options:{position:'topleft'},onAdd:function(){
              var d=L.DomUtil.create('div','leaflet-bar hm-ctl');
              d.innerHTML='<a href="#" id="hmAll" title="'+L('Show all places','Toon alle plaatsen')+'">🌍</a><a href="#" id="hmEu" title="Europa">🇪🇺</a>'
                +'<a href="#" id="hmBe" title="'+L('Belgium','België')+'">🇧🇪</a><a href="#" id="hmSel" title="'+L('Select an area to zoom in (or Shift+drag)','Kies een gebied om in te zoomen (of Shift+slepen)')+'">⬚</a>'
                +'<a href="#" id="hmBack" title="'+L('Back (Esc)','Terug (Esc)')+'" hidden>←</a><a href="#" id="hmFwd" title="'+L('Forward','Vooruit')+'" hidden>→</a>';
              L.DomEvent.disableClickPropagation(d);
              function on(id,f){d.querySelector('#'+id).onclick=function(e){e.preventDefault();f()}}
              on('hmAll',showAll);on('hmEu',function(){jump(REGIONS.eu)});on('hmBe',function(){jump(REGIONS.be)});
              on('hmSel',function(){setSelect(!selecting)});
              on('hmBack',function(){history.back()});on('hmFwd',function(){history.forward()});
              return d}});
            new Nav().addTo(map);
            function placeAt(p){for(var i=PLACES.length-1;i>=0;i--){var c=PLACES[i];if(Math.hypot(p.x-c.x,p.y-c.y)<=c.r+3)return c}return null}
            // Solid lines, one colour per class; the busier classes are drawn last so they stay on top.
            function paintCounts(z,scale,o,vx0,vy0,vx1,vy1,dark){
              countPasses();
              var paths=CLASSES.map(function(){return new Path2D()});
              ACTS.forEach(function(act){if(!shown(act))return;
                act.lines.forEach(function(l){var b=l.b;if(b[2]<vx0||b[0]>vx1||b[3]<vy0||b[1]>vy1)return;
                  var xy=l.xy,cls=l.cls,px=xy[0]*scale-o.x,py=xy[1]*scale-o.y,pc=-1;
                  for(var i=2;i<xy.length;i+=2){var x=xy[i]*scale-o.x,y=xy[i+1]*scale-o.y,c=cls[i/2];
                    if(c==pc&&Math.abs(x-px)+Math.abs(y-py)<.7&&i<xy.length-2)continue;
                    if(c!=pc)paths[c].moveTo(px,py);paths[c].lineTo(x,y);px=x;py=y;pc=c}
                })});
              var w=z>=15?2.5:z>=13?2:z>=11?1.5:1.2;
              ctx.globalCompositeOperation='source-over';ctx.lineJoin='round';ctx.lineCap='round';
              paths.forEach(function(p,c){var lw=w*.8+c*(z>=13?.4:.25);
                if(!dark){ctx.strokeStyle='rgba(20,24,30,.55)';ctx.lineWidth=lw+1.6;ctx.stroke(p)}
                ctx.strokeStyle=CLASSES[c][2];ctx.lineWidth=lw;ctx.stroke(p)});
            }
            map.on('move zoom resize',draw);window.addEventListener('load',draw);
            // The map's size settles after the page layout (fonts, wrapping filters); follow it.
            if(window.ResizeObserver)new ResizeObserver(function(){map.invalidateSize({pan:false});draw()}).observe(map.getContainer());
            // Click: the activities whose track passes within a few pixels.
            map.on('click',function(e){
              var z=map.getZoom(),scale=256*Math.pow(2,z),o=map.getPixelBounds().min,p=map.latLngToContainerPoint(e.latlng);
              if(Date.now()<noClickUntil)return;
              var pl=placeAt(p);
              if(pl){jump([ll(pl.x0,pl.y1),ll(pl.x1,pl.y0)]);return}
              var cx=(p.x+o.x)/scale,cy=(p.y+o.y)/scale,r=8/scale,hits=[];
              ACTS.forEach(function(act){
                if(!shown(act))return;
                for(var li=0;li<act.lines.length;li++){var l=act.lines[li],b=l.b;
                  if(cx<b[0]-r||cx>b[2]+r||cy<b[1]-r||cy>b[3]+r)continue;
                  var xy=l.xy;for(var i=2;i<xy.length;i+=2){if(segDist(cx,cy,xy[i-2],xy[i-1],xy[i],xy[i+1])<r){hits.push(act);return}}}
              });
              if(!hits.length)return;
              hits.reverse();
              var h='<div class="hm-pop"><b>'+hits.length+(hits.length==1?L(' activity passed here',' activiteit kwam hier langs'):L(' activities passed here',' activiteiten kwamen hier langs'))+'</b>'+hits.slice(0,200).map(function(a){
                return '<a href="'+href(a.date)+(a.id?'#a'+a.id:'')+'">'+a.date+' '+esc(a.time)+' · '+esc(a.name||a.sport)+' <span>'+a.km.toFixed(1)+' km</span></a>'}).join('')
                +(hits.length>200?'<span>'+L('… and ','… en nog ')+(hits.length-200)+L(' more','')+'</span>':'')+'</div>';
              L.popup({maxWidth:360}).setLatLng(e.latlng).setContent(h).openOn(map);
            });
            function segDist(px,py,ax,ay,bx,by){var dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy,t=l?Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/l)):0;
              return Math.hypot(px-ax-t*dx,py-ay-t*dy)}
            // Start where the last visit left off, else around the most common start of an activity.
            function home(){
              var cells={},best=null;
              ACTS.forEach(function(a){var l=a.lines[0];if(!l)return;var k=Math.round(l.xy[0]*2000)+','+Math.round(l.xy[1]*2000);
                cells[k]=(cells[k]||0)+1;if(!best||cells[k]>cells[best])best=k});
              if(!best)return [[50.85,4.35],8];
              var c=best.split(',').map(Number),x=c[0]/2000,y=c[1]/2000;
              var lat=Math.atan(Math.sinh(Math.PI*(1-2*y)))*180/Math.PI;return [[lat,x*360-180],12];
            }
            setBase();
            if(ST.view)map.setView([ST.view[0],ST.view[1]],ST.view[2]);else{var h0=home();map.setView(h0[0],h0[1])}
            map.on('moveend',function(){var c=map.getCenter();ST.view=[+c.lat.toFixed(5),+c.lng.toFixed(5),map.getZoom()];store()});
            changed();
            """;
}
