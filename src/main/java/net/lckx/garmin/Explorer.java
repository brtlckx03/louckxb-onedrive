package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Explorer squares": the world cut into map tiles at zoom 14 (OpenStreetMap's slippy tiles, about 1.5 km wide in
 * Belgium). A tile is explored once any GPS track passes through it. The page shows how many tiles are explored, the
 * biggest fully explored square of tiles (VeloViewer's "max square"), the cluster (the largest connected group of tiles
 * whose four neighbours are all explored), which tiles to visit next to grow both, and how the count grew per year.
 * <p>
 * Reads the tracks of the heatmap ({@code heatmap-data.js}, written by {@link Heatmap}), so run it after that. The
 * tiles are worked out here (an exact walk along every segment of every track); the square, the cluster and the
 * suggestions are worked out in the browser, so the sport filter can redo them instantly. Virtual rides are left out:
 * their tracks are somewhere the rider never was.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.Explorer [--account NAME]
 * </pre>
 */
public final class Explorer {
    static final String PAGE_FILE = "explorer.html";
    private static final String DATA_FILE = "explorer-data.js";
    private static final int ZOOM = 14;
    private static final double SIZE = 1 << ZOOM;

    private Explorer() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: Explorer [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Explorer squares updated: " + write().toAbsolutePath());
    }

    private record Activity(String date, String time, String sport, String cls, String name, double km, String id, List<String> lines) {}

    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        Path source = dir.resolve("heatmap-data.js");
        List<Activity> acts = Files.exists(source) ? read(Files.readString(source, StandardCharsets.UTF_8)) : List.of();
        acts.sort(Comparator.comparing(Activity::date).thenComparing(Activity::time));

        // tile (x * 2^14 + y) -> the activities that passed through it, oldest first
        Map<Long, List<Integer>> tiles = new HashMap<>();
        Map<String, String> icons = new TreeMap<>();
        StringBuilder js = new StringBuilder("window.EXPLORER={z:").append(ZOOM).append(",acts:[");
        int index = 0;
        for (Activity a : acts) {
            Set<Long> seen = new LinkedHashSet<>();
            for (String line : a.lines()) walk(decode(line), seen);
            if (seen.isEmpty()) continue;
            int i = index++;
            for (long t : seen) tiles.computeIfAbsent(t, k -> new ArrayList<>(2)).add(i);
            // [date, name, garmin id, sport class, km, sport]
            js.append(i == 0 ? "\n" : ",\n").append('[').append(jsStr(a.date())).append(',').append(jsStr(a.name())).append(',')
                    .append(jsStr(a.id())).append(',').append(jsStr(a.cls())).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", a.km())).append(',').append(jsStr(a.sport())).append(']');
            icons.put(a.sport(), DescribeGarminFile.sportIcon(a.sport()));
        }
        // tiles as one flat list: x, y, number of activities, their indexes
        js.append("\n],tiles:[");
        boolean first = true;
        int col = 0;
        for (var e : new TreeMap<>(tiles).entrySet()) {
            long key = e.getKey();
            StringBuilder t = new StringBuilder();
            t.append(key / (long) SIZE).append(',').append(key % (long) SIZE).append(',').append(e.getValue().size());
            for (int i : e.getValue()) t.append(',').append(i);
            js.append(first ? "" : ",");
            if ((col += t.length()) > 2000) {
                js.append('\n');
                col = 0;
            }
            js.append(t);
            first = false;
        }
        js.append("],icons:{");
        boolean firstIcon = true;
        for (var i : icons.entrySet()) {
            js.append(firstIcon ? "" : ",").append(jsStr(i.getKey())).append(':').append(jsStr(i.getValue()));
            firstIcon = false;
        }
        js.append("}};\n");
        replace(dir.resolve(DATA_FILE), js.toString());
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page());
        System.out.printf("Explorer squares: %d tiles from %d activities with GPS.%n", tiles.size(), index);
        return page;
    }

    // ---- reading heatmap-data.js: one activity per line, [date,time,sport,group,name,km,secs,[polylines],garminId]

    private static List<Activity> read(String js) {
        List<Activity> out = new ArrayList<>();
        for (String line : js.split("\n")) {
            line = line.strip();
            if (!line.startsWith("[\"")) continue;
            Object v = new Parser(line).value();
            if (!(v instanceof List<?> r) || r.size() < 9 || !(r.get(7) instanceof List<?> polys)) continue;
            String sport = str(r.get(2)), group = str(r.get(3));
            // Virtual rides and anything indoors: the track is not somewhere the athlete was.
            if (group.startsWith("indoor") || sport.startsWith("virtual") || sport.contains("treadmill")) continue;
            List<String> lines = new ArrayList<>();
            for (Object p : polys) if (p instanceof String s && !s.isEmpty()) lines.add(s);
            if (lines.isEmpty()) continue;
            String cls = switch (group) {
                case "running" -> "run";
                case "cycling" -> "ride";
                case "walking" -> "walk";
                default -> "other";
            };
            double km = r.get(5) instanceof Number n ? n.doubleValue() : 0;
            out.add(new Activity(str(r.get(0)), str(r.get(1)), sport, cls, str(r.get(4)), km, str(r.get(8)), lines));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Just enough of a JavaScript literal parser for the heatmap rows: arrays, strings, numbers, null. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                while (true) {
                    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                    if (i >= s.length()) return list;
                    if (s.charAt(i) == ']') {
                        i++;
                        return list;
                    }
                    list.add(value());
                    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                    if (i < s.length() && s.charAt(i) == ',') i++;
                }
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    char ch = s.charAt(i++);
                    if (ch == '\\' && i < s.length()) {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n' -> b.append('\n');
                            case 't' -> b.append('\t');
                            case 'u' -> {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            default -> b.append(e);
                        }
                    } else b.append(ch);
                }
                i++;
                return b.toString();
            }
            int start = i;
            while (i < s.length() && ",]".indexOf(s.charAt(i)) < 0) i++;
            String tok = s.substring(start, i).strip();
            if (tok.equals("null") || tok.isEmpty()) return null;
            try {
                return Double.parseDouble(tok);
            } catch (NumberFormatException e) {
                return tok;
            }
        }
    }

    // ---- tracks to tiles

    /** Google's encoded polyline (1e-5°) to points in tile units at zoom 14: [x0, y0, x1, y1, …]. */
    private static double[] decode(String enc) {
        List<double[]> pts = new ArrayList<>();
        int i = 0;
        long lat = 0, lon = 0;
        while (i < enc.length()) {
            long[] d = new long[2];
            for (int k = 0; k < 2; k++) {
                long r = 0;
                int sh = 0, b;
                do {
                    if (i >= enc.length()) return flat(pts);
                    b = enc.charAt(i++) - 63;
                    r |= (long) (b & 31) << sh;
                    sh += 5;
                } while (b >= 32);
                d[k] = (r & 1) != 0 ? ~(r >> 1) : (r >> 1);
            }
            lat += d[0];
            lon += d[1];
            double la = Math.max(-85, Math.min(85, lat / 1e5)), lo = lon / 1e5;
            double x = (lo + 180) / 360 * SIZE, sn = Math.sin(Math.toRadians(la));
            double y = (0.5 - Math.log((1 + sn) / (1 - sn)) / (4 * Math.PI)) * SIZE;
            pts.add(new double[] {x, y});
        }
        return flat(pts);
    }

    private static double[] flat(List<double[]> pts) {
        double[] xy = new double[pts.size() * 2];
        for (int j = 0; j < pts.size(); j++) {
            xy[2 * j] = pts.get(j)[0];
            xy[2 * j + 1] = pts.get(j)[1];
        }
        return xy;
    }

    /** Every tile a line passes through: an exact grid walk along each segment, so long straight segments miss nothing. */
    private static void walk(double[] xy, Set<Long> out) {
        if (xy.length < 2) return;
        add(out, (long) Math.floor(xy[0]), (long) Math.floor(xy[1]));
        for (int j = 2; j + 1 < xy.length; j += 2) {
            double x0 = xy[j - 2], y0 = xy[j - 1], x1 = xy[j], y1 = xy[j + 1];
            long ix = (long) Math.floor(x0), iy = (long) Math.floor(y0), ex = (long) Math.floor(x1), ey = (long) Math.floor(y1);
            if (ix == ex && iy == ey) continue;
            double dx = x1 - x0, dy = y1 - y0;
            int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1;
            double tdx = dx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx), tdy = dy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
            double tx = dx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? ix + 1 - x0 : x0 - ix) * tdx;
            double ty = dy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? iy + 1 - y0 : y0 - iy) * tdy;
            long steps = Math.abs(ex - ix) + Math.abs(ey - iy);
            for (long n = 0; n < steps; n++) {
                if (tx < ty) {
                    ix += sx;
                    tx += tdx;
                } else {
                    iy += sy;
                    ty += tdy;
                }
                add(out, ix, iy);
            }
        }
    }

    private static void add(Set<Long> out, long x, long y) {
        long n = (long) SIZE;
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        out.add(x * n + y);
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- the page

    private static String page() {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        LocalDateTime now = LocalDateTime.now();
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#3b4fd8")
                + "<title>Explorer squares · " + esc(label) + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + Site.HEAD + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<style>" + CSS + "</style></head><body>"
                + BODY.replace("{{LABEL}}", esc(label)).replace("{{NAV}}", Site.nav(PAGE_FILE, 0))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(now))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.of("nl")).format(now))
                + "<script src=\"" + DATA_FILE + "\"></script>"
                + "<script>" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --run:#d95926;--ride:#3987e5;--walk:#199e70;--other:#8792a8;--gold:#ffc94a;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;background:var(--night);color:var(--fog);font:17px/1.55 var(--text)}
            a{color:var(--moon)}
            :focus-visible{outline:2px solid var(--moon);outline-offset:2px}
            button{font:inherit;color:var(--moon);background:var(--deep);border:1px solid var(--rule);border-radius:999px;padding:6px 14px;cursor:pointer}
            button:hover{border-color:var(--dim)}
            button[aria-pressed=true]{background:var(--moon);color:var(--night);border-color:var(--moon)}
            .app{display:grid;grid-template-columns:minmax(0,1fr) 400px;height:calc(100vh - var(--site-h,44px));min-height:600px}
            .stage{position:relative;overflow:hidden}
            #map{position:absolute;inset:0;background:var(--night)}
            #map .leaflet-tile-pane{filter:grayscale(1) invert(1) brightness(.34) contrast(1.3) sepia(.35) hue-rotate(185deg);opacity:.6}
            .leaflet-container{font:13px var(--text)}
            .stage .leaflet-top{top:var(--top-h,64px)}
            .leaflet-control-attribution{background:rgba(10,18,32,.6)!important;color:var(--dim)!important}
            .leaflet-control-attribution a{color:var(--fog)!important}
            .top{position:absolute;left:0;right:0;top:0;z-index:600;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;
              gap:10px 24px;padding:12px 20px 24px 28px;pointer-events:none;background:linear-gradient(var(--night) 40%,rgba(10,18,32,0))}
            .top>*{pointer-events:auto}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em;flex:none}
            .title b{font-weight:inherit}
            .title span{color:var(--dim);font-weight:500}
            .top .site-nav{order:-1;flex:1 1 100%;min-width:0;margin-left:-9px;color:var(--fog);font-family:var(--text);
              --site-active-bg:var(--moon);--site-active-fg:var(--night)}
            .top .site-pages{min-width:0;max-width:100%}
            .top .site-switch{margin-left:auto}
            .hud{position:absolute;left:28px;bottom:28px;z-index:600;max-width:min(560px,calc(100% - 56px));pointer-events:none}
            .hud::before{content:'';position:absolute;z-index:-1;inset:-120px -220px -60px -120px;pointer-events:none;
              background:radial-gradient(closest-side,rgba(10,18,32,.9),rgba(10,18,32,.6) 55%,rgba(10,18,32,0))}
            .hud *{pointer-events:auto}
            #big{font:800 clamp(56px,9vw,124px)/.84 var(--display);color:var(--moon);margin:0;font-variant-numeric:tabular-nums}
            #big small{display:block;font-weight:500;font-size:.34em;color:var(--fog);margin:0 0 .3em .04em}
            #sub{margin:14px 0 0;font-size:16px;max-width:52ch}
            .legend{display:flex;flex-wrap:wrap;gap:6px 18px;margin:12px 0 0;padding:0;list-style:none;font-size:14px}
            .legend li{display:flex;align-items:center;gap:8px}
            .legend i{width:14px;height:14px;border-radius:2px;flex:none}
            .log{background:var(--deep);border-left:1px solid var(--rule);overflow-y:auto}
            .log>div{padding:22px 22px 0}
            .log>div:last-child{padding-bottom:30px}
            .log h2{font:700 30px/1 var(--display);color:var(--moon);margin:0}
            .log h3{font:700 22px/1 var(--display);color:var(--moon);margin:0 0 6px}
            .log p{margin:6px 0 0;font-size:14px}
            .note{color:var(--dim)}
            .filters{display:flex;gap:6px;margin-top:12px;flex-wrap:wrap}
            .filters button{padding:4px 11px;font-size:14px;display:inline-flex;align-items:center;gap:7px}
            .filters i{width:9px;height:9px;border-radius:50%}
            .stats{display:grid;grid-template-columns:1fr 1fr;gap:16px 22px;margin:20px 0 0}
            .stats div{border-top:1px solid var(--rule);padding-top:10px}
            .stats dt{font-size:14px;color:var(--dim)}
            .stats dd{margin:2px 0 0;font:700 34px/1.05 var(--display);color:var(--moon);font-variant-numeric:tabular-nums}
            .stats dd small{display:block;font:400 13px/1.4 var(--text);color:var(--fog);margin-top:3px}
            .sugg{border-left:3px solid var(--gold);padding:2px 0 2px 14px;margin-top:22px}
            .sugg.cl{border-color:var(--moon)}
            .sugg b{color:var(--moon)}
            .sugg button{margin-top:8px;padding:4px 11px;font-size:14px}
            #yearsChart{display:block;width:100%;height:auto;margin-top:8px;overflow:visible}
            #tile ol{list-style:none;margin:10px 0 0;padding:0}
            #tile li{border-top:1px solid var(--rule);padding:7px 0;font-size:14px;display:flex;gap:10px;align-items:baseline}
            #tile li a{color:var(--moon);text-decoration:none;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            #tile li a:hover{text-decoration:underline}
            #tile li span{color:var(--dim);flex:none;font-variant-numeric:tabular-nums}
            #tile li em{font-style:normal;color:var(--gold);flex:none;font-size:13px}
            footer{border-top:1px solid var(--rule);margin-top:26px;padding:16px 0 0;font-size:13px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:14px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:8px 11px;font-size:14px;line-height:1.45;color:var(--fog);max-width:320px;box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            @media (max-width:900px){.app{grid-template-columns:1fr;height:auto}.stage{height:78vh;min-height:460px}
              .log{border-left:0;border-top:1px solid var(--rule);overflow:visible}
              
              .hud{left:16px;bottom:16px;max-width:calc(100% - 32px)}.top{padding:12px 16px 18px;gap:8px}.log>div{padding:20px 16px 0}}
            @media (prefers-reduced-motion:reduce){*{scroll-behavior:auto!important}}
            """;

    private static final String BODY = """
            <div class="app">
              <section class="stage" aria-label="Map of explored tiles" data-nl-aria="Kaart van de verkende vakjes">
                <div id="map"></div>
                <div class="top"><p class="title"><b data-nl="Verkenningsvakjes">Explorer squares</b> <span>{{LABEL}}</span></p>
                  {{NAV}}</div>
                <div class="hud">
                  <p id="big"></p>
                  <p id="sub"></p>
                  <ul class="legend" id="legend"></ul>
                </div>
              </section>
              <aside class="log" aria-label="Explorer squares" data-nl-aria="Verkenningsvakjes">
                <div>
                  <h2 data-nl="Jouw vakjes">Your tiles</h2>
                  <p class="note" data-nl="De kaart verdeeld in vakjes van ongeveer 1,5 km. Fiets, loop of wandel door een vakje en het is van jou.">The map cut into squares of about 1.5 km. Ride, run or walk through one and it is yours.</p>
                  <div class="filters" id="filters" role="group" aria-label="Count tiles from" data-nl-aria="Tel de vakjes van"></div>
                  <dl class="stats" id="stats"></dl>
                  <div id="suggest"></div>
                </div>
                <div>
                  <h3 data-nl="Nieuwe vakjes per jaar">New tiles per year</h3>
                  <p class="note" id="yearsNote"></p>
                  <svg id="yearsChart" role="img" aria-label="New tiles explored per year" data-nl-aria="Nieuw verkende vakjes per jaar"></svg>
                </div>
                <div id="tile" aria-live="polite">
                  <h3 data-nl="Een vakje">A tile</h3>
                  <p class="note" data-nl="Klik op een vakje op de kaart om de activiteiten te zien die het verkend hebben.">Click a tile on the map to see the activities that explored it.</p>
                </div>
                <div><footer data-nl="Gemaakt uit je GPS-sporen op {{UPDATED_NL}}. De vakjes zijn OpenStreetMap-tegels op zoomniveau 14. Virtuele ritten tellen niet mee.">
                  Built from your GPS tracks on {{UPDATED}}. Tiles are OpenStreetMap tiles at zoom 14. Virtual rides are left out.</footer></div>
              </aside>
            </div>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var D=window.EXPLORER||{acts:[],tiles:[],icons:{}},ACTS=D.acts,ICONS=D.icons||{},Z=D.z||14,N=Math.pow(2,Z);
            var COLOR={run:'#d95926',ride:'#3987e5',walk:'#199e70',other:'#8792a8'},GOLD='#ffc94a',MOON='#eef1f7';
            var NL=window.LANG=='nl';
            var LABEL={run:L('Running','Hardlopen'),ride:L('Cycling','Fietsen'),walk:L('Walking','Wandelen'),other:L('Other','Andere')};
            var ORDER=['run','ride','walk','other'];
            var MONTHS=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            if(NL)document.title=document.title.replace('Explorer squares','Verkenningsvakjes');
            // The zoom buttons sit just below the top bar, however high it wraps.
            var topBar=document.querySelector('.top'),stage=document.querySelector('.stage');
            function topH(){stage.style.setProperty('--top-h',(topBar.offsetHeight+4)+'px')}
            topH();if(window.ResizeObserver)new ResizeObserver(topH).observe(topBar);else addEventListener('resize',topH);
            // On a phone the menu scrolls sideways: start it at this page.
            (function(){var cur=document.querySelector('.site-pages [aria-current]'),row=cur&&cur.parentNode;
              if(row&&row.scrollWidth>row.clientWidth)row.scrollLeft+=cur.getBoundingClientRect().left-row.getBoundingClientRect().left-row.clientWidth/2+cur.offsetWidth/2})();
            var reduced=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
            var THIS_YEAR=String(new Date().getFullYear());
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,' ')}
            function dec(s){return NL?String(s).replace('.',','):String(s)}
            function fmtDate(s){return +s.slice(8,10)+' '+MONTHS[+s.slice(5,7)-1]+' '+s.slice(0,4)}
            function href(a){return a[0].slice(0,4)+'/'+a[0].slice(5,7)+'/'+a[0]+'-report.html'+(a[2]?'#a'+a[2]:'')}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function rgba(hex,a){var n=parseInt(hex.slice(1),16);return 'rgba('+(n>>16)+','+(n>>8&255)+','+(n&255)+','+a+')'}
            function key(x,y){return x*N+y}
            var tip=document.getElementById('tip');
            function showTip(html,x,y){tip.innerHTML=html;tip.hidden=false;var w=tip.offsetWidth,h=tip.offsetHeight;
              tip.style.left=Math.max(8,Math.min(innerWidth-w-8,x+14))+'px';tip.style.top=(y+h+20>innerHeight?y-h-14:y+16)+'px'}
            function hideTip(){tip.hidden=true}

            // ---- every tile with the activities that crossed it (oldest first)
            var TILES=new Map(),T=D.tiles;
            for(var i=0;i<T.length;){var x=T[i],y=T[i+1],n=T[i+2],list=T.slice(i+3,i+3+n);i+=3+n;TILES.set(key(x,y),{x:x,y:y,acts:list})}
            var present={};ACTS.forEach(function(a){present[a[3]]=(present[a[3]]||0)+1});
            // Home: the tile most activities pass through.
            var home=null;TILES.forEach(function(t){if(!home||t.acts.length>home.acts.length)home=t});
            if(!home){document.getElementById('big').textContent=L('No GPS tracks yet','Nog geen GPS-sporen');return}
            var HX=home.x+.5,HY=home.y+.5;
            function lat(y){return Math.atan(Math.sinh(Math.PI*(1-2*y/N)))*180/Math.PI}
            function lon(x){return x/N*360-180}
            var KM=40075*Math.cos(lat(HY)*Math.PI/180)/N;  // width of a tile near home, km
            function homeDist(x,y){var dx=x-HX,dy=y-HY;return Math.sqrt(dx*dx+dy*dy)*KM}

            // ---- what one filter gives: explored tiles, max square, cluster, suggestions, growth
            var cache={};
            function compute(f){
              if(cache[f])return cache[f];
              var set=new Map(),years={};
              TILES.forEach(function(t,k){
                var acts=f=='all'?t.acts:t.acts.filter(function(i){return ACTS[i][3]==f});if(!acts.length)return;
                var cnt={};acts.forEach(function(i){var c=ACTS[i][3];cnt[c]=(cnt[c]||0)+1});
                var dom=ORDER.reduce(function(m,c){return (cnt[c]||0)>(cnt[m]||0)?c:m},'other');
                var y=ACTS[acts[0]][0].slice(0,4);years[y]=(years[y]||0)+1;
                set.set(k,{x:t.x,y:t.y,acts:acts,c:dom})});
              function has(x,y){return set.has(key(x,y))}
              // Max square: for each tile the side of the biggest square ending there (dynamic programming, rows top to bottom).
              var sorted=Array.from(set.values()).sort(function(a,b){return a.y-b.y||a.x-b.x}),dp=new Map(),best=0,squares=[];
              sorted.forEach(function(t){var a=dp.get(key(t.x-1,t.y))||0,b=dp.get(key(t.x,t.y-1))||0,c=dp.get(key(t.x-1,t.y-1))||0,
                  v=1+Math.min(a,b,c);dp.set(key(t.x,t.y),v);
                if(v>best){best=v;squares=[]}if(v==best)squares.push({x:t.x-v+1,y:t.y-v+1,n:v})});
              squares.forEach(function(s){s.d=homeDist(s.x+s.n/2,s.y+s.n/2)});squares.sort(function(a,b){return a.d-b.d});
              var sq=squares[0]||null;
              // Growing it: the (n+1)-square near it with the fewest unexplored tiles, nearest home on a tie.
              var grow=null;
              if(sq){squares.slice(0,25).forEach(function(s){var m=s.n+1;
                for(var wx=s.x-3;wx<=s.x+2;wx++)for(var wy=s.y-3;wy<=s.y+2;wy++){var miss=[];
                  for(var xx=wx;xx<wx+m&&(!grow||miss.length<=grow.miss.length);xx++)for(var yy=wy;yy<wy+m;yy++)if(!has(xx,yy))miss.push([xx,yy]);
                  if(!miss.length)continue;var d=homeDist(wx+m/2,wy+m/2);
                  if(!grow||miss.length<grow.miss.length||(miss.length==grow.miss.length&&d<grow.d))grow={x:wx,y:wy,n:m,miss:miss,d:d}}})}
              // Cluster: tiles whose four neighbours are all explored; the biggest connected group of them.
              function clusterOf(hasFn){var inner=new Set();
                set.forEach(function(t,k){if(hasFn(t.x+1,t.y)&&hasFn(t.x-1,t.y)&&hasFn(t.x,t.y+1)&&hasFn(t.x,t.y-1))inner.add(k)});
                extra.forEach(function(k){var x=Math.floor(k/N),y=k%N;if(hasFn(x+1,y)&&hasFn(x-1,y)&&hasFn(x,y+1)&&hasFn(x,y-1))inner.add(k)});
                var seen=new Set(),bestC=[];
                inner.forEach(function(k){if(seen.has(k))return;var comp=[],stack=[k];seen.add(k);
                  while(stack.length){var c=stack.pop(),x=Math.floor(c/N),y=c%N;comp.push(c);
                    [[1,0],[-1,0],[0,1],[0,-1]].forEach(function(o){var nk=key(x+o[0],y+o[1]);if(inner.has(nk)&&!seen.has(nk)){seen.add(nk);stack.push(nk)}})}
                  if(comp.length>bestC.length)bestC=comp});
                return {tiles:bestC,inner:inner}}
              var extra=[],cl=clusterOf(has),clSet=new Set(cl.tiles);
              // Growing it: unexplored tiles near the cluster that would turn the most neighbours into cluster tiles.
              var cand=new Map();
              cl.tiles.forEach(function(k){var x=Math.floor(k/N),y=k%N;
                for(var dx=-2;dx<=2;dx++)for(var dy=-2;dy<=2;dy++){var nk=key(x+dx,y+dy);if(!set.has(nk)&&!cand.has(nk))cand.set(nk,[x+dx,y+dy])}});
              var picks=[];
              cand.forEach(function(p,k){var gain=0;
                function h2(x,y){return (x==p[0]&&y==p[1])||has(x,y)}
                [[0,0],[1,0],[-1,0],[0,1],[0,-1]].forEach(function(o){var x=p[0]+o[0],y=p[1]+o[1],tk=key(x,y);
                  if(clSet.has(tk)||!h2(x,y))return;
                  if(!(h2(x+1,y)&&h2(x-1,y)&&h2(x,y+1)&&h2(x,y-1)))return;
                  if(clSet.has(key(x+1,y))||clSet.has(key(x-1,y))||clSet.has(key(x,y+1))||clSet.has(key(x,y-1)))gain++});
                if(gain)picks.push({x:p[0],y:p[1],gain:gain,d:homeDist(p[0]+.5,p[1]+.5)})});
              picks.sort(function(a,b){return (b.gain>=2)-(a.gain>=2)||a.d-b.d||b.gain-a.gain});
              picks=picks.slice(0,8);
              var clGrowTo=cl.tiles.length;
              if(picks.length){var add=new Set(picks.map(function(p){return key(p.x,p.y)}));extra=Array.from(add);
                var after=clusterOf(function(x,y){return has(x,y)||add.has(key(x,y))});clGrowTo=after.tiles.length;extra=[]}
              var r={f:f,set:set,years:years,sq:sq,squares:squares.length,grow:grow,cluster:clSet,clusterSize:cl.tiles.length,
                clPicks:picks,clGrowTo:clGrowTo};
              r.suggest=new Map();
              if(grow)grow.miss.forEach(function(p){r.suggest.set(key(p[0],p[1]),'square')});
              picks.forEach(function(p){var k=key(p.x,p.y);if(!r.suggest.has(k))r.suggest.set(k,'cluster')});
              return cache[f]=r;
            }

            // ---- the map with a canvas of tiles, redrawn when it moves
            var map=L.map('map',{zoomControl:false,minZoom:3,maxZoom:17});
            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
            L.control.zoom({position:'topright'}).addTo(map);
            map.createPane('squares').style.zIndex=450;map.getPane('squares').style.pointerEvents='none';
            var cv=document.createElement('canvas');cv.style.cssText='position:absolute;left:0;top:0';map.getPane('squares').appendChild(cv);
            var ctx=cv.getContext('2d'),R=null,F='all',sel=null;
            function bounds(x,y,n){return L.latLngBounds([lat(y+n),lon(x)],[lat(y),lon(x+n)])}
            function draw(){
              if(!R)return;
              var z=map.getZoom(),sz=map.getSize(),o=map.getPixelBounds().min,s=256*Math.pow(2,z)/N,dpr=window.devicePixelRatio||1;
              if(cv.width!=sz.x*dpr||cv.height!=sz.y*dpr){cv.width=sz.x*dpr;cv.height=sz.y*dpr;cv.style.width=sz.x+'px';cv.style.height=sz.y+'px'}
              L.DomUtil.setPosition(cv,map.containerPointToLayerPoint([0,0]));
              ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,sz.x,sz.y);
              var x0=o.x/s-1,y0=o.y/s-1,x1=(o.x+sz.x)/s,y1=(o.y+sz.y)/s,gap=s>6?1:0;
              function px(x){return x*s-o.x}function py(y){return y*s-o.y}
              var fills={};ORDER.forEach(function(c){fills[c]=[rgba(COLOR[c],.26),rgba(COLOR[c],.62)]});
              R.set.forEach(function(t,k){if(t.x<x0||t.x>x1||t.y<y0||t.y>y1)return;
                ctx.fillStyle=fills[t.c][R.cluster.has(k)?1:0];ctx.fillRect(px(t.x)+gap/2,py(t.y)+gap/2,Math.max(1,s-gap),Math.max(1,s-gap))});
              ctx.setLineDash(s>14?[5,4]:[3,3]);ctx.lineWidth=s>14?2:1.3;
              R.suggest.forEach(function(kind,k){var x=Math.floor(k/N),y=k%N;if(x<x0||x>x1||y<y0||y>y1)return;
                ctx.strokeStyle=kind=='square'?GOLD:MOON;ctx.strokeRect(px(x)+2,py(y)+2,Math.max(1,s-4),Math.max(1,s-4))});
              ctx.setLineDash([]);
              if(R.grow){ctx.strokeStyle=rgba(GOLD,.45);ctx.lineWidth=1;ctx.setLineDash([2,4]);
                ctx.strokeRect(px(R.grow.x),py(R.grow.y),R.grow.n*s,R.grow.n*s);ctx.setLineDash([])}
              if(R.sq){ctx.strokeStyle=GOLD;ctx.lineWidth=3;ctx.shadowColor=GOLD;ctx.shadowBlur=10;
                ctx.strokeRect(px(R.sq.x),py(R.sq.y),R.sq.n*s,R.sq.n*s);ctx.shadowBlur=0}
              if(sel){ctx.strokeStyle=MOON;ctx.lineWidth=2.5;ctx.strokeRect(px(sel[0])-1,py(sel[1])-1,s+2,s+2)}
            }
            map.on('move zoom resize viewreset zoomend moveend',draw);
            function tileAt(e){var p=map.project(e.latlng,map.getZoom()),s=256*Math.pow(2,map.getZoom())/N;return [Math.floor(p.x/s),Math.floor(p.y/s)]}
            function actLine(i){var a=ACTS[i];return (ICONS[a[5]]||'')+' '+esc(a[1]||LABEL[a[3]])+', '+fmtDate(a[0])}
            map.on('mousemove',function(e){if(!R)return;var t=tileAt(e),k=key(t[0],t[1]),tile=R.set.get(k),kind=R.suggest.get(k),s;
              if(tile){var one=tile.acts.length==1;
                s='<b>'+fmtInt(tile.acts.length)+(one?L(' activity',' activiteit'):L(' activities',' activiteiten'))+'</b>'+(one?L(' passed here',' kwam hier langs'):L(' passed here',' kwamen hier langs'))
                  +'<br>'+L('First: ','Eerste: ')+actLine(tile.acts[0])+(R.cluster.has(k)?'<br>'+L('Part of your cluster','Deel van je cluster'):'')
                  +(R.sq&&t[0]>=R.sq.x&&t[0]<R.sq.x+R.sq.n&&t[1]>=R.sq.y&&t[1]<R.sq.y+R.sq.n?'<br>'+L('Inside your max square','Binnen je grootste vierkant'):'')}
              else if(kind)s='<b>'+L('Not explored yet','Nog niet verkend')+'</b><br>'+(kind=='square'?L('One of the tiles that grow your square','Een van de vakjes die je vierkant laten groeien'):L('Would grow your cluster','Laat je cluster groeien'));
              else{hideTip();map.getContainer().style.cursor='';return}
              map.getContainer().style.cursor='pointer';showTip(s,e.originalEvent.clientX,e.originalEvent.clientY)});
            map.on('mouseout',hideTip);
            map.on('click',function(e){var t=tileAt(e);select(t[0],t[1])});

            // ---- the side panel
            var tileBox=document.getElementById('tile');
            function select(x,y,fly){sel=[x,y];draw();var k=key(x,y),t=R.set.get(k),kind=R.suggest.get(k),h;
              var where=dec(lat(y+.5).toFixed(3))+'°'+(NL?'; ':', ')+dec(lon(x+.5).toFixed(3))+'°'+(NL?'; ':', ')+fmtInt(homeDist(x+.5,y+.5))+L(' km from home',' km van thuis');
              if(!t)h='<h3>'+L('Not explored yet','Nog niet verkend')+'</h3><p class="note">'+where+'</p><p>'+(kind=='square'?L('Pass through it to help grow your max square.','Kom erdoor om je grootste vierkant te helpen groeien.')
                  :kind=='cluster'?L('Pass through it to grow your cluster.','Kom erdoor om je cluster te laten groeien.')
                  :L('No '+(F=='all'?'':LABEL[F].toLowerCase()+' ')+'track has crossed this tile.','Nog geen enkel spoor'+(F=='all'?'':' ('+LABEL[F].toLowerCase()+')')+' kwam door dit vakje.'))+'</p>';
              else{var list=t.acts.slice().reverse(),firstI=t.acts[0],shown=list.slice(0,40);
                if(shown.indexOf(firstI)<0)shown.push(firstI);
                h='<h3>'+fmtInt(t.acts.length)+(t.acts.length==1?L(' activity',' activiteit'):L(' activities',' activiteiten'))+L(' in this tile',' in dit vakje')+'</h3><p class="note">'+where+L('. First explored on ','. Voor het eerst verkend op ')
                  +fmtDate(ACTS[firstI][0])+'.</p><ol>'+shown.map(function(i){var a=ACTS[i];
                    return '<li><span>'+fmtDate(a[0])+'</span><a href="'+href(a)+'" title="'+esc(a[1])+'">'+(ICONS[a[5]]||'')+' '+esc(a[1]||LABEL[a[3]])+'</a>'
                      +(i==firstI?'<em>'+L('first','eerste')+'</em>':'')+'</li>'}).join('')+'</ol>'
                  +(list.length>shown.length?'<p class="note">'+L('And '+fmtInt(list.length-shown.length)+' more.','En nog '+fmtInt(list.length-shown.length)+'.')+'</p>':'')}
              tileBox.innerHTML=h;
              if(fly)map.flyToBounds(bounds(x-2,y-2,5),{maxZoom:14,animate:!reduced});
            }
            var filters=document.getElementById('filters');
            filters.innerHTML=['all'].concat(ORDER.filter(function(c){return present[c]&&c!='other'})).map(function(c){
              return '<button type="button" data-f="'+c+'">'+(c=='all'?L('All sports','Alle sporten'):'<i style="background:'+COLOR[c]+'"></i>'+LABEL[c])+'</button>'}).join('');
            filters.querySelectorAll('button').forEach(function(b){b.onclick=function(){setFilter(b.dataset.f,true)}});
            function setFilter(f,focusMap){F=f;R=compute(f);
              filters.querySelectorAll('button').forEach(function(b){b.setAttribute('aria-pressed',b.dataset.f==f)});
              var what=f=='all'?'':LABEL[f].toLowerCase()+' ';
              document.getElementById('big').innerHTML='<small>'+L('Explored '+what+'tiles','Verkende vakjes'+(f=='all'?'':' · '+LABEL[f].toLowerCase()))+'</small>'+fmtInt(R.set.size);
              document.getElementById('sub').textContent=R.sq?L('Your biggest fully explored square is '+R.sq.n+' by '+R.sq.n+' tiles, about '
                +Math.round(R.sq.n*KM)+' km across, '+(R.sq.d<3?'right around home':fmtInt(R.sq.d)+' km from home')+'.',
                'Je grootste volledig verkende vierkant is '+R.sq.n+' op '+R.sq.n+' vakjes, ongeveer '
                +Math.round(R.sq.n*KM)+' km breed, '+(R.sq.d<3?'vlak bij huis':fmtInt(R.sq.d)+' km van thuis')+'.'):'';
              document.getElementById('legend').innerHTML='<li><i style="background:'+(f=='all'?'linear-gradient(90deg,'+COLOR.run+' 33%,'+COLOR.ride+' 33% 66%,'+COLOR.walk+' 66%)':COLOR[f])+';opacity:.55"></i>'+L('Explored','Verkend')+'</li>'
                +'<li><i style="background:'+(f=='all'?COLOR.ride:COLOR[f])+'"></i>Cluster</li>'
                +'<li><i style="border:2px solid '+GOLD+'"></i>'+L('Max square','Grootste vierkant')+'</li>'
                +'<li><i style="border:2px dashed '+GOLD+'"></i>'+L('Grows the square','Laat het vierkant groeien')+'</li>'
                +'<li><i style="border:2px dashed '+MOON+'"></i>'+L('Grows the cluster','Laat de cluster groeien')+'</li>';
              var newNow=R.years[THIS_YEAR]||0;
              var st=[[L('Max square','Grootste vierkant'),R.sq?R.sq.n+'×'+R.sq.n:'–',R.sq?fmtInt(R.sq.n*R.sq.n)+L(' tiles',' vakjes')+(R.squares>1?', '+R.squares+L(' squares this size',' vierkanten van die grootte'):''):''],
                ['Cluster',fmtInt(R.clusterSize),L('tiles with all four neighbours explored','vakjes waarvan alle vier de buren verkend zijn')],
                [L('New this year','Nieuw dit jaar'),fmtInt(newNow),L('tiles first explored in ','vakjes voor het eerst verkend in ')+THIS_YEAR],
                [L('Furthest tile','Verste vakje'),fmtInt(furthest(R)),L('km from home','km van thuis')]];
              document.getElementById('stats').innerHTML=st.map(function(s){return '<div><dt>'+s[0]+'</dt><dd>'+s[1]+'<small>'+s[2]+'</small></dd></div>'}).join('');
              var sg='';
              if(R.grow){var n=R.grow.miss.length;
                sg+='<div class="sugg"><p>'+L('Explore <b>'+(n==1?'this one tile':'these '+n+' tiles')+'</b> to grow your square to <b>'+R.grow.n+'×'+R.grow.n+'</b>. '
                  +(n==1?'It is':'They are')+' about '+fmtInt(R.grow.d)+' km from home.',
                  'Verken <b>'+(n==1?'dit ene vakje':'deze '+n+' vakjes')+'</b> om je vierkant te laten groeien tot <b>'+R.grow.n+'×'+R.grow.n+'</b>. '
                  +(n==1?'Het ligt':'Ze liggen')+' ongeveer '+fmtInt(R.grow.d)+' km van thuis.')
                  +'</p><button type="button" id="goSquare">'+(n==1?L('Show it','Toon het'):L('Show them','Toon ze'))+'</button></div>'}
              if(R.clPicks.length&&R.clGrowTo>R.clusterSize)
                sg+='<div class="sugg cl"><p>'+L('Explore <b>these '+R.clPicks.length+' tiles</b> at the edge of your cluster to grow it from '+fmtInt(R.clusterSize)
                  +' to <b>'+fmtInt(R.clGrowTo)+'</b> tiles.','Verken <b>deze '+R.clPicks.length+' vakjes</b> aan de rand van je cluster om hem te laten groeien van '+fmtInt(R.clusterSize)
                  +' tot <b>'+fmtInt(R.clGrowTo)+'</b> vakjes.')+'</p><button type="button" id="goCluster">'+L('Show them','Toon ze')+'</button></div>';
              document.getElementById('suggest').innerHTML=sg;
              var gs=document.getElementById('goSquare'),gc=document.getElementById('goCluster');
              if(gs)gs.onclick=function(){map.flyToBounds(bounds(R.grow.x-1,R.grow.y-1,R.grow.n+2),{maxZoom:13,animate:!reduced});var p=R.grow.miss[0];select(p[0],p[1])};
              if(gc)gc.onclick=function(){var xs=R.clPicks.map(function(p){return p.x}),ys=R.clPicks.map(function(p){return p.y}),
                  x0=Math.min.apply(null,xs),y0=Math.min.apply(null,ys),n=Math.max(Math.max.apply(null,xs)-x0,Math.max.apply(null,ys)-y0)+1;
                map.flyToBounds(bounds(x0-1,y0-1,n+2),{maxZoom:13,animate:!reduced});select(R.clPicks[0].x,R.clPicks[0].y)};
              yearsChart();
              if(sel)select(sel[0],sel[1]);
              draw();
              if(focusMap&&R.sq)map.flyToBounds(bounds(R.sq.x,R.sq.y,R.sq.n).pad(.6),{maxZoom:12,animate:!reduced});
            }
            function furthest(R){var m=0;R.set.forEach(function(t){var d=homeDist(t.x+.5,t.y+.5);if(d>m)m=d});return m}

            // ---- new tiles per year: one series, bars
            function yearsChart(){
              var svg=document.getElementById('yearsChart'),ys=Object.keys(R.years).sort();if(!ys.length){svg.innerHTML='';return}
              var y0=+ys[0],y1=Math.max(+ys[ys.length-1],+THIS_YEAR),n=y1-y0+1,W=356,H=150,B=20,max=1,col=R.f=='all'?COLOR.ride:COLOR[R.f];
              for(var y=y0;y<=y1;y++)max=Math.max(max,R.years[y]||0);
              var bw=W/n,s='',cum=0,bestY=null;
              s+='<line x1="0" x2="'+W+'" y1="'+(H-B)+'" y2="'+(H-B)+'" stroke="#1d2a44"/>';
              for(var y=y0;y<=y1;y++){var v=R.years[y]||0,h=v/max*(H-B-16);cum+=v;if(!bestY||v>(R.years[bestY]||0))bestY=y;
                var tipTxt='<b>'+y+'</b><br>'+fmtInt(v)+L(' new tiles',' nieuwe vakjes')+'<br>'+fmtInt(cum)+L(' in total',' in totaal');
                s+='<rect x="'+((y-y0)*bw+1).toFixed(1)+'" y="'+(H-B-h).toFixed(1)+'" width="'+Math.max(1,bw-2).toFixed(1)+'" height="'+Math.max(v?1:0,h).toFixed(1)+'" rx="1.5" fill="'+(String(y)==THIS_YEAR?MOON:col)+'"/>'
                  +'<rect x="'+((y-y0)*bw).toFixed(1)+'" y="0" width="'+bw.toFixed(1)+'" height="'+H+'" fill="transparent" data-tip="'+esc(tipTxt)+'"/>';
                var stp=Math.ceil(n/6);if((y-y0)%stp==0&&(y==y1||y1-y>=stp*.6)||y==y1&&(y-y0)%stp>=stp*.6)s+='<text x="'+((y-y0)*bw+bw/2).toFixed(1)+'" y="'+(H-4)+'" fill="#76829b" font-size="11" text-anchor="middle">'+y+'</text>'}
              s+='<text x="0" y="10" fill="#76829b" font-size="11">'+fmtInt(max)+'</text>';
              svg.setAttribute('viewBox','0 0 '+W+' '+H);svg.innerHTML=s;
              document.getElementById('yearsNote').textContent=L('The most new ones in '+bestY+' ('+fmtInt(R.years[bestY])+'). This year is the light bar.',
                'De meeste nieuwe in '+bestY+' ('+fmtInt(R.years[bestY])+'). Dit jaar is de lichte staaf.');
            }
            var ych=document.getElementById('yearsChart');
            ych.addEventListener('mousemove',function(e){var t=e.target.getAttribute&&e.target.getAttribute('data-tip');if(t)showTip(t,e.clientX,e.clientY);else hideTip()});
            ych.addEventListener('mouseleave',hideTip);

            map.setView([lat(HY),lon(HX)],11);
            setFilter('all',false);
            if(R.sq)map.fitBounds(bounds(R.sq.x,R.sq.y,R.sq.n).pad(.6),{maxZoom:12});
            draw();
            })();
            """;
}
