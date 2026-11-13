package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Years in motion": every activity ever recorded, told as one page.
 * <ul>
 *   <li>a replay that draws every GPS track in the order it happened, like a long-exposure photo, with the camera
 *       following along on trips;</li>
 *   <li>every day since the first activity as one cell, a row per year;</li>
 *   <li>at what hour of which weekday activities start;</li>
 *   <li>resting heart rate, HRV, sleep and steps month by month.</li>
 * </ul>
 * Reads the summaries of the day reports (written by {@link DescribeGarminFile}) and the tracks of the heatmap
 * ({@code heatmap-data.js}), so run it after both. The downloaded Garmin files are not touched.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.YearsInMotion [--account NAME]
 * </pre>
 */
public final class YearsInMotion {
    static final String PAGE_FILE = "years.html";
    private static final String DATA_FILE = "years-data.js";

    private YearsInMotion() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: YearsInMotion [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Years in motion updated: " + write().toAbsolutePath());
    }

    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        StringBuilder js = new StringBuilder("window.YEARS={days:[");
        Map<String, String> icons = new TreeMap<>();
        boolean first = true;
        int activities = 0;
        for (var e : DescribeGarminFile.Overview.entries()) {
            Map<String, Object> s = e.summary();
            js.append(first ? "\n" : ",\n");
            first = false;
            // [date, steps, resting HR, HRV, sleep score, sleep seconds, [[sport, group, start, metres, seconds, name], …]]
            js.append('[').append(jsStr(e.date().toString())).append(',').append(n(s.get("steps"))).append(',')
                    .append(n(s.get("restingHR"))).append(',').append(n(s.get("hrv"))).append(',')
                    .append(n(s.get("sleepScore"))).append(',').append(n(s.get("sleepSeconds"))).append(",[");
            boolean firstAct = true;
            if (s.get("activities") instanceof List<?> acts) {
                for (Object o : acts) {
                    if (!(o instanceof Map<?, ?> a)) continue;
                    String sport = str(a.get("sport"));
                    if (DescribeGarminFile.Overview.HIDDEN_SPORTS.contains(sport)) continue;
                    Object dist = a.get("correctedDistance") instanceof Number ? a.get("correctedDistance") : a.get("distance");
                    js.append(firstAct ? "" : ",").append('[').append(jsStr(sport)).append(',')
                            .append(jsStr(DescribeGarminFile.Overview.sportGroup(sport))).append(',')
                            .append(jsStr(str(a.get("start")))).append(',').append(n(dist)).append(',')
                            .append(n(a.get("duration"))).append(',').append(jsStr(str(a.get("name")))).append(']');
                    firstAct = false;
                    icons.put(sport, DescribeGarminFile.sportIcon(sport));
                    activities++;
                }
            }
            js.append("]]");
        }
        js.append("\n],icons:{");
        boolean firstIcon = true;
        for (var i : icons.entrySet()) {
            js.append(firstIcon ? "" : ",").append(jsStr(i.getKey())).append(':').append(jsStr(i.getValue()));
            firstIcon = false;
        }
        js.append("}};\n");
        replace(dir.resolve(DATA_FILE), js.toString());
        Path page = dir.resolve(PAGE_FILE);
        replace(page, page());
        System.out.printf("Years in motion: %d activities.%n", activities);
        return page;
    }

    private static String n(Object o) {
        return o instanceof Number num ? Long.toString(Math.round(num.doubleValue())) : "0";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void replace(Path f, String content) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String page() {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#3b4fd8")
                + "<title>Years in motion · " + esc(label) + "</title>" + Site.HEAD
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>"
                + "<style>" + CSS + "</style></head><body>"
                + BODY.replace("{{NAV}}", Site.nav(PAGE_FILE, 0)).replace("{{LABEL}}", esc(label))
                .replace("{{UPDATED}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).format(LocalDateTime.now()))
                .replace("{{UPDATED_NL}}", DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.of("nl")).format(LocalDateTime.now()))
                + "<script src=\"heatmap-data.js\"></script><script src=\"" + DATA_FILE + "\"></script>"
                + "<script>" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --run:#d95926;--ride:#3987e5;--walk:#199e70;--other:#8792a8;--rest:#1a2640;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;background:var(--night);color:var(--fog);font:17px/1.55 var(--text)}
            a{color:var(--moon)}
            :focus-visible{outline:2px solid var(--moon);outline-offset:2px}
            h2{font:700 clamp(40px,6vw,64px)/.95 var(--display);color:var(--moon);margin:0 0 12px;letter-spacing:.005em}
            .lede{max-width:62ch;margin:0 0 28px}
            /* ---- replay */
            #replay{position:relative;height:calc(100vh - var(--site-h,44px));min-height:560px;overflow:hidden;background:var(--night)}
            #map{position:absolute;inset:0;background:var(--night)}
            #map .leaflet-tile-pane{filter:grayscale(1) invert(1) brightness(.34) contrast(1.3) sepia(.35) hue-rotate(185deg);opacity:.55}
            .leaflet-container{font:13px var(--text)}
            .leaflet-top.leaflet-right{top:calc(var(--top-h,64px) - 6px)}
            .leaflet-control-attribution{background:rgba(10,18,32,.6)!important;color:var(--dim)!important}
            .leaflet-control-attribution a{color:var(--fog)!important}
            .top{position:absolute;left:0;right:0;top:0;z-index:600;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;
              gap:8px 24px;padding:16px 24px 28px 28px;pointer-events:none;background:linear-gradient(var(--night) 40%,rgba(10,18,32,0))}
            .top>*{pointer-events:auto}
            .title{font:800 22px/1 var(--display);color:var(--moon);margin:0;letter-spacing:.02em}
            .title span{color:var(--dim);font-weight:500}
            .top .site-nav{color:var(--moon);flex:0 1 auto;justify-content:flex-end;min-width:0;--site-active-bg:var(--moon);--site-active-fg:var(--night)}
            .hud{position:absolute;left:28px;bottom:calc(var(--deck-h,120px) + 12px);z-index:600;pointer-events:none;max-width:calc(100% - 56px)}
            .hud a{pointer-events:auto}
            .hud::before{content:'';position:absolute;z-index:-1;inset:-140px -260px -40px -140px;pointer-events:none;
              background:radial-gradient(closest-side,rgba(10,18,32,.88),rgba(10,18,32,.6) 55%,rgba(10,18,32,0))}
            #when{font:800 clamp(64px,12vw,150px)/.82 var(--display);color:var(--moon);margin:0;letter-spacing:-.01em;
              text-shadow:0 0 40px rgba(10,18,32,.9)}
            #when small{display:block;font-weight:500;font-size:.36em;color:var(--fog);letter-spacing:.02em;margin:0 0 .25em .04em}
            .tally{display:flex;flex-wrap:wrap;gap:6px 26px;margin:18px 0 0;padding:0;list-style:none}
            .tally li{display:flex;align-items:baseline;gap:8px;font-size:15px;text-shadow:0 0 12px var(--night)}
            .tally b{font:700 30px/1 var(--display);color:var(--moon);font-variant-numeric:tabular-nums;letter-spacing:.01em}
            .tally i{width:10px;height:10px;border-radius:50%;flex:none;align-self:center}
            #ticker{display:block;min-height:1.6em;margin-top:12px;font-size:15px;color:var(--fog);text-decoration:none;
              text-shadow:0 0 12px var(--night);transition:opacity .4s}
            #ticker:hover{color:var(--moon)}
            .deck{position:absolute;left:0;right:0;bottom:0;z-index:600;padding:12px 28px 18px;
              background:linear-gradient(rgba(10,18,32,0),rgba(10,18,32,.92) 38%)}
            .controls{display:flex;flex-wrap:wrap;align-items:center;gap:10px 18px;margin-bottom:10px;font-size:14px}
            button{font:inherit;color:var(--moon);background:var(--deep);border:1px solid var(--rule);border-radius:999px;
              padding:6px 14px;cursor:pointer}
            button:hover{border-color:var(--dim)}
            button[aria-pressed=true]{background:var(--moon);color:var(--night);border-color:var(--moon)}
            #play{width:96px;font-weight:700}
            .seg{display:flex;align-items:center;gap:4px}
            .seg>span{margin-right:6px;color:var(--dim)}
            .seg button{padding:5px 11px}
            #timeline{display:block;width:100%;height:56px;cursor:pointer;touch-action:none}
            /* ---- sections */
            main{max-width:1180px;margin:0 auto;padding:0 28px}
            section.part{padding:110px 0 30px}
            .scroll{overflow-x:auto;margin:0 -4px;padding:0 4px}
            #cal{display:block;min-width:760px;width:100%}
            .keys{display:flex;flex-wrap:wrap;gap:6px 20px;margin:14px 0 0;padding:0;list-style:none;font-size:14px}
            .keys li{display:flex;align-items:center;gap:7px}
            .keys i{width:12px;height:12px;border-radius:3px}
            .facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:24px 40px;margin:44px 0 0}
            .facts div{border-top:1px solid var(--rule);padding-top:12px}
            .facts dt{font-size:14px;color:var(--dim)}
            .facts dd{margin:2px 0 0;font:700 36px/1.05 var(--display);color:var(--moon)}
            .facts dd small{display:block;font:400 14px/1.4 var(--text);color:var(--fog);margin-top:4px}
            #clock{display:block;width:100%;max-width:1000px}
            .body{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,420px),1fr));gap:40px 48px}
            .chart h3{font:700 26px/1 var(--display);color:var(--moon);margin:0}
            .chart p{margin:4px 0 10px;font-size:14px;color:var(--dim)}
            .chart svg{display:block;width:100%;height:auto;overflow:visible}
            .end{padding:140px 0 120px}
            .end p{font:700 clamp(34px,5vw,58px)/1.06 var(--display);color:var(--moon);max-width:22ch;margin:0}
            .end p+p{font:400 17px/1.55 var(--text);color:var(--fog);max-width:62ch;margin-top:24px}
            footer{border-top:1px solid var(--rule);padding:22px 0 40px;font-size:14px;color:var(--dim)}
            footer a{color:var(--fog);margin-right:16px}
            #tip{position:fixed;z-index:1000;pointer-events:none;background:var(--deep);border:1px solid var(--rule);border-radius:8px;
              padding:9px 12px;font-size:14px;line-height:1.45;color:var(--fog);max-width:320px;box-shadow:0 8px 30px rgba(0,0,0,.45)}
            #tip b{color:var(--moon)}
            @media (max-width:700px){
              .top{padding:12px 16px 22px}.top .site-nav{width:100%;justify-content:space-between}
              .hud{left:16px}.deck{padding:10px 16px 14px}main{padding:0 16px}
              .tally b{font-size:24px}.seg>span{display:none}
            }
            """;

    private static final String BODY = """
            <section id="replay" aria-label="Replay of every route" data-nl-aria="Herhaling van elke route">
              <div id="map"></div>
              <div class="top" id="top"><p class="title" data-nl="Jaren in beweging <span>{{LABEL}}</span>">Years in motion <span>{{LABEL}}</span></p>
                {{NAV}}</div>
              <div class="hud" aria-live="off">
                <p id="when"><small id="whenDay"></small><span id="whenText"></span></p>
                <ul class="tally" id="tally"></ul>
                <a id="ticker" href="#"></a>
              </div>
              <div class="deck" id="deck">
                <div class="controls">
                  <button id="play" type="button">Pause</button>
                  <div class="seg" id="speeds" role="group" aria-label="Replay speed" data-nl-aria="Afspeelsnelheid"><span data-nl="Eén seconde is">One second is</span>
                    <button type="button" data-speed="7" data-nl="een week">a week</button><button type="button" data-speed="30" data-nl="een maand">a month</button>
                    <button type="button" data-speed="91" data-nl="een seizoen">a season</button><button type="button" data-speed="365" data-nl="een jaar">a year</button></div>
                  <button id="follow" type="button" aria-pressed="true" title="Fly along to wherever the next activities are"
                    data-nl-title="Vlieg mee naar waar de volgende activiteiten zijn" data-nl="Volg mij">Follow me</button>
                  <button id="whole" type="button" title="Show every route at once" data-nl-title="Toon alle routes tegelijk" data-nl="Toon alles">Show everything</button>
                </div>
                <canvas id="timeline" aria-label="Timeline: kilometres per month. Click or drag to jump to a date."
                  data-nl-aria="Tijdlijn: kilometers per maand. Klik of sleep om naar een datum te springen."></canvas>
              </div>
            </section>
            <main>
              <section class="part" id="days">
                <h2><span data-nl="Elke dag sinds">Every day since</span> <span id="since"></span></h2>
                <p class="lede" data-nl="Eén rij per jaar, één vakje per dag, gekleurd naar wat je die dag het meest deed. Donkere vakjes zijn
                  dagen zonder training, gaten zijn dagen zonder gegevens. Wijs een dag aan om hem te zien, klik om het verslag te openen.">One row per year, one cell per day, coloured by what you did most that day. Dark cells are days the
                  watch saw no workout, gaps are days without data. Point at a day to see it, click to open its report.</p>
                <div class="scroll"><canvas id="cal"></canvas></div>
                <ul class="keys" id="calKeys"></ul>
                <dl class="facts" id="facts"></dl>
              </section>
              <section class="part" id="rhythm">
                <h2 data-nl="Wanneer je vertrekt">When you head out</h2>
                <p class="lede" id="rhythmLede"></p>
                <svg id="clock" role="img" aria-label="Start times of all activities by weekday and hour" data-nl-aria="Starttijden van alle activiteiten per weekdag en uur"></svg>
              </section>
              <section class="part" id="bodyPart">
                <h2 data-nl="Wat het met je deed">What it did to you</h2>
                <p class="lede" data-nl="De middelste waarde van elke maand voor wat het horloge mat op de dagen dat je het droeg. Wijs een grafiek aan om een maand te lezen.">The middle value of each month for what the watch measured on the days you wore it. Point at a chart to read a month.</p>
                <div class="body" id="body"></div>
              </section>
              <section class="end"><p id="endLine"></p><p id="endSub"></p></section>
              <footer><a href="index.html" data-nl="Overzicht">Overview</a><a href="heatmap.html">Heatmap</a>
                <span data-nl="Gemaakt uit je Garmin-bestanden op {{UPDATED_NL}}. De routes komen uit de GPX-bestanden; binnenactiviteiten en
                zwembadsessies tellen mee in de totalen maar hebben geen route.">Built from your Garmin files on {{UPDATED}}. Routes come from the GPX files; indoor activities and pool swims count in the
                totals but have no route.</span></footer>
            </main>
            <div id="tip" hidden></div>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var DAYS=(window.YEARS||{days:[]}).days,ICONS=(window.YEARS||{}).icons||{};
            var CLASS={running:'run',treadmill_running:'run',cycling:'ride',indoor_cycling:'ride',walking:'walk'};
            var COLOR={run:'#d95926',ride:'#3987e5',walk:'#199e70',other:'#8792a8'};
            var LABEL=LANG=='nl'?{run:'hardlopen',ride:'fietsen',walk:'wandelen',other:'overige'}:{run:'running',ride:'cycling',walk:'walking',other:'other'};
            var ORDER=['run','ride','walk','other'];
            var NL=LANG=='nl';
            var MONTHS=NL?['januari','februari','maart','april','mei','juni','juli','augustus','september','oktober','november','december']
              :['January','February','March','April','May','June','July','August','September','October','November','December'];
            var MON=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:MONTHS.map(function(m){return m.slice(0,3)});
            var WEEKDAYS=NL?['maandag','dinsdag','woensdag','donderdag','vrijdag','zaterdag','zondag']
              :['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
            if(NL)document.title=document.title.replace('Years in motion','Jaren in beweging');
            function cap(s){return s.charAt(0).toUpperCase()+s.slice(1)}
            // A number with decimals, with a decimal comma in Dutch.
            function fx(v,d){var s=v.toFixed(d);return NL?s.replace('.',','):s}
            var reduced=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
            function cls(g){return CLASS[g]||'other'}
            function dayNo(s){return Date.UTC(+s.slice(0,4),+s.slice(5,7)-1,+s.slice(8,10))/864e5}
            function dateOf(n){var d=new Date(Math.floor(n)*864e5);return d}
            function iso(n){return dateOf(n).toISOString().slice(0,10)}
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,' ')}
            function fmtDate(s){return +s.slice(8,10)+' '+MON[+s.slice(5,7)-1]+' '+s.slice(0,4)}
            function fmtDur(s){var h=Math.floor(s/3600),m=Math.round(s%3600/60);return h?h+' h '+String(m).padStart(2,'0'):m+' min'}
            function href(d){return d.slice(0,4)+'/'+d.slice(5,7)+'/'+d+'-report.html'}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function rgba(hex,a){var n=parseInt(hex.slice(1),16);return 'rgba('+(n>>16)+','+(n>>8&255)+','+(n&255)+','+a+')'}
            function hourOf(t){var m=/^(\\d+):(\\d+)/.exec(t||'');return m?(+m[1])+(+m[2])/60:12}
            var tip=document.getElementById('tip');
            function showTip(html,x,y){tip.innerHTML=html;tip.hidden=false;var w=tip.offsetWidth,h=tip.offsetHeight;
              tip.style.left=Math.max(8,Math.min(innerWidth-w-8,x+14))+'px';tip.style.top=(y+h+20>innerHeight?y-h-14:y+16)+'px'}
            function hideTip(){tip.hidden=true}

            // ---- every activity (also without GPS), for the totals, calendar and clock
            var ALL=[],byDay={};
            DAYS.forEach(function(d){
              var day={date:d[0],n:dayNo(d[0]),steps:d[1],rhr:d[2],hrv:d[3],sleepScore:d[4],sleep:d[5],acts:[]};
              d[6].forEach(function(a){var x={sport:a[0],group:a[1],c:cls(a[1]),start:a[2],m:a[3],secs:a[4],name:a[5],date:d[0]};
                x.t=day.n+hourOf(a[2])/24;day.acts.push(x);ALL.push(x)});
              byDay[d[0]]=day;
            });
            ALL.sort(function(a,b){return a.t-b.t});
            var withActs=DAYS.filter(function(d){return d[6].length});
            if(!withActs.length){document.getElementById('whenText').textContent=L('No activities yet','Nog geen activiteiten');return}
            var START=dayNo(withActs[0][0]),END=dayNo(withActs[withActs.length-1][0])+1;

            // ---- GPS tracks from the heatmap data
            function decode(s){
              var pts=[],i=0,lat=0,lon=0;
              while(i<s.length){for(var k=0;k<2;k++){var r=0,sh=0,b;do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);
                var dd=(r&1)?~(r>>1):(r>>1);if(k==0)lat+=dd;else lon+=dd}pts.push(lon/1e5,lat/1e5)}
              var xy=new Float64Array(pts.length),x0=1,y0=1,x1=0,y1=0;
              for(var j=0;j<pts.length;j+=2){var x=(pts[j]+180)/360,sn=Math.sin(pts[j+1]*Math.PI/180),y=.5-Math.log((1+sn)/(1-sn))/(4*Math.PI);
                xy[j]=x;xy[j+1]=y;if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y}
              return {xy:xy,b:[x0,y0,x1,y1]};
            }
            var TRACKS=[];
            (window.HEATMAP||[]).forEach(function(r){
              if(!r[7].length)return;
              var lines=r[7].map(decode),b=[1,1,0,0],n=0;
              lines.forEach(function(l){n+=l.xy.length/2;b=[Math.min(b[0],l.b[0]),Math.min(b[1],l.b[1]),Math.max(b[2],l.b[2]),Math.max(b[3],l.b[3])]});
              TRACKS.push({date:r[0],t:dayNo(r[0])+hourOf(r[1])/24,sport:r[2],c:cls(r[3]),name:r[4],km:r[5],id:r[8],lines:lines,b:b,n:n,live:0});
            });
            TRACKS.sort(function(a,b){return a.t-b.t});
            function ll(x,y){return [Math.atan(Math.sinh(Math.PI*(1-2*y)))*180/Math.PI,x*360-180]}
            function boundsOf(list,lo,hi){
              // The box around most start points: outliers (one trip far away) do not stretch it.
              var xs=list.map(function(a){return a.lines[0].xy[0]}).sort(function(a,b){return a-b}),
                  ys=list.map(function(a){return a.lines[0].xy[1]}).sort(function(a,b){return a-b});
              function q(v,p){return v[Math.min(v.length-1,Math.floor(p*v.length))]}
              return L.latLngBounds(ll(q(xs,lo),q(ys,hi)),ll(q(xs,hi),q(ys,lo)));
            }

            // ---- map and the two light layers: settled trails (redrawn when the map moves) and the ones being drawn now
            var map=L.map('map',{zoomControl:false,zoomAnimation:false,worldCopyJump:false,minZoom:2,maxZoom:17});
            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);
            L.control.zoom({position:'topright',zoomInTitle:L('Zoom in','Inzoomen'),zoomOutTitle:L('Zoom out','Uitzoomen')}).addTo(map);
            // The zoom buttons sit just below the top bar, however many lines the menu wraps to.
            // and the overlay text just above the controls, however many lines they wrap to.
            (function(){var top=document.getElementById('top'),deck=document.getElementById('deck'),rp=document.getElementById('replay');
              function fit(){rp.style.setProperty('--top-h',top.offsetHeight+'px');rp.style.setProperty('--deck-h',deck.offsetHeight+'px')}fit();
              if(window.ResizeObserver){var ro=new ResizeObserver(fit);ro.observe(top);ro.observe(deck)}else window.addEventListener('resize',fit)})();
            map.createPane('trails').style.zIndex=450;map.getPane('trails').style.pointerEvents='none';
            function layer(){var c=document.createElement('canvas');c.style.cssText='position:absolute;left:0;top:0';map.getPane('trails').appendChild(c);return c}
            var baseC=layer(),liveC=layer(),bctx=baseC.getContext('2d'),lctx=liveC.getContext('2d');
            function view(){var z=map.getZoom(),sz=map.getSize(),o=map.getPixelBounds().min,s=256*Math.pow(2,z);
              return {z:z,s:s,ox:o.x,oy:o.y,w:sz.x,h:sz.y,x0:o.x/s,y0:o.y/s,x1:(o.x+sz.x)/s,y1:(o.y+sz.y)/s}}
            function prep(c,ctx,v){var dpr=window.devicePixelRatio||1;
              if(c.width!=v.w*dpr||c.height!=v.h*dpr){c.width=v.w*dpr;c.height=v.h*dpr;c.style.width=v.w+'px';c.style.height=v.h+'px'}
              L.DomUtil.setPosition(c,map.containerPointToLayerPoint([0,0]));
              ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,c.width,c.height);ctx.setTransform(dpr,0,0,dpr,0,0);
              ctx.globalCompositeOperation='lighter';ctx.lineJoin='round';ctx.lineCap='round'}
            function visible(a,v){var b=a.b,m=.002;return !(b[2]<v.x0-m||b[0]>v.x1+m||b[3]<v.y0-m||b[1]>v.y1+m)}
            function lw(v){return v.z>=14?2:v.z>=11?1.4:v.z>=8?1.1:.9}
            // Strokes the first `upto` points of a track; returns the last point drawn (the head of the line).
            function trace(ctx,a,v,upto){
              var left=upto==null?a.n:upto,head=null,tiny=1/v.s;
              ctx.beginPath();
              for(var li=0;li<a.lines.length&&left>0;li++){var xy=a.lines[li].xy,k=Math.min(xy.length/2,left);left-=k;
                var px=(xy[0]*v.s-v.ox),py=(xy[1]*v.s-v.oy);ctx.moveTo(px,py);var lx=xy[0],ly=xy[1];
                for(var i=1;i<k;i++){var x=xy[2*i],y=xy[2*i+1];if(i<k-1&&Math.abs(x-lx)<tiny&&Math.abs(y-ly)<tiny)continue;
                  ctx.lineTo(x*v.s-v.ox,y*v.s-v.oy);lx=x;ly=y}
                head=[lx*v.s-v.ox,ly*v.s-v.oy]}
              ctx.stroke();return head;
            }
            var SETTLED={};ORDER.forEach(function(c){SETTLED[c]=rgba(COLOR[c],.42)});
            var T=START,ptr=0,live=[],playing=false,speed=30,follow=true,flying=false,dirty=true,last=0,nextLook=0;
            function settle(a,v){bctx.strokeStyle=SETTLED[a.c];bctx.lineWidth=lw(v);trace(bctx,a,v);a.live=0}
            function redrawBase(){var v=view();prep(baseC,bctx,v);var w=lw(v);bctx.lineWidth=w;
              for(var i=0;i<ptr;i++){var a=TRACKS[i];if(a.live||!visible(a,v))continue;bctx.strokeStyle=SETTLED[a.c];trace(bctx,a,v)}
              dirty=false}
            map.on('move zoom resize viewreset',function(){dirty=true});
            var DRAW=1500;
            function drawLive(now){var v=view();prep(liveC,lctx,v);var w=lw(v),keep=[];
              for(var i=0;i<live.length;i++){var a=live[i],age=(now-a.live)/DRAW;
                if(age>=1||live.length-i>160){settle(a,v);continue}
                keep.push(a);if(!visible(a,v))continue;
                var f=Math.min(1,age/.6),fade=age<.6?1:1-(age-.6)/.4;
                lctx.strokeStyle=rgba(COLOR[a.c],.42+.5*fade);lctx.lineWidth=w+1.2*fade;
                var head=trace(lctx,a,v,Math.max(2,Math.ceil(a.n*f)));
                if(head&&age<.75){var g=lctx.createRadialGradient(head[0],head[1],0,head[0],head[1],9);
                  g.addColorStop(0,'rgba(255,255,255,.95)');g.addColorStop(.35,rgba(COLOR[a.c],.7));g.addColorStop(1,rgba(COLOR[a.c],0));
                  lctx.fillStyle=g;lctx.beginPath();lctx.arc(head[0],head[1],9,0,7);lctx.fill()}}
              live=keep}

            // ---- what the overlay says
            var whenText=document.getElementById('whenText'),whenDay=document.getElementById('whenDay'),tally=document.getElementById('tally'),
                ticker=document.getElementById('ticker'),allPtr=0,sums={run:0,ride:0,walk:0,other:0},counted={run:0,ride:0,walk:0,other:0};
            tally.innerHTML=ORDER.map(function(c){return '<li><i style="background:'+COLOR[c]+'"></i><b id="k-'+c+'">0</b>km '+LABEL[c]+'</li>'}).join('');
            ALL.forEach(function(a){counted[a.c]+=a.m});
            ORDER.forEach(function(c){if(!counted[c])document.getElementById('k-'+c).parentNode.hidden=true});
            var shownText='',lastTicker=null;
            function hud(){
              if(T<(allPtr?ALL[allPtr-1].t:-1)){allPtr=0;ORDER.forEach(function(c){sums[c]=0})}
              while(allPtr<ALL.length&&ALL[allPtr].t<=T){var a=ALL[allPtr++];sums[a.c]+=a.m}
              ORDER.forEach(function(c){document.getElementById('k-'+c).textContent=fmtInt(sums[c]/1000)});
              var d=dateOf(Math.min(T,END-1)),txt=cap(MON[d.getUTCMonth()])+' '+d.getUTCFullYear();
              if(txt!=shownText){whenText.textContent=txt;shownText=txt}
              whenDay.textContent=cap(WEEKDAYS[(d.getUTCDay()+6)%7])+' '+d.getUTCDate();
              var a=ptr?TRACKS[ptr-1]:null;
              if(a!==lastTicker){lastTicker=a;
                ticker.textContent=a?(ICONS[a.sport]||'')+' '+(a.name||LABEL[a.c])+', '+fx(a.km,1)+L(' km on ',' km op ')+fmtDate(a.date):'';
                ticker.href=a?href(a.date)+(a.id?'#a'+a.id:''):'#'}
            }

            // ---- the camera: fly to where the next activities are when they are not in view
            function lookAhead(now){
              if(!follow||!playing||flying||now<nextLook)return;nextLook=now+350;
              var v=view(),up=[];
              for(var i=ptr;i<TRACKS.length&&TRACKS[i].t<=T+Math.max(6,speed*2);i++)up.push(TRACKS[i]);
              if(!up.length)return;
              var inside=0,b=[1,1,0,0];
              up.forEach(function(a){var x=a.lines[0].xy[0],y=a.lines[0].xy[1];if(x>v.x0&&x<v.x1&&y>v.y0&&y<v.y1)inside++;
                b=[Math.min(b[0],a.b[0]),Math.min(b[1],a.b[1]),Math.max(b[2],a.b[2]),Math.max(b[3],a.b[3])]});
              // Mostly out of view, or the view is far wider than where things happen: move.
              var tooWide=v.z<8&&(b[2]-b[0])<(v.x1-v.x0)/25&&(b[3]-b[1])<(v.y1-v.y0)/25;
              if(inside/up.length>=.5&&!tooWide)return;
              var target=up.length>3?boundsOf(up,.1,.9):L.latLngBounds(ll(b[0],b[3]),ll(b[2],b[1]));
              flying=true;map.flyToBounds(target.pad(.35),{maxZoom:12,duration:inside?1.2:2.2,easeLinearity:.2});
            }
            map.on('moveend',function(){if(flying){flying=false;dirty=true}});
            var mapEl=document.getElementById('map');
            ['pointerdown','wheel','touchstart'].forEach(function(ev){mapEl.addEventListener(ev,function(){if(follow)setFollow(false)},{passive:true})});

            // ---- timeline: kilometres per month, the part already played lit
            var tl=document.getElementById('timeline'),tctx=tl.getContext('2d'),M0=new Date(START*864e5),months=[],maxKm=1;
            (function(){var y=M0.getUTCFullYear(),m=M0.getUTCMonth(),e=dateOf(END);
              while(y<e.getUTCFullYear()||(y==e.getUTCFullYear()&&m<=e.getUTCMonth())){months.push({y:y,m:m,s:Date.UTC(y,m,1)/864e5,e:Date.UTC(y,m+1,1)/864e5,km:{run:0,ride:0,walk:0,other:0}});
                if(++m>11){m=0;y++}}
              ALL.forEach(function(a){var d=dateOf(a.t),i=(d.getUTCFullYear()-months[0].y)*12+d.getUTCMonth()-months[0].m;if(months[i])months[i].km[a.c]+=a.m/1000});
              months.forEach(function(mo){var s=0;ORDER.forEach(function(c){s+=mo.km[c]});mo.total=s;if(s>maxKm)maxKm=s})})();
            var t0=months[0].s,t1=months[months.length-1].e;
            function drawTimeline(){var dpr=window.devicePixelRatio||1,w=tl.clientWidth,h=tl.clientHeight;
              if(tl.width!=w*dpr||tl.height!=h*dpr){tl.width=w*dpr;tl.height=h*dpr}
              tctx.setTransform(dpr,0,0,dpr,0,0);tctx.clearRect(0,0,w,h);
              var top=4,bh=h-20,bw=w/months.length,px=(T-t0)/(t1-t0)*w;
              months.forEach(function(mo,i){var x=i*bw,yb=top+bh,played=mo.s<T;
                ORDER.forEach(function(c){var hh=mo.km[c]/maxKm*bh;if(hh<=0)return;tctx.fillStyle=played?COLOR[c]:rgba(COLOR[c],.28);
                  tctx.fillRect(x+(bw>3?.5:0),yb-hh,Math.max(1,bw-(bw>3?1:0)),hh);yb-=hh});
                if(mo.m==0){tctx.fillStyle='#76829b';tctx.font='12px Atkinson Hyperlegible, sans-serif';
                  if(bw*12>34||mo.y%5==0)tctx.fillText(mo.y,x+2,h-3);tctx.fillStyle='#1d2a44';tctx.fillRect(x,top,1,bh)}});
              tctx.fillStyle='#eef1f7';tctx.fillRect(Math.round(px)-1,0,2,h-16)}
            function seekTo(t){T=Math.max(START,Math.min(END,t));live.forEach(function(a){a.live=0});live=[];
              ptr=0;while(ptr<TRACKS.length&&TRACKS[ptr].t<=T)ptr++;dirty=true;nextLook=0}
            var scrubbing=false;
            function scrub(e){var r=tl.getBoundingClientRect();seekTo(t0+(e.clientX-r.left)/r.width*(t1-t0))}
            tl.addEventListener('pointerdown',function(e){scrubbing=true;tl.setPointerCapture(e.pointerId);scrub(e)});
            tl.addEventListener('pointermove',function(e){var r=tl.getBoundingClientRect(),t=t0+(e.clientX-r.left)/r.width*(t1-t0);
              if(scrubbing)scrub(e);var i=Math.floor((e.clientX-r.left)/r.width*months.length),mo=months[i];if(!mo)return;
              showTip('<b>'+cap(MONTHS[mo.m])+' '+mo.y+'</b><br>'+ORDER.filter(function(c){return mo.km[c]>=.5}).map(function(c){
                return fmtInt(mo.km[c])+' km '+LABEL[c]}).join('<br>')||'<b>'+cap(MONTHS[mo.m])+' '+mo.y+'</b><br>'+L('Nothing recorded','Niets geregistreerd'),e.clientX,e.clientY-60)});
            tl.addEventListener('pointerup',function(){scrubbing=false});
            tl.addEventListener('pointerleave',hideTip);

            // ---- controls
            var playBtn=document.getElementById('play'),followBtn=document.getElementById('follow');
            function setPlaying(p){playing=p;playBtn.textContent=p?L('Pause','Pauze'):(T>=END?L('Replay','Opnieuw'):L('Play','Afspelen'));if(p&&T>=END)seekTo(START)}
            function setFollow(f){follow=f;followBtn.setAttribute('aria-pressed',f);nextLook=0}
            function setSpeed(s){speed=s;document.querySelectorAll('#speeds button').forEach(function(b){b.setAttribute('aria-pressed',+b.dataset.speed==s)})}
            playBtn.onclick=function(){setPlaying(!playing)};
            followBtn.onclick=function(){setFollow(!follow)};
            document.querySelectorAll('#speeds button').forEach(function(b){b.onclick=function(){setSpeed(+b.dataset.speed)}});
            document.getElementById('whole').onclick=function(){setPlaying(false);seekTo(END);setFollow(false);map.flyToBounds(boundsOf(TRACKS,0,1).pad(.05),{duration:1.5})};
            document.addEventListener('keydown',function(e){
              if(e.target.closest&&e.target.closest('button,input,a'))return;
              if(e.key==' '){e.preventDefault();setPlaying(!playing)}
              else if(e.key=='ArrowRight')seekTo(T+speed*3);else if(e.key=='ArrowLeft')seekTo(T-speed*3)});
            window.addEventListener('resize',function(){dirty=true});

            function frame(now){
              var dt=Math.min(100,now-(last||now));last=now;
              if(playing&&!flying){T+=dt/1000*speed;if(T>=END){T=END;setPlaying(false)}}
              while(ptr<TRACKS.length&&TRACKS[ptr].t<=T){var a=TRACKS[ptr++];a.live=now||1;live.push(a)}
              if(dirty)redrawBase();
              drawLive(now);hud();drawTimeline();lookAhead(now);
              requestAnimationFrame(frame);
            }
            setSpeed(reduced?365:30);
            var home=TRACKS.length?boundsOf(TRACKS,.15,.85):L.latLngBounds([50.7,4.2],[51,4.5]);
            map.fitBounds(home.pad(.2),{maxZoom:12});
            if(TRACKS.length){var first=TRACKS[0];map.setView(ll((first.b[0]+first.b[2])/2,(first.b[1]+first.b[3])/2),11)}
            if(reduced){seekTo(END);setFollow(false);map.fitBounds(home.pad(.2),{maxZoom:12});setPlaying(false)}
            else{setPlaying(false);setTimeout(function(){if(!jumped)setPlaying(true)},700)}
            // #at=2016-05-01 opens the replay paused at that day, with the camera on what happened around it.
            var jumped=false,at=/at=(\\d{4}-\\d{2}-\\d{2}|end)/.exec(location.hash);
            if(at){jumped=true;setFollow(false);seekTo(at[1]=='end'?END:dayNo(at[1])+1);
              if(at[1]=='end')map.fitBounds(home.pad(.2),{maxZoom:12});
              else{var near=TRACKS.filter(function(a){return Math.abs(a.t-T)<20});if(near.length)map.fitBounds(boundsOf(near,0,1).pad(.2),{maxZoom:13})}}
            requestAnimationFrame(frame);

            // ---- every day: a row per year, a cell per day
            var cal=document.getElementById('cal'),cx=cal.getContext('2d'),Y0=dateOf(START).getUTCFullYear(),Y1=dateOf(END-1).getUTCFullYear();
            document.getElementById('since').textContent=Y0;
            var ROW=17,GAP=7,LEFT=46,RIGHT=86,TOPM=22;
            function dominant(day){var s={};day.acts.forEach(function(a){s[a.c]=(s[a.c]||0)+a.secs+1});
              var best=null;Object.keys(s).forEach(function(c){if(!best||s[c]>s[best])best=c});return best}
            var yearKm={};DAYS.forEach(function(d){var y=d[0].slice(0,4);d[6].forEach(function(a){if(cls(a[1])!='other')yearKm[y]=(yearKm[y]||0)+a[3]/1000})});
            function calGeom(){var w=cal.clientWidth;return {w:w,cw:(w-LEFT-RIGHT)/366}}
            function drawCal(){var dpr=window.devicePixelRatio||1,g=calGeom(),rows=Y1-Y0+1,h=TOPM+rows*(ROW+GAP);
              cal.style.height=h+'px';cal.width=g.w*dpr;cal.height=h*dpr;cx.setTransform(dpr,0,0,dpr,0,0);
              cx.font='12px Atkinson Hyperlegible, sans-serif';cx.fillStyle='#76829b';
              [0,31,59,90,120,151,181,212,243,273,304,334].forEach(function(o,i){cx.fillText(cap(MON[i]),LEFT+o*g.cw,13)});
              for(var y=Y0;y<=Y1;y++){var top=TOPM+(Y1-y)*(ROW+GAP),n0=Date.UTC(y,0,1)/864e5,len=(Date.UTC(y+1,0,1)/864e5)-n0;
                cx.fillStyle='#a9b4ca';cx.font='700 15px Big Shoulders Display, sans-serif';cx.fillText(y,0,top+ROW-3);
                cx.textAlign='right';cx.fillStyle='#eef1f7';cx.fillText(yearKm[y]?fmtInt(yearKm[y])+' km':'',g.w,top+ROW-3);cx.textAlign='left';
                for(var i=0;i<len;i++){var day=byDay[iso(n0+i)];if(!day)continue;var c=dominant(day);
                  cx.fillStyle=c?COLOR[c]:'#1a2640';var x=LEFT+i*g.cw;cx.fillRect(x,top,Math.max(1,g.cw-(g.cw>2.5?.6:0)),ROW)}}}
            drawCal();window.addEventListener('resize',drawCal);
            function calDay(e){var r=cal.getBoundingClientRect(),g=calGeom(),x=e.clientX-r.left,y=e.clientY-r.top-TOPM,
                row=Math.floor(y/(ROW+GAP)),i=Math.floor((x-LEFT)/g.cw);if(y<0||y%(ROW+GAP)>ROW||i<0||i>365)return null;
              var yr=Y1-row;if(yr<Y0)return null;var n=Date.UTC(yr,0,1)/864e5+i;if(dateOf(n).getUTCFullYear()!=yr)return null;return {date:iso(n),day:byDay[iso(n)]}}
            cal.addEventListener('mousemove',function(e){var h=calDay(e);if(!h){hideTip();cal.style.cursor='default';return}
              cal.style.cursor=h.day?'pointer':'default';var d=h.day,s='<b>'+cap(WEEKDAYS[(dateOf(dayNo(h.date)).getUTCDay()+6)%7])+' '+fmtDate(h.date)+'</b>';
              if(!d)s+='<br>'+L('No data','Geen gegevens');
              else{s+=d.acts.length?d.acts.map(function(a){return '<br>'+(ICONS[a.sport]||'')+' '+esc(a.name||a.sport)+(a.m>0?', '+fx(a.m/1000,1)+' km':'')+', '+fmtDur(a.secs)}).join(''):'<br>'+L('No workout','Geen training');
                var extra=[];if(d.steps)extra.push(fmtInt(d.steps)+L(' steps',' stappen'));if(d.sleep)extra.push(fx(d.sleep/3600,1)+L(' h sleep',' u slaap'));
                if(extra.length)s+='<br><span style="color:#76829b">'+extra.join(', ')+'</span>'}
              showTip(s,e.clientX,e.clientY)});
            cal.addEventListener('mouseleave',hideTip);
            cal.addEventListener('click',function(e){var h=calDay(e);if(h&&h.day)location.href=href(h.date)});
            document.getElementById('calKeys').innerHTML=ORDER.filter(function(c){return counted[c]||ALL.some(function(a){return a.c==c})}).map(function(c){
              return '<li><i style="background:'+COLOR[c]+'"></i>'+cap(LABEL[c])+'</li>'}).join('')
              +'<li><i style="background:#1a2640"></i>'+L('No workout','Geen training')+'</li><li><i style="background:transparent;border:1px solid #1d2a44"></i>'+L('No data','Geen gegevens')+'</li>';

            // ---- facts: streak, best year, longest day out, furthest from home
            (function(){
              var best={len:0},cur=0,curStart=null,prev=null;
              withActs.forEach(function(d){var n=dayNo(d[0]);if(prev!=null&&n==prev+1)cur++;else{cur=1;curStart=d[0]}
                if(cur>best.len)best={len:cur,from:curStart,to:d[0]};prev=n});
              var years=Object.keys(yearKm).sort(function(a,b){return yearKm[b]-yearKm[a]});
              // Furthest in one go: skip entries that cannot be real (a watch left running, a distance typed in by hand).
              var longest=ALL.filter(function(a){return a.c!='other'&&a.secs>=600&&a.secs<20*3600&&a.m/a.secs<22})
                .reduce(function(m,a){return !m||a.m>m.m?a:m},null);
              var far=null;
              if(TRACKS.length){var hc=boundsOf(TRACKS,.5,.5).getCenter();
                TRACKS.forEach(function(a){var p=ll(a.lines[0].xy[0],a.lines[0].xy[1]),dkm=map.distance(hc,p)/1000;if(!far||dkm>far.km)far={km:dkm,a:a}})}
              var f=[[L('Longest streak','Langste reeks'),best.len+L(' days',' dagen'),fmtDate(best.from)+L(' to ',' tot ')+fmtDate(best.to)+L(', a workout every day',', elke dag een training')],
                [L('Biggest year','Grootste jaar'),years[0],fmtInt(yearKm[years[0]])+L(' km running, riding and walking',' km lopen, fietsen en wandelen')],
                ];
              if(longest)f.push([L('Furthest in one go','Verst in één keer'),fmtInt(longest.m/1000)+' km',(ICONS[longest.sport]||'')+' '+esc(longest.name||longest.sport)+', '
                +fmtDate(longest.date)+', '+fmtDur(longest.secs)]);
              if(far)f.push([L('Furthest from home','Verst van huis'),fmtInt(far.km)+' km',(ICONS[far.a.sport]||'')+' '+esc(far.a.name||far.a.sport)+', '+fmtDate(far.a.date)]);
              document.getElementById('facts').innerHTML=f.map(function(x){return '<div><dt>'+x[0]+'</dt><dd>'+x[1]+'<small>'+x[2]+'</small></dd></div>'}).join('');
            })();

            // ---- the week: start times by weekday and hour
            (function(){
              var grid=[],max=1,svg=document.getElementById('clock'),W=1000,CW=(W-110)/24,RH=46,H=7*RH+36;
              for(var i=0;i<7;i++){grid.push([]);for(var j=0;j<24;j++)grid[i].push({n:0,c:{}})}
              ALL.forEach(function(a){var wd=(dateOf(a.t).getUTCDay()+6)%7,h=Math.floor(hourOf(a.start)),g=grid[wd][h];g.n++;g.c[a.c]=(g.c[a.c]||0)+1;if(g.n>max)max=g.n});
              var s='',top={n:0};
              for(var h=0;h<24;h+=3)s+='<text x="'+(110+h*CW+CW/2)+'" y="14" fill="#76829b" font-size="12" text-anchor="middle">'+h+':00</text>';
              grid.forEach(function(row,i){var y=36+i*RH+RH/2;
                s+='<text x="0" y="'+(y+5)+'" fill="#a9b4ca" font-size="15">'+cap(WEEKDAYS[i])+'</text>';
                row.forEach(function(g,j){var x=110+j*CW+CW/2;
                  s+='<circle cx="'+x+'" cy="'+y+'" r="1.5" fill="#1d2a44"/>';
                  if(!g.n)return;var best=Object.keys(g.c).sort(function(a,b){return g.c[b]-g.c[a]})[0],r=Math.max(3,Math.sqrt(g.n/max)*(RH/2-3));
                  if(g.n>top.n)top={n:g.n,wd:i,h:j,c:best};
                  var tipTxt='<b>'+cap(WEEKDAYS[i])+', '+j+L(':00 to ',':00 tot ')+(j+1)+':00</b><br>'+g.n+(g.n==1?L(' activity started',' activiteit gestart'):L(' activities started',' activiteiten gestart'))+'<br>'
                    +ORDER.filter(function(c){return g.c[c]}).map(function(c){return g.c[c]+' '+LABEL[c]}).join(', ');
                  s+='<circle cx="'+x+'" cy="'+y+'" r="'+r.toFixed(1)+'" fill="'+COLOR[best]+'" stroke="#0a1220" stroke-width="2" data-tip="'+esc(tipTxt)+'"/>'})});
              svg.setAttribute('viewBox','0 0 '+W+' '+H);svg.innerHTML=s;
              svg.addEventListener('mousemove',function(e){var t=e.target.getAttribute('data-tip');if(t)showTip(t,e.clientX,e.clientY);else hideTip()});
              svg.addEventListener('mouseleave',hideTip);
              var total=ALL.length,weekend=0,morning=0;ALL.forEach(function(a){var wd=(dateOf(a.t).getUTCDay()+6)%7,h=hourOf(a.start);if(wd>=5)weekend++;if(h<12)morning++});
              document.getElementById('rhythmLede').textContent=NL?'Het begin van alle '+fmtInt(total)+' activiteiten, per weekdag en uur. Je vaakste moment is '
                +WEEKDAYS[top.wd]+' om '+top.h+':00, meestal '+LABEL[top.c]+'. '+Math.round(weekend/total*100)+'% begint in het weekend en '
                +Math.round(morning/total*100)+'% voor de middag.'
                :'The start of all '+fmtInt(total)+' activities, by weekday and hour. Your most common moment is '
                +WEEKDAYS[top.wd]+' at '+top.h+':00, mostly '+LABEL[top.c]+'. '+Math.round(weekend/total*100)+'% start on a weekend and '
                +Math.round(morning/total*100)+'% before noon.';
            })();

            // ---- the body, month by month
            (function(){
              // Garmin's resting heart rate is only real when the watch was worn at night: without sleep data it comes from the day.
              function night(d){return d.sleep>=3*3600&&d.sleep<=14*3600}
              var specs=[['rhr',L('Resting heart rate','Rusthartslag'),L('beats per minute, on nights the watch was worn','slagen per minuut, in nachten dat je het horloge droeg'),function(d){return night(d)?d.rhr:0},0],
                ['hrv',L('Heart rate variability','Hartslagvariabiliteit'),L('milliseconds, overnight','milliseconden, ’s nachts'),function(d){return d.hrv},0],
                ['sleep',L('Sleep','Slaap'),L('hours per night','uren per nacht'),function(d){return night(d)?d.sleep/3600:0},1],
                ['steps',L('Steps','Stappen'),L('per day, on days the watch was worn','per dag, op dagen dat je het horloge droeg'),function(d){return d.steps>=1000?d.steps:0},0]];
              var host=document.getElementById('body');
              specs.forEach(function(sp){
                var mon={};DAYS.forEach(function(r){var d=byDay[r[0]],v=sp[3](d);if(!v)return;var k=r[0].slice(0,7);(mon[k]=mon[k]||[]).push(v)});
                var keys=Object.keys(mon).sort().filter(function(k){return mon[k].length>=5});if(keys.length<8)return;  // too few months to show a trend
                // The median of the month, so a few odd days do not make a peak.
                var pts=keys.map(function(k){var a=mon[k].slice().sort(function(x,y){return x-y}),m=a.length>>1;
                  return {k:k,v:a.length%2?a[m]:(a[m-1]+a[m])/2}});
                var W=520,H=200,L0=40,B=24,min=Infinity,max=-Infinity;pts.forEach(function(p){if(p.v<min)min=p.v;if(p.v>max)max=p.v});
                var pad=(max-min)*.12||1;min-=pad;max+=pad;
                var k0=+keys[0].slice(0,4)*12+ +keys[0].slice(5,7)-1,k1=+keys[keys.length-1].slice(0,4)*12+ +keys[keys.length-1].slice(5,7)-1;
                function X(k){var n=+k.slice(0,4)*12+ +k.slice(5,7)-1;return L0+(n-k0)/Math.max(1,k1-k0)*(W-L0)}
                function Y(v){return 8+(1-(v-min)/(max-min))*(H-B-8)}
                function fmt(v){return sp[4]?fx(v,1):fmtInt(v)}
                var path='',prevN=null;
                pts.forEach(function(p){var n=+p.k.slice(0,4)*12+ +p.k.slice(5,7);path+=(prevN!=null&&n-prevN<=2?'L':'M')+X(p.k).toFixed(1)+' '+Y(p.v).toFixed(1);prevN=n});
                var s='';[0,.5,1].forEach(function(f){var v=min+pad+(max-min-2*pad)*f,y=Y(v);
                  s+='<line x1="'+L0+'" x2="'+W+'" y1="'+y+'" y2="'+y+'" stroke="#1d2a44"/><text x="'+(L0-8)+'" y="'+(y+4)+'" fill="#76829b" font-size="12" text-anchor="end">'+fmt(v)+'</text>'});
                var ys={};pts.forEach(function(p){var y=p.k.slice(0,4);if(!ys[y])ys[y]=p.k});
                var yk=Object.keys(ys),step=Math.ceil(yk.length/6);
                yk.forEach(function(y,i){if(i%step)return;s+='<text x="'+X(y+'-01').toFixed(1)+'" y="'+(H-4)+'" fill="#76829b" font-size="12" text-anchor="middle">'+y+'</text>'});
                s+='<path d="'+path+'" fill="none" stroke="#eef1f7" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>';
                s+='<line class="cross" y1="8" y2="'+(H-B)+'" stroke="#76829b" visibility="hidden"/><circle class="dot" r="4" fill="#eef1f7" stroke="#0a1220" stroke-width="2" visibility="hidden"/>';
                s+='<rect x="'+L0+'" y="0" width="'+(W-L0)+'" height="'+(H-B)+'" fill="transparent"/>';
                var firstYear=pts.filter(function(p){return p.k.slice(0,4)==pts[0].k.slice(0,4)}),lastYear=pts.slice(-12);
                function avg(a){return a.reduce(function(s,p){return s+p.v},0)/a.length}
                var div=document.createElement('div');div.className='chart';
                div.innerHTML='<h3>'+sp[1]+'</h3><p>'+sp[2]+'. '+pts[0].k.slice(0,4)+': '+fmt(avg(firstYear))+L(', last 12 months: ',', laatste 12 maanden: ')+fmt(avg(lastYear))+'</p>'
                  +'<svg viewBox="0 0 '+W+' '+H+'" role="img" aria-label="'+sp[1]+L(' per month',' per maand')+'">'+s+'</svg>';
                host.appendChild(div);
                var svg=div.querySelector('svg'),cross=svg.querySelector('.cross'),dot=svg.querySelector('.dot');
                svg.addEventListener('mousemove',function(e){var r=svg.getBoundingClientRect(),x=(e.clientX-r.left)/r.width*W,best=null;
                  pts.forEach(function(p){if(!best||Math.abs(X(p.k)-x)<Math.abs(X(best.k)-x))best=p});if(!best)return;
                  cross.setAttribute('x1',X(best.k));cross.setAttribute('x2',X(best.k));cross.setAttribute('visibility','visible');
                  dot.setAttribute('cx',X(best.k));dot.setAttribute('cy',Y(best.v));dot.setAttribute('visibility','visible');
                  showTip('<b>'+cap(MONTHS[+best.k.slice(5,7)-1])+' '+best.k.slice(0,4)+'</b><br>'+fmt(best.v)+' '+sp[2].split(',')[0],e.clientX,e.clientY)});
                svg.addEventListener('mouseleave',function(){cross.setAttribute('visibility','hidden');dot.setAttribute('visibility','hidden');hideTip()});
              });
            })();

            // ---- the end: all of it, laid end to end
            (function(){
              var km=(counted.run+counted.ride+counted.walk)/1000,earth=km/40075,moon=km/384400*100;
              var hours=ALL.reduce(function(s,a){return s+a.secs},0)/3600;
              var moonPct=moon<1?fx(moon,1):Math.round(moon);
              document.getElementById('endLine').textContent=NL
                ?fmtInt(km)+' km. Dat is '+(earth>=1?fx(earth,1)+' keer rond de aarde':Math.round(earth*100)+'% van de weg rond de aarde')+'.'
                :fmtInt(km)+' km. That is '+(earth>=1?earth.toFixed(1)+' times around the Earth':Math.round(earth*100)+'% of the way around the Earth')+'.';
              document.getElementById('endSub').textContent=NL
                ?'Lopen, fietsen en wandelen sinds '+fmtDate(withActs[0][0])+', in '+fmtInt(ALL.length)+' activiteiten en '
                  +fmtInt(hours)+' uur, of '+(hours/24).toFixed(0)+' dagen in beweging. Ga zo door en je bereikt de maan bij '+fmtInt(384400)+' km; je bent al '
                  +moonPct+'% op weg.'
                :'Running, riding and walking since '+fmtDate(withActs[0][0])+', in '+fmtInt(ALL.length)+' activities and '
                  +fmtInt(hours)+' hours, or '+(hours/24).toFixed(0)+' days on the move. Keep going and you reach the Moon at '+fmtInt(384400)+' km; you are '
                  +moonPct+'% of the way there.';
            })();
            })();
            """;
}
