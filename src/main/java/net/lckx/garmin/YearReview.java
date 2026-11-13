package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;

/**
 * "Year in review": one year told as a story of full-screen cards, to step through with the family.
 * <p>
 * Total distance and hours against the year before, the sport split, the biggest week, the longest activity, the place
 * visited most and the places that were new, the earliest start and latest finish, the longest streak, the biggest
 * month, every route of the year drawn as glowing lines, steps and sleep, and a closing card.
 * <p>
 * The page is plain HTML and JavaScript: it loads {@code years-data.js} (written by {@link YearsInMotion}) and
 * {@code heatmap-data.js} (written by {@link Heatmap}) next to it and works everything out in the browser, so run it
 * after both. {@code year-review.html#2025} opens 2025, {@code #2025/3} its third card.
 * <p>
 * The story starts paused: Play lets it run, about ten seconds a card and longer for cards with more to read. Holding a
 * card (or resting the mouse on its words) pauses it, Space plays and pauses. English and Dutch, see {@link Site}.
 * <pre>
 * java --enable-preview -cp target/classes net.lckx.garmin.YearReview [--account NAME]
 * </pre>
 */
public final class YearReview {
    static final String PAGE_FILE = "year-review.html";
    private static final String YEARS_DATA = "years-data.js";
    private static final String HEATMAP_DATA = "heatmap-data.js";

    private YearReview() {}

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--account")) DescribeGarminFile.useAccount(args[++i]);
            else {
                System.err.println("Usage: YearReview [--account NAME]");
                System.exit(1);
            }
        }
        System.out.println("Year in review updated: " + write().toAbsolutePath());
    }

    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        Files.createDirectories(dir);
        Path page = dir.resolve(PAGE_FILE);
        Path tmp = page.resolveSibling(PAGE_FILE + ".tmp");
        Files.writeString(tmp, page(), StandardCharsets.UTF_8);
        Files.move(tmp, page, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Path data = dir.resolve(YEARS_DATA);
        if (Files.exists(data)) {
            TreeSet<String> years = new TreeSet<>();
            Matcher m = Pattern.compile("(?m)^\\[\"(\\d{4})-\\d\\d-\\d\\d\",.*\\[\\[").matcher(Files.readString(data));
            while (m.find()) years.add(m.group(1));
            System.out.println(years.isEmpty() ? "Year in review: no activities yet."
                    : "Year in review: " + years.size() + " years, " + years.first() + " to " + years.last() + ".");
        } else {
            System.out.println("Year in review: " + YEARS_DATA + " is missing, run YearsInMotion first.");
        }
        if (!Files.exists(dir.resolve(HEATMAP_DATA))) System.out.println("Year in review: " + HEATMAP_DATA + " is missing, the route cards stay empty.");
        return page;
    }

    private static String page() {
        String label = DescribeGarminFile.accountLabelOf(DescribeGarminFile.account());
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" + DescribeGarminFile.favicon("#d95926")
                + "<title>Year in review · " + esc(label) + "</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700"
                + "&family=Big+Shoulders+Display:wght@500;700;800&display=swap\">"
                + Site.HEAD
                + "<style>" + CSS + "</style></head><body>"
                + BODY.replace("{{LABEL}}", esc(label)).replace("{{NAV}}", Site.nav(PAGE_FILE, 0))
                + "<script src=\"" + HEATMAP_DATA + "\"></script><script src=\"" + YEARS_DATA + "\"></script>"
                + "<script>window.WHO=" + jsStr(label) + ";</script>"
                + "<script>" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            :root{--night:#0a1220;--deep:#0f1a2e;--rule:#1d2a44;--fog:#a9b4ca;--moon:#eef1f7;--dim:#76829b;
              --run:#d95926;--ride:#3987e5;--walk:#199e70;--other:#8792a8;
              --display:'Big Shoulders Display',Impact,'Arial Narrow',sans-serif;--text:'Atkinson Hyperlegible',system-ui,sans-serif;color-scheme:dark}
            *{box-sizing:border-box}
            html,body{margin:0;height:100%;background:var(--night);color:var(--fog);font:18px/1.5 var(--text);overflow:hidden}
            a{color:var(--moon)}
            :focus-visible{outline:3px solid var(--moon);outline-offset:3px;border-radius:6px}
            .sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
            /* ---- chrome */
            #menu,#navbox{display:none!important}
            .bar{position:fixed;left:0;right:0;top:var(--site-h,44px);z-index:5;padding:12px clamp(16px,3vw,32px) 22px;
              background:linear-gradient(rgba(10,18,32,.55),rgba(10,18,32,0));pointer-events:none}
            .bar>*{pointer-events:auto}
            .segs{display:flex;gap:4px}
            .seg{flex:1;height:4px;border-radius:2px;background:rgba(238,241,247,.22);overflow:hidden;cursor:pointer;padding:0;border:0}
            .seg i{display:block;height:100%;width:0;background:var(--moon);border-radius:2px}
            .seg.done i{width:100%}
            .seg.now{background:rgba(238,241,247,.38)}
            .row{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px}
            .row .left,.row .right{display:flex;align-items:center;gap:14px}
            .row .left{position:relative;min-width:0}
            .row .right{flex:none}
            select,button{font:inherit;font-size:15px;color:var(--moon);background:rgba(15,26,46,.7);border:1px solid rgba(238,241,247,.2);
              border-radius:999px;padding:6px 14px;cursor:pointer}
            select{padding-right:10px}
            button:hover,select:hover{border-color:rgba(238,241,247,.5)}
            #play{min-width:6.5em}
            .close{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:50%;
              color:var(--moon);text-decoration:none;font-size:24px;line-height:1;background:rgba(15,26,46,.7);border:1px solid rgba(238,241,247,.2)}
            /* the site menu, inline on wide screens, behind "Menu" on narrower ones */
            .bar .site-nav{color:var(--moon);font-size:13px;flex-wrap:nowrap;gap:10px;--site-active-bg:rgba(238,241,247,.16)}
            .bar .site-pages{flex-wrap:nowrap}
            .bar .site-nav a{padding:5px 8px}
            .bar .site-seg a{padding:3px 8px}
            #menu{display:none;align-items:center;gap:8px}
            #menu svg{width:16px;height:16px}
            @media (max-width:1359px){
              #menu{display:inline-flex}
              #navbox{display:none;position:absolute;top:calc(100% + 8px);left:0;z-index:7;min-width:250px;max-width:calc(100vw - 32px);
                max-height:calc(100vh - 90px);overflow:auto;padding:10px;border-radius:16px;background:rgba(15,26,46,.97);
                border:1px solid rgba(238,241,247,.18);box-shadow:0 18px 50px rgba(0,0,0,.5)}
              #navbox.open{display:block}
              #navbox .site-nav{flex-direction:column;align-items:stretch;gap:10px;font-size:15px}
              #navbox .site-pages{flex-direction:column;align-items:stretch;overflow:visible;max-width:none}
              #navbox .site-pages a{padding:8px 12px}
              #navbox .site-switch{justify-content:space-between;gap:8px;padding:10px 4px 2px;border-top:1px solid rgba(238,241,247,.14)}
              #navbox .site-seg a{padding:5px 12px}
            }
            .foot{position:fixed;left:50%;bottom:max(18px,env(safe-area-inset-bottom));transform:translateX(-50%);z-index:5;
              display:flex;align-items:center;gap:14px;font-size:14px;color:var(--fog)}
            .foot button{width:44px;height:44px;padding:0;font-size:20px;line-height:1}
            #count{min-width:5ch;text-align:center;font-variant-numeric:tabular-nums}
            .paused{position:fixed;top:calc(76px + var(--site-h,44px));left:50%;transform:translateX(-50%);z-index:6;display:none;align-items:center;gap:8px;
              padding:5px 13px;border-radius:999px;background:rgba(10,18,32,.78);border:1px solid rgba(238,241,247,.22);color:var(--moon);
              font-size:13px;pointer-events:none}
            .paused i{display:inline-flex;gap:3px}
            .paused i::before,.paused i::after{content:'';width:3px;height:11px;border-radius:1px;background:currentColor}
            body.held .paused{display:flex}
            /* ---- cards */
            #stage{position:fixed;inset:var(--site-h,44px) 0 0 0;cursor:pointer;-webkit-tap-highlight-color:transparent;user-select:none;-webkit-user-select:none;
              -webkit-touch-callout:none;touch-action:manipulation}
            .card{position:absolute;inset:0;display:none;padding:110px clamp(20px,6vw,96px) 92px;overflow:hidden}
            .card.on{display:flex;align-items:center}
            .inner{position:relative;width:100%;max-width:1280px;margin:0 auto;display:grid;grid-template-columns:minmax(0,5fr) minmax(0,6fr);
              gap:clamp(32px,5vw,88px);align-items:center;max-height:100%}
            .kicker{font-size:clamp(18px,1.6vw,24px);color:var(--moon);margin:0 0 10px;opacity:.9}
            .big{font:800 clamp(92px,min(15vw,27vh),236px)/.84 var(--display);color:var(--moon);margin:0;letter-spacing:-.005em;
              font-variant-numeric:tabular-nums}
            .big.word{font-size:clamp(54px,min(8vw,15vh),132px);line-height:.92;overflow-wrap:anywhere}
            .unit{display:block;font:700 clamp(26px,min(3.2vw,6vh),48px)/1.05 var(--display);color:var(--fog);margin-top:.28em;letter-spacing:.01em}
            .say{font-size:clamp(17px,1.45vw,21px);line-height:1.5;max-width:36ch;margin:22px 0 0;color:var(--fog)}
            .say b{color:var(--moon);font-weight:700}
            .say a{color:var(--moon)}
            .vis{min-width:0}
            .vis svg{display:block;width:100%;height:auto;max-height:60vh;overflow:visible}
            .vis svg text{font-family:var(--text)}
            .vis .lbl{font:700 22px var(--display);fill:var(--moon)}
            .vis .tick{font-size:13px;fill:var(--dim)}
            /* sport rows and lists */
            .stack{display:flex;height:22px;border-radius:11px;overflow:hidden;margin:0 0 26px;gap:3px}
            .stack i{display:block;height:100%}
            .rows{list-style:none;margin:0;padding:0;display:grid;gap:16px}
            .rows li{display:grid;grid-template-columns:14px minmax(0,1fr) auto;gap:4px 14px;align-items:baseline}
            .rows .dot{width:14px;height:14px;border-radius:50%;align-self:center}
            .rows .name{color:var(--moon);font-size:clamp(17px,1.5vw,21px);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .rows .val{font:700 clamp(26px,2.4vw,34px)/1 var(--display);color:var(--moon);font-variant-numeric:tabular-nums}
            .rows .sub{grid-column:2/4;font-size:14px;color:var(--fog);margin-top:-2px}
            .rows .meter{grid-column:2/4;height:6px;border-radius:3px;background:rgba(238,241,247,.1);overflow:hidden}
            .rows .meter i{display:block;height:100%;border-radius:3px}
            /* the top ten places: denser rows */
            .rows.top{gap:9px;counter-reset:top}
            .rows.top li{grid-template-columns:1.6em minmax(0,1fr) auto;gap:3px 10px}
            .rows.top li::before{counter-increment:top;content:counter(top);font:700 17px/1 var(--display);color:var(--dim);text-align:right}
            .rows.top .name{font-size:clamp(16px,1.3vw,19px)}
            .rows.top .val{font-size:clamp(20px,1.8vw,26px)}
            .rows.top .meter{height:4px}
            .rows.top .meter i{opacity:.9}
            @media (min-width:821px) and (max-height:760px){.rows.top{grid-template-columns:1fr 1fr;column-gap:36px}}
            .chips{list-style:none;margin:0;padding:0;display:flex;flex-wrap:wrap;gap:10px}
            .chips li{display:flex;align-items:center;gap:8px;padding:8px 14px;border-radius:999px;background:rgba(10,18,32,.45);
              border:1px solid rgba(238,241,247,.14);color:var(--moon);font-size:16px}
            .chips li i{width:9px;height:9px;border-radius:50%;flex:none}
            .chips li small{color:var(--dim);font-size:13px}
            .chips li.more{color:var(--fog);background:none}
            .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:18px}
            .tiles div{border-top:1px solid rgba(238,241,247,.2);padding-top:12px}
            .tiles dt{font-size:15px;color:var(--fog)}
            .tiles dd{margin:2px 0 0;font:800 clamp(44px,4.4vw,64px)/1 var(--display);color:var(--moon)}
            .recap{grid-template-columns:repeat(auto-fit,minmax(170px,1fr))}
            .recap dd{font-size:clamp(30px,3vw,44px);line-height:1.05;overflow-wrap:anywhere}
            .recap dd.w{font-size:clamp(24px,2.2vw,32px)}
            .tiles dd small{display:block;font:400 14px/1.4 var(--text);color:var(--fog);margin-top:6px}
            .frame{position:relative;width:100%;aspect-ratio:4/3;max-height:58vh;border-radius:18px;overflow:hidden;
              background:rgba(10,18,32,.5);border:1px solid rgba(238,241,247,.1)}
            .frame canvas{position:absolute;inset:0;width:100%;height:100%}
            .emoji{font-size:clamp(120px,18vw,260px);line-height:1;text-align:center}
            .second{font:800 clamp(64px,7vw,112px)/.9 var(--display);color:var(--moon);margin:0 0 4px}
            .second+p{margin:0 0 24px;color:var(--fog)}
            .acts{display:flex;flex-wrap:wrap;gap:12px;margin-top:30px}
            .acts button,.acts a{font-size:17px;padding:11px 20px;border-radius:999px;text-decoration:none;border:1px solid rgba(238,241,247,.3);
              background:rgba(10,18,32,.4);color:var(--moon);cursor:pointer}
            .acts .main{background:var(--moon);color:var(--night);border-color:var(--moon);font-weight:700}
            /* the map card: the routes fill the card, the words sit on top */
            .card.full .inner{display:block;position:static}
            .card.full canvas.bg{position:absolute;inset:0;width:100%;height:100%}
            .card.full .words{position:absolute;left:clamp(20px,6vw,96px);bottom:96px;max-width:min(560px,calc(100% - 40px));z-index:1}
            .card.full .words::before{content:'';position:absolute;z-index:-1;inset:-120px -200px -60px -140px;pointer-events:none;
              background:radial-gradient(closest-side,rgba(10,18,32,.9),rgba(10,18,32,.55) 55%,rgba(10,18,32,0))}
            /* entrances */
            .card.on .words>*{animation:rise .7s cubic-bezier(.2,.7,.2,1) both}
            .card.on .words>:nth-child(2){animation-delay:.08s}
            .card.on .words>:nth-child(3){animation-delay:.2s}
            .card.on .words>:nth-child(4){animation-delay:.3s}
            .card.on .vis{animation:fade 1s .25s both}
            @keyframes rise{from{opacity:0;transform:translateY(24px)}}
            @keyframes fade{from{opacity:0;transform:scale(.98)}}
            @media (prefers-reduced-motion:reduce){
              .card.on .words>*,.card.on .vis{animation:none}
            }
            @media (max-width:820px),(max-aspect-ratio:1/1){
              .inner{grid-template-columns:minmax(0,1fr);gap:28px;align-content:center}
              .card{padding:100px 20px 84px}
              .big{font-size:clamp(80px,min(25vw,17vh),170px)}
              .big.word{font-size:clamp(46px,min(13vw,9vh),96px)}
              .say{margin-top:14px}
              .vis svg,.frame{max-height:36vh}
              .frame{aspect-ratio:auto;height:34vh}
              .emoji{font-size:min(28vw,22vh)}
              .rows{gap:10px}
              .rows.top{gap:5px}
              .rows.top li{gap:1px 8px}
              .rows.top li::before{font-size:15px}
              .rows.top .name{font-size:15px}
              .rows.top .val{font-size:19px}
              .rows.top .meter{height:3px}
              .card.top .say{font-size:16px}
              .card.top .inner{gap:18px}
              .card.full .words{bottom:84px;left:20px}
            }
            @media (max-width:520px){
              .row{gap:8px}
              .row .right{gap:6px}
              select,button{padding:6px 11px}
              #play{min-width:0}
              .tiles{grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}
              .tiles dd{font-size:38px}
            }
            """;

    private static final String BODY = """
            <div id="stage" role="region" aria-roledescription="carousel" aria-label="Year in review of {{LABEL}}" data-nl-aria="Jaaroverzicht van {{LABEL}}"></div>
            <header class="bar">
              <div class="segs" id="segs"></div>
              <div class="row">
                <div class="left"><button id="menu" type="button" aria-expanded="false" aria-controls="navbox"><svg viewBox="0 0 16 16" aria-hidden="true">
                  <path d="M2 4h12M2 8h12M2 12h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg><span data-nl="Menu">Menu</span></button>
                  <div id="navbox">{{NAV}}</div></div>
                <div class="right"><label class="sr" for="year" data-nl="Jaar">Year</label><select id="year"></select>
                  <button id="play" type="button" aria-pressed="false" title="Space plays and pauses; hold a card to pause it"
                    data-nl-title="Spatie speelt af en pauzeert; houd een kaart ingedrukt om te pauzeren">Play</button>
                  <a class="close" href="index.html" aria-label="Close the year in review" data-nl-aria="Sluit het jaaroverzicht">&times;</a></div>
              </div>
            </header>
            <div class="paused" id="paused" aria-hidden="true"><i></i><span data-nl="Gepauzeerd">Paused</span></div>
            <nav class="foot" aria-label="Cards" data-nl-aria="Kaarten"><button id="prev" type="button" aria-label="Previous card" data-nl-aria="Vorige kaart">&larr;</button>
              <span id="count" aria-live="polite"></span><button id="next" type="button" aria-label="Next card" data-nl-aria="Volgende kaart">&rarr;</button></nav>
            """;

    private static final String JS = """
            (function(){
            'use strict';
            var NL=window.LANG=='nl',T=window.L||function(en){return en};
            var WHO=window.WHO||T('You','Jij'),Y0=window.YEARS||{days:[]},DAYS=Y0.days||[],ICONS=Y0.icons||{};
            var CLASS={running:'run',treadmill_running:'run',cycling:'ride',indoor_cycling:'ride',walking:'walk'};
            var COLOR={run:'#d95926',ride:'#3987e5',walk:'#199e70',other:'#8792a8'};
            var SPORT=NL?{run:'lopen',ride:'fietsen',walk:'wandelen',other:'andere sporten'}:{run:'running',ride:'cycling',walk:'walking',other:'other sports'};
            var ORDER=['run','ride','walk','other'];
            var MONTHS=NL?['januari','februari','maart','april','mei','juni','juli','augustus','september','oktober','november','december']
              :['January','February','March','April','May','June','July','August','September','October','November','December'];
            var MON=NL?['jan','feb','mrt','apr','mei','jun','jul','aug','sep','okt','nov','dec']:['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
            var WEEKDAYS=NL?['maandag','dinsdag','woensdag','donderdag','vrijdag','zaterdag','zondag']
              :['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'];
            // [English one, English more, Dutch one, Dutch more]
            var NOUN={activity:['activity','activities','activiteit','activiteiten'],day:['day','days','dag','dagen'],hour:['hour','hours','uur','uur'],
              time:['time','times','keer','keer'],visit:['visit','visits','bezoek','bezoeken'],route:['route','routes','route','routes'],
              place:['new place','new places','nieuwe plek','nieuwe plekken']};
            var reduced=window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
            var NB=' ';
            function cls(g){return CLASS[g]||'other'}
            function dayNo(s){return Date.UTC(+s.slice(0,4),+s.slice(5,7)-1,+s.slice(8,10))/864e5}
            function iso(n){return new Date(n*864e5).toISOString().slice(0,10)}
            function wdOf(n){return (new Date(n*864e5).getUTCDay()+6)%7}
            function fmtInt(v){return Math.round(v).toLocaleString('en-US').replace(/,/g,NB)}
            function dec(s){return NL?s.replace('.',','):s}
            function fmtN(v,d){return d?dec(v.toFixed(d)):fmtInt(v)}
            function fmtKm(v){return v<20?dec(v.toFixed(1)):fmtInt(v)}
            function fmtDate(s){return +s.slice(8,10)+' '+MONTHS[+s.slice(5,7)-1]}
            function fmtDur(s){var h=Math.floor(s/3600),m=Math.round(s%3600/60);if(m==60){h++;m=0}
              return h?h+NB+T('h','u')+(m?NB+String(m).padStart(2,'0'):''):m+NB+'min'}
            function hm(h){h=((h%24)+24)%24;var H=Math.floor(h),M=Math.round((h-H)*60);if(M==60){H=(H+1)%24;M=0}return H+':'+String(M).padStart(2,'0')}
            function href(d,id){return d.slice(0,4)+'/'+d.slice(5,7)+'/'+d+'-report.html'+(id?'#a'+id:'')}
            function esc(s){return String(s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
            function rgba(hex,a){var n=parseInt(hex.slice(1),16);return 'rgba('+(n>>16)+','+(n>>8&255)+','+(n&255)+','+a+')'}
            function mix(a,b,t){var x=parseInt(a.slice(1),16),y=parseInt(b.slice(1),16);
              function ch(s){return Math.round(((x>>s)&255)*(1-t)+((y>>s)&255)*t)}return 'rgb('+ch(16)+','+ch(8)+','+ch(0)+')'}
            function hourOf(t){var m=/^(\\d+):(\\d+)/.exec(t||'');return m?(+m[1])+(+m[2])/60:-1}
            function noun(n,k){var w=NOUN[k],one=Math.round(n)==1;return NL?w[one?2:3]:w[one?0:1]}
            function many(n,k){return fmtInt(n)+' '+noun(n,k)}
            function cap(s){return s.charAt(0).toUpperCase()+s.slice(1)}
            function sum(list,f){var s=0;list.forEach(function(x){s+=f(x)});return s}
            function q(s){return '“'+esc(s)+'”'}
            function and(a,b){return a+' '+T('and','en')+' '+b}

            // ---- places: Garmin names an activity after where it starts, "Sint-Pieters-Leeuw Hardlopen"
            var WORDS='Hardlopen|Wandelen|Fietsen|Wegwielrennen|Mountainbiken|Hiken|Veldrijden|Lopen|Zwemmen|Openwaterzwemmen|Kajakken|Roeien|'
              +'Hindernisbaanracen|Trailrunning|Peddelen|Wielrennen|Gravelfietsen|Baanlopen|Skiën|Langlaufen';
            var PLACE=new RegExp('^(.+?) +(?:'+WORDS+')(?::.*)?$'),PLACE2=/^(.+?) - (?:Basis|Herstel|Drempel|Tempo|Sprint)/;
            function placeOf(n){n=(n||'').trim();var m=PLACE.exec(n)||PLACE2.exec(n);if(!m)return null;var p=m[1].trim();
              return p.length>1&&p.length<40&&!/[0-9]/.test(p)?p:null}

            // ---- every activity, and the GPS tracks for the ones that have one
            var ACTS=[],BYDAY={};
            DAYS.forEach(function(d){BYDAY[d[0]]=d;d[6].forEach(function(a){
              ACTS.push({date:d[0],y:+d[0].slice(0,4),n:dayNo(d[0]),sport:a[0],c:cls(a[1]),start:a[2],h:hourOf(a[2]),m:a[3]||0,secs:a[4]||0,
                name:(a[5]||'').trim(),place:placeOf(a[5])})})});
            var TRK={},TRACKS=[];
            (window.HEATMAP||[]).forEach(function(r){if(!r[7]||!r[7].length)return;
              var t={date:r[0],y:+r[0].slice(0,4),time:r[1],c:cls(r[3]),name:r[4],km:r[5],id:r[8],enc:r[7]};TRACKS.push(t);TRK[r[0]+'|'+r[1]]=t});
            TRACKS.sort(function(a,b){return a.date<b.date?-1:a.date>b.date?1:a.time<b.time?-1:1});
            ACTS.forEach(function(a){var t=TRK[a.date+'|'+a.start];if(t){a.id=t.id;a.trk=t}});
            var FIRST={};ACTS.forEach(function(a){if(a.place&&!(a.place in FIRST))FIRST[a.place]=a.y});
            var YEARS=[];ACTS.forEach(function(a){if(YEARS.indexOf(a.y)<0)YEARS.push(a.y)});YEARS.sort();
            var LAST=DAYS.length?DAYS[DAYS.length-1][0]:'';
            function decode(s){
              var pts=[],i=0,lat=0,lon=0;
              while(i<s.length){for(var k=0;k<2;k++){var r=0,sh=0,b;do{b=s.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32);
                var dd=(r&1)?~(r>>1):(r>>1);if(k==0)lat+=dd;else lon+=dd}pts.push(lon/1e5,lat/1e5)}
              var xy=new Float64Array(pts.length),x0=1,y0=1,x1=0,y1=0;
              for(var j=0;j<pts.length;j+=2){var x=(pts[j]+180)/360,sn=Math.sin(pts[j+1]*Math.PI/180),y=.5-Math.log((1+sn)/(1-sn))/(4*Math.PI);
                xy[j]=x;xy[j+1]=y;if(x<x0)x0=x;if(x>x1)x1=x;if(y<y0)y0=y;if(y>y1)y1=y}
              return {xy:xy,b:[x0,y0,x1,y1]};
            }
            function lines(t){if(!t.L){t.L=t.enc.map(decode);var b=[1,1,0,0];
              t.L.forEach(function(l){b=[Math.min(b[0],l.b[0]),Math.min(b[1],l.b[1]),Math.max(b[2],l.b[2]),Math.max(b[3],l.b[3])]});t.b=b}return t.L}
            function startOf(t){var L=lines(t);for(var i=0;i<L.length;i++)if(L[i].xy.length)return [L[i].xy[0],L[i].xy[1]];return [0,0]}

            // ---- drawing routes on a canvas, glowing: a wide faint stroke and a thin bright one, added up
            var drawToken=0;
            function drawRoutes(cv,list,box,animate){
              var token=++drawToken,dpr=Math.min(2,window.devicePixelRatio||1),w=cv.clientWidth,h=cv.clientHeight;if(!w||!h)return;
              cv.width=Math.round(w*dpr);cv.height=Math.round(h*dpr);var ctx=cv.getContext('2d');ctx.setTransform(dpr,0,0,dpr,0,0);
              var bw=box[2]-box[0],bh=box[3]-box[1],s=Math.min(w/bw,h/bh),ox=(w-bw*s)/2-box[0]*s,oy=(h-bh*s)/2-box[1]*s;
              ctx.lineJoin='round';ctx.lineCap='round';ctx.globalCompositeOperation='lighter';
              var few=list.length<6,passes=few?[[9,.1],[4,.22],[2.2,.95]]:list.length<60?[[5,.06],[1.6,.55]]:[[4,.03],[1.1,.3]];
              function one(t){var L=lines(t);passes.forEach(function(p){ctx.strokeStyle=rgba(COLOR[t.c],p[1]);ctx.lineWidth=p[0];ctx.beginPath();
                L.forEach(function(l){var xy=l.xy,lx=xy[0]*s+ox,ly=xy[1]*s+oy;ctx.moveTo(lx,ly);
                  for(var i=2;i<xy.length;i+=2){var x=xy[i]*s+ox,y=xy[i+1]*s+oy;if(i<xy.length-2&&Math.abs(x-lx)<.6&&Math.abs(y-ly)<.6)continue;ctx.lineTo(x,y);lx=x;ly=y}});
                ctx.stroke()})}
              if(!animate||reduced){list.forEach(one);return}
              var i=0,per=Math.max(1,Math.ceil(list.length/80));
              (function step(){if(token!=drawToken)return;for(var k=0;k<per&&i<list.length;k++)one(list[i++]);if(i<list.length)requestAnimationFrame(step)})();
            }
            function padBox(b,f,min){var w=Math.max(b[2]-b[0],min||0),h=Math.max(b[3]-b[1],min||0),cx=(b[0]+b[2])/2,cy=(b[1]+b[3])/2;
              w*=1+f;h*=1+f;return [cx-w/2,cy-h/2,cx+w/2,cy+h/2]}
            // The box around most start points of the year: one trip far away does not shrink home to a dot.
            function homeBox(list){
              // home is the start point with the most other starts within about 35 km; what starts within about 55 km of it counts
              var P=list.map(startOf),bi=0,bn=-1,N=.0015,R=.0022;
              P.forEach(function(p,i){var n=0;P.forEach(function(o){if(Math.abs(o[0]-p[0])<N&&Math.abs(o[1]-p[1])<N)n++});if(n>bn){bn=n;bi=i}});
              var cx=P[bi][0],cy=P[bi][1],xs=[],ys=[];
              // the box holds most of the ground covered by those routes (a sample of their points), not every last detour
              list.forEach(function(t){var p=startOf(t);if(Math.abs(p[0]-cx)<R&&Math.abs(p[1]-cy)<R)lines(t).forEach(function(l){
                for(var i=0;i<l.xy.length;i+=16){xs.push(l.xy[i]);ys.push(l.xy[i+1])}})});
              // centred on home, as wide as most of those points reach
              var dx=xs.map(function(x){return Math.abs(x-cx)}).sort(function(a,b){return a-b}),dy=ys.map(function(y){return Math.abs(y-cy)}).sort(function(a,b){return a-b});
              function qq(v,p){return v.length?v[Math.min(v.length-1,Math.floor(p*v.length))]:0}
              var rx=Math.max(qq(dx,.95),.0003),ry=Math.max(qq(dy,.95),.0003),b=[cx-rx,cy-ry,cx+rx,cy+ry];
              return padBox(b,.1,.0006)}

            // ---- one card
            function words(kicker,big,unit,say,o){o=o||{};var num=typeof big=='number';
              return '<div class="words"><p class="kicker">'+kicker+'</p><p class="big'+(o.word?' word':'')+'">'
                +(num?'<span class="num" data-n="'+big+'" data-d="'+(o.dec||0)+'">'+fmtN(big,o.dec||0)+'</span>':esc(big))
                +(unit?'<span class="unit">'+unit+'</span>':'')+'</p>'+(say?'<p class="say">'+say+'</p>':'')+(o.extra||'')+'</div>'}
            function bg(c){var col=COLOR[c]||COLOR.other;
              return 'radial-gradient(110% 80% at 88% 0%,'+rgba(col,.42)+',rgba(10,18,32,0) 62%),radial-gradient(90% 70% at 0% 100%,'+rgba(col,.2)
                +',rgba(10,18,32,0) 70%),'+mix(col,'#0a1220',.84)}
            function dominant(list,f){var s={};list.forEach(function(a){s[a.c]=(s[a.c]||0)+f(a)});var best='other';
              Object.keys(s).forEach(function(c){if(!(best in s)||s[c]>s[best])best=c});return best}
            var CITIES=[[173,'Amsterdam'],[264,T('Paris','Parijs')],[320,T('London','Londen')],[651,T('Berlin','Berlijn')],[1063,'Barcelona'],[1171,'Rome'],
              [1712,T('Lisbon','Lissabon')],[2170,T('Istanbul','Istanboel')],[2256,T('Moscow','Moskou')],[3210,T('Cairo','Caïro')],[5870,'New York'],
              [6340,'Delhi'],[7970,T('Beijing','Peking')],[9470,T('Tokyo','Tokio')],[16700,'Sydney']];

            function build(Y){
              var acts=ACTS.filter(function(a){return a.y==Y}),cards=[];
              var partial=Y==+LAST.slice(0,4)&&LAST.slice(5)<'12-31',cut=partial?LAST.slice(5):'12-31',so=partial?T(' so far',' tot nu toe'):'';
              var prev=ACTS.filter(function(a){return a.y==Y-1&&a.date.slice(5)<=cut});
              var km=sum(acts,function(a){return a.m})/1000,secs=sum(acts,function(a){return a.secs});
              var dates={};acts.forEach(function(a){dates[a.date]=1});var nDays=Object.keys(dates).length;
              var byC={};ORDER.forEach(function(c){byC[c]={km:0,secs:0,n:0}});
              acts.forEach(function(a){var b=byC[a.c];b.km+=a.m/1000;b.secs+=a.secs;b.n++});
              var top=ORDER.slice().sort(function(a,b){return byC[b].km-byC[a].km})[0];if(!byC[top].km)top=dominant(acts,function(a){return a.secs});
              var n0=Date.UTC(Y,0,1)/864e5,nLen=Date.UTC(Y+1,0,1)/864e5-n0,nEnd=partial?dayNo(LAST):n0+nLen-1;

              // 1. the cover: every day of the year as a line
              (function(){var per={},max=0;acts.forEach(function(a){var p=per[a.date]||(per[a.date]={km:0,list:[]});p.km+=a.m/1000;p.list.push(a)});
                Object.keys(per).forEach(function(k){if(per[k].km>max)max=per[k].km});
                var s='',W=nLen*3,H=200;
                for(var i=0;i<nLen;i++){var p=per[iso(n0+i)],x=i*3+1.5;
                  if(p){var c=dominant(p.list,function(a){return a.secs+1}),h=p.km>0?12+Math.sqrt(p.km/max)*(H-40):12;
                    s+='<line x1="'+x+'" x2="'+x+'" y1="'+(H-20)+'" y2="'+(H-20-h).toFixed(1)+'" stroke="'+COLOR[c]+'" stroke-width="2.2" stroke-linecap="round"/>'}
                  else if(n0+i<=nEnd)s+='<circle cx="'+x+'" cy="'+(H-21)+'" r="1" fill="rgba(238,241,247,.25)"/>'}
                for(var m=0;m<12;m++){var x0=(Date.UTC(Y,m,1)/864e5-n0)*3;s+='<text class="tick" x="'+x0+'" y="'+H+'">'+MONTHS[m].charAt(0).toUpperCase()+'</text>'}
                cards.push({c:top,label:T('The year ','Het jaar ')+Y,html:words(T(esc(WHO)+'’s year in review','Het jaaroverzicht van '+esc(WHO)),String(Y),'',
                  T('A look back at '+Y+so+': <b>'+many(acts.length,'activity')+'</b> on '+many(nDays,'day')+'. Press Play to let it run, or tap and use the arrows to go at your own pace.',
                    'Een terugblik op '+Y+so+': <b>'+many(acts.length,'activity')+'</b> op '+many(nDays,'day')+'. Druk op Afspelen om het vanzelf te laten lopen, of tik en gebruik de pijltjes om op je eigen tempo verder te gaan.'))
                  +'<div class="vis"><svg viewBox="0 0 '+W+' '+(H+4)+'" role="img" aria-label="'+T('Every day of '+Y+', a line per active day','Elke dag van '+Y+', een streepje per actieve dag')+'">'+s+'</svg></div>'})})();

              // 2. how far and how long, against the year before
              (function(){var pkm=sum(prev,function(a){return a.m})/1000,psecs=sum(prev,function(a){return a.secs}),
                  say=T('In '+many(secs/3600,'hour')+' on the move.',many(secs/3600,'hour')+' in beweging.');
                if(pkm>0){var p=Math.round((km/pkm-1)*100),when=partial?T('by this time in '+(Y-1),'in '+(Y-1)+' rond deze tijd'):T('in ','in ')+(Y-1);
                  say+=Math.abs(p)<3?T(' About as far as '+when+'.',' Ongeveer even ver als '+when+'.')
                    :T(' That’s <b>'+Math.abs(p)+'% '+(p>0?'more':'fewer')+'</b> kilometres than '+when+(p>15?'. Nicely done.':'.'),
                       ' Dat is <b>'+Math.abs(p)+'% '+(p>0?'meer':'minder')+'</b> kilometer dan '+when+(p>15?'. Goed bezig!':'.'))}
                var city=null;CITIES.forEach(function(c){if(km>=c[0])city=c});
                if(city)say+=T(' Laid end to end, that’s further than Brussels to '+city[1]+'.',' Achter elkaar gelegd is dat verder dan van Brussel naar '+city[1]+'.');
                var s='',W=560,rows=[[String(Y)+so,km,secs,COLOR[top]]];if(pkm>0)rows.push([String(Y-1)+(partial?T(' same days',' zelfde dagen'):''),pkm,psecs,'rgba(238,241,247,.35)']);
                var mk=Math.max.apply(null,rows.map(function(r){return r[1]})),mh=Math.max.apply(null,rows.map(function(r){return r[2]}));
                rows.forEach(function(r,i){var y=i*120;
                  s+='<text class="tick" x="0" y="'+(y+14)+'">'+r[0]+'</text>';
                  s+='<rect x="0" y="'+(y+24)+'" width="'+(r[1]/mk*(W-150)).toFixed(1)+'" height="30" rx="6" fill="'+r[3]+'"/>';
                  s+='<text class="lbl" x="'+(r[1]/mk*(W-150)+12).toFixed(1)+'" y="'+(y+48)+'">'+fmtInt(r[1])+'\\u00a0km</text>';
                  s+='<rect x="0" y="'+(y+62)+'" width="'+(r[2]/mh*(W-150)).toFixed(1)+'" height="14" rx="4" fill="'+r[3]+'" opacity=".55"/>';
                  s+='<text class="tick" x="'+(r[2]/mh*(W-150)+12).toFixed(1)+'" y="'+(y+75)+'">'+many(r[2]/3600,'hour')+'</text>'});
                cards.push({c:top,label:T('Distance','Afstand'),fact:[T('Distance','Afstand'),fmtInt(km)+NB+'km'],
                  html:words(T('Altogether you covered','In totaal legde je'),Math.round(km),T('kilometres'+so,'kilometer af'+(partial?', tot nu toe':'')),say)
                  +'<div class="vis"><svg viewBox="0 0 '+W+' '+(rows.length*120-30)+'" role="img" aria-label="'+T('Kilometres and hours compared with the year before','Kilometers en uren tegenover het jaar ervoor')+'">'+s+'</svg></div>'})})();

              // 3. the sport split
              (function(){var tk=sum(ORDER,function(c){return byC[c].km});if(!tk)return;
                var share=Math.round(byC[top].km/tk*100),how=(NL?{run:'procent ervan al lopend',ride:'procent ervan op de fiets',walk:'procent ervan al wandelend',other:'procent ervan met andere sporten'}
                  :{run:'percent of it running',ride:'percent of it on the bike',walk:'percent of it walking',other:'percent of it doing other sports'})[top];
                var rest=ORDER.filter(function(c){return c!=top&&byC[c].n&&(byC[c].km>=.5||byC[c].secs>=1800)}).map(function(c){var b=byC[c],sh=b.km/tk*100;
                  return SPORT[c]+' '+(b.km>=.5?(sh<1?T('under 1%','minder dan 1%'):Math.round(sh)+'%'):many(b.secs/3600,'hour'))});
                var say=T('Of every kilometre, '+share+'% was '+SPORT[top],'Van elke kilometer was '+share+'% '+SPORT[top])
                  +(rest.length?T('. The rest: ','. De rest: ')+rest.join(', ')+'.':T('. Nothing else came close.','. Niets anders kwam in de buurt.'));
                var bar=ORDER.filter(function(c){return byC[c].km>0}).map(function(c){return '<i style="flex:'+byC[c].km+';background:'+COLOR[c]+'"></i>'}).join('');
                var mx=Math.max.apply(null,ORDER.map(function(c){return byC[c].secs}));
                var rows=ORDER.filter(function(c){return byC[c].n}).map(function(c){var b=byC[c];
                  return '<li><i class="dot" style="background:'+COLOR[c]+'"></i><span class="name">'+cap(SPORT[c])+'</span><span class="val">'
                    +(b.km>=1?fmtInt(b.km)+'\\u00a0km':fmtInt(b.secs/3600)+' '+T('h','u'))+'</span><span class="meter"><i style="width:'+(b.secs/mx*100).toFixed(1)
                    +'%;background:'+COLOR[c]+'"></i></span><span class="sub">'+many(b.n,'activity')+', '+many(b.secs/3600,'hour')+'</span></li>'}).join('');
                cards.push({c:top,label:T('Sport split','Verdeling per sport'),html:words(T('How you moved','Hoe je bewoog'),share,how,say)
                  +'<div class="vis"><div class="stack" aria-hidden="true">'+bar+'</div><ul class="rows">'+rows+'</ul></div>'})})();

              // 4. the biggest week
              (function(){var mon0=n0-wdOf(n0),nW=Math.floor((n0+nLen-1-mon0)/7)+1,wk=[];
                for(var i=0;i<nW;i++)wk.push({km:0,secs:0,list:[]});
                acts.forEach(function(a){var w=wk[Math.floor((a.n-mon0)/7)];w.km+=a.m/1000;w.secs+=a.secs;w.list.push(a)});
                var bi=0;wk.forEach(function(w,i){if(w.km>wk[bi].km)bi=i});var b=wk[bi];if(!b.km)return;
                var c=dominant(b.list,function(a){return a.m}),days={};b.list.forEach(function(a){days[a.date]=1});
                var avg=km/Math.max(1,Math.ceil((nEnd-n0+1)/7)),nd=many(Object.keys(days).length,'day');
                var say=T('The week of <b>'+fmtDate(iso(mon0+bi*7))+'</b>: '+many(b.list.length,'activity')+' on '+nd+', '+fmtDur(b.secs)+' in all, mostly '+SPORT[c]
                    +'. Your usual week was '+fmtInt(avg)+'\\u00a0km.',
                  'De week van <b>'+fmtDate(iso(mon0+bi*7))+'</b>: '+many(b.list.length,'activity')+' op '+nd+', in totaal '+fmtDur(b.secs)+', vooral '+SPORT[c]
                    +'. Een gewone week was '+fmtInt(avg)+'\\u00a0km.');
                var W=nW*12,H=210,mx=b.km,s='';
                wk.forEach(function(w,i){if(!w.km)return;var h=Math.max(3,w.km/mx*(H-60)),cc=dominant(w.list,function(a){return a.m});
                  s+='<rect x="'+(i*12)+'" y="'+(H-22-h).toFixed(1)+'" width="9" height="'+h.toFixed(1)+'" rx="2" fill="'+COLOR[cc]+'" opacity="'+(i==bi?1:.42)+'"/>'});
                var lx=Math.min(W-60,Math.max(0,bi*12-20));
                s+='<text class="lbl" x="'+lx+'" y="'+(H-22-(H-60)-12)+'">'+fmtInt(b.km)+'\\u00a0km</text>';
                for(var m=0;m<12;m++){var x=Math.floor((Date.UTC(Y,m,1)/864e5-mon0)/7)*12;s+='<text class="tick" x="'+x+'" y="'+H+'">'+MONTHS[m].charAt(0).toUpperCase()+'</text>'}
                cards.push({c:c,label:T('Biggest week','Grootste week'),fact:[T('Biggest week','Grootste week'),fmtInt(b.km)+NB+'km'],
                  html:words(T('Your biggest week','Je grootste week'),Math.round(b.km),T('kilometres in seven days','kilometer in zeven dagen'),say)
                  +'<div class="vis"><svg viewBox="0 0 '+W+' '+(H+4)+'" role="img" aria-label="'+T('Kilometres per week','Kilometer per week')+'">'+s+'</svg></div>'})})();

              // 5. the longest one: skip what cannot be real (a watch left running, a distance typed in by hand)
              (function(){var L=acts.filter(function(a){return a.c!='other'&&a.secs>=600&&a.secs<20*3600&&a.m/a.secs<22})
                  .reduce(function(m,a){return !m||a.m>m.m?a:m},null);if(!L)return;
                var wd=WEEKDAYS[wdOf(L.n)],say=(L.name?q(L.name)+T(', on ',', op '):T('On ','Op '))+wd+' '+fmtDate(L.date)+'. '
                  +T(fmtDur(L.secs)+' of '+SPORT[L.c]+' in one go.',fmtDur(L.secs)+' '+SPORT[L.c]+' in één ruk.')
                  +' <a href="'+href(L.date,L.id)+'">'+T('Open that day','Bekijk die dag')+'</a>';
                var vis=L.trk?'<div class="vis"><div class="frame"><canvas aria-label="'+T('The route of the longest activity','De route van de langste activiteit')+'"></canvas></div></div>'
                  :'<div class="vis"><div class="emoji" aria-hidden="true">'+(ICONS[L.sport]||'⏱')+'</div></div>';
                cards.push({c:L.c,label:T('Longest activity','Langste activiteit'),fact:[T('Longest one','Langste tocht'),fmtKm(L.m/1000)+NB+'km'],
                  html:words(T('Your longest one','Je langste tocht'),L.m>=20000?Math.round(L.m/1000):+(L.m/1000).toFixed(1),T('kilometres','kilometer'),say,{dec:L.m>=20000?0:1})+vis,
                  after:L.trk?function(el){var t=L.trk;lines(t);var b=padBox(t.b,.16,.0002);drawRoutes(el.querySelector('canvas'),[t],b,true)}:null})})();

              // 6. where you went most: the top ten
              var places={};acts.forEach(function(a){if(!a.place)return;var p=places[a.place]||(places[a.place]={name:a.place,n:0,km:0,list:[]});p.n++;p.km+=a.m/1000;p.list.push(a)});
              var plist=Object.keys(places).map(function(k){return places[k]}).sort(function(a,b){return b.n-a.n||b.km-a.km});
              if(plist.length){(function(){var p=plist[0],c=dominant(p.list,function(a){return 1});
                var say='<b>'+many(p.n,'time')+'</b> '+T('and','en')+' '+fmtInt(p.km)+'\\u00a0km, '+T('mostly','vooral')+' '+SPORT[c]+'. '
                  +(plist.length>1?T('Then ','Daarna ')+(plist.length>2?and(esc(plist[1].name),esc(plist[2].name)):esc(plist[1].name))+'.':'');
                var mx=p.n,rows=plist.slice(0,10).map(function(x){var cc=dominant(x.list,function(){return 1});
                  return '<li><span class="name">'+esc(x.name)+'</span><span class="val">'+x.n+'</span><span class="meter"><i style="width:'
                    +(x.n/mx*100).toFixed(1)+'%;background:'+COLOR[cc]+'"></i></span></li>'}).join('');
                cards.push({c:'walk',cls:'top',label:T('Favourite place','Favoriete plek'),fact:[T('Most visited','Vaakst bezocht'),esc(p.name),1],
                  html:words(T('Where you went most','Waar je het vaakst was'),p.name,'',say,{word:true})
                  +'<div class="vis"><ol class="rows top" aria-label="'+T('Top ten places, by number of visits','Top tien plekken, volgens aantal bezoeken')+'">'+rows+'</ol></div>'})})()}

              // 7. new places: never in a name before this year
              (function(){var hadBefore=Object.keys(FIRST).some(function(k){return FIRST[k]<Y});if(!hadBefore)return;
                var fresh=plist.filter(function(p){return FIRST[p.name]==Y}).map(function(p){var f=p.list[0];return {name:p.name,date:f.date,c:f.c,n:p.n}})
                  .sort(function(a,b){return a.date<b.date?-1:1});if(!fresh.length)return;
                var far=fresh.slice().sort(function(a,b){return b.n-a.n})[0],m0=MONTHS[+fresh[0].date.slice(5,7)-1];
                var say=T('Places you had never started from before, beginning with <b>'+esc(fresh[0].name)+'</b> in '+m0+'.',
                    'Plekken waar je nooit eerder vertrok, te beginnen met <b>'+esc(fresh[0].name)+'</b> in '+m0+'.')
                  +(fresh.length>1&&far.n>1?T(' You liked '+esc(far.name)+': '+many(far.n,'visit')+'.',' '+esc(far.name)+' beviel je: '+many(far.n,'visit')+'.'):'');
                var shown=fresh.slice(0,16),chips=shown.map(function(p){return '<li><i style="background:'+COLOR[p.c]+'"></i>'+esc(p.name)+' <small>'
                  +(+p.date.slice(8,10))+' '+MON[+p.date.slice(5,7)-1]+'</small></li>'}).join('')
                  +(fresh.length>shown.length?'<li class="more">'+T('and '+(fresh.length-shown.length)+' more','en nog '+(fresh.length-shown.length))+'</li>':'');
                cards.push({c:'ride',label:T('New places','Nieuwe plekken'),fact:[T('New places','Nieuwe plekken'),fresh.length],
                  html:words(T('New on the map','Nieuw op de kaart'),fresh.length,noun(fresh.length,'place'),say)
                  +'<div class="vis"><ul class="chips">'+chips+'</ul></div>'})})();

              // 8. early bird, night owl
              (function(){var ok=acts.filter(function(a){return a.h>=0&&a.secs>=600});if(ok.length<3)return;
                var early=ok.filter(function(a){return a.h>=3}).reduce(function(m,a){return !m||a.h<m.h?a:m},null);
                var late=ok.filter(function(a){return a.secs<6*3600}).reduce(function(m,a){var f=(a.h<3?a.h+24:a.h)+a.secs/3600;return !m||f>m.f?{a:a,f:f}:m},null);
                if(!early||!late)return;
                var eh=early.h,lf=late.f;
                var say=T('That was ','Dat was ')+(early.name?q(early.name):SPORT[early.c])+T(', on ',', op ')+WEEKDAYS[wdOf(early.n)]+' '+fmtDate(early.date)
                  +(eh<7?T('. While most people were still asleep.','. Terwijl de meeste mensen nog sliepen.'):'.');
                var s='',W=600,X=function(h){return 10+(h-3)/24*(W-20)};
                s+='<line x1="10" x2="'+(W-10)+'" y1="70" y2="70" stroke="rgba(238,241,247,.25)"/>';
                [3,6,9,12,15,18,21,24,27].forEach(function(h){s+='<text class="tick" x="'+X(h)+'" y="128" text-anchor="middle">'+hm(h)+'</text>'
                  +'<line x1="'+X(h)+'" x2="'+X(h)+'" y1="64" y2="76" stroke="rgba(238,241,247,.25)"/>'});
                ok.forEach(function(a,i){var h=a.h<3?a.h+24:a.h;s+='<circle cx="'+X(h).toFixed(1)+'" cy="'+(52-(i*37%30))+'" r="2.6" fill="'+COLOR[a.c]+'" opacity=".6"/>'});
                s+='<rect x="'+X(eh).toFixed(1)+'" y="66" width="'+(X(Math.min(27,lf))-X(eh)).toFixed(1)+'" height="8" rx="4" fill="rgba(238,241,247,.18)"/>';
                s+='<circle cx="'+X(eh).toFixed(1)+'" cy="70" r="9" fill="#eef1f7"/><circle cx="'+X(Math.min(27,lf)).toFixed(1)+'" cy="70" r="9" fill="#eef1f7"/>';
                s+='<text class="tick" x="'+X(eh).toFixed(1)+'" y="100" text-anchor="middle">'+T('first out','eerste vertrek')+'</text><text class="tick" x="'
                  +X(Math.min(27,lf)).toFixed(1)+'" y="100" text-anchor="middle">'+T('last home','laatst thuis')+'</text>';
                cards.push({c:'run',label:T('Early and late','Vroeg en laat'),html:words(T('Early bird','Vroege vogel'),hm(eh),T('your earliest start','je vroegste vertrek'),say)
                  +'<div class="vis"><p class="kicker">'+T('Night owl','Nachtuil')+'</p><p class="second">'+hm(lf)+'</p><p>'
                  +T('Your latest finish'+(lf>=24?', past midnight':''),'Je laatste aankomst'+(lf>=24?', na middernacht':''))+': '
                  +(late.a.name?q(late.a.name):SPORT[late.a.c])+T(', on ',', op ')+fmtDate(late.a.date)+'.</p>'
                  +'<svg viewBox="0 0 '+W+' 132" role="img" aria-label="'+T('Start times of every activity of the year','Starttijden van elke activiteit van het jaar')+'">'+s+'</svg></div>'})})();

              // 9. the longest streak
              (function(){var ds=Object.keys(dates).sort();if(!ds.length)return;var best={len:0},cur=0,from=null,pn=null;
                ds.forEach(function(d){var n=dayNo(d);if(pn!=null&&n==pn+1)cur++;else{cur=1;from=d}if(cur>best.len)best={len:cur,from:from,to:d};pn=n});
                var active=Math.round(nDays/(nEnd-n0+1)*100);
                var say=best.len>1?T('From '+fmtDate(best.from)+' to '+fmtDate(best.to)+', something every single day. Over the whole year you moved on '+active+'% of days.',
                    'Van '+fmtDate(best.from)+' tot '+fmtDate(best.to)+' elke dag iets. Over het hele jaar bewoog je op '+active+'% van de dagen.')
                  :T('You moved on '+active+'% of the days'+so+'.','Je bewoog op '+active+'% van de dagen'+so+'.');
                var mon0=n0-wdOf(n0),s='',C=15,sn0=dayNo(best.from),sn1=dayNo(best.to),per={};acts.forEach(function(a){(per[a.date]=per[a.date]||[]).push(a)});
                for(var i=0;i<nLen;i++){var n=n0+i,col=Math.floor((n-mon0)/7),row=wdOf(n),d=iso(n),p=per[d],fill;
                  if(p){var c=dominant(p,function(a){return a.secs+1});fill=n>=sn0&&n<=sn1?COLOR[c]:rgba(COLOR[c],.38)}
                  else fill=n<=nEnd?'rgba(238,241,247,.07)':'rgba(238,241,247,.025)';
                  s+='<rect x="'+col*C+'" y="'+row*C+'" width="'+(C-3)+'" height="'+(C-3)+'" rx="2.5" fill="'+fill+'"'+(n>=sn0&&n<=sn1?' stroke="#eef1f7" stroke-width="1.2"':'')+'/>'}
                for(var m=0;m<12;m++){var x=Math.floor((Date.UTC(Y,m,1)/864e5-mon0)/7)*C;s+='<text class="tick" x="'+x+'" y="'+(7*C+16)+'">'+MON[m]+'</text>'}
                var W=(Math.floor((n0+nLen-1-mon0)/7)+1)*C;
                cards.push({c:'walk',label:T('Longest streak','Langste reeks'),fact:[T('Longest streak','Langste reeks'),many(best.len,'day')],
                  html:words(T('Your longest streak','Je langste reeks'),best.len,best.len==1?T('day','dag'):T('days in a row','dagen op rij'),say)
                  +'<div class="vis"><svg viewBox="0 0 '+W+' '+(7*C+20)+'" role="img" aria-label="'+T('Calendar of '+Y+', the streak outlined','Kalender van '+Y+', met de reeks omlijnd')+'">'+s+'</svg></div>'})})();

              // 10. the biggest month
              (function(){var mo=[];for(var m=0;m<12;m++){var o={km:0,list:[]};ORDER.forEach(function(c){o[c]=0});mo.push(o)}
                acts.forEach(function(a){var o=mo[+a.date.slice(5,7)-1];o.km+=a.m/1000;o[a.c]+=a.m/1000;o.list.push(a)});
                var bi=0;mo.forEach(function(o,i){if(o.km>mo[bi].km)bi=i});var b=mo[bi];if(!b.km)return;
                var months=partial?+LAST.slice(5,7):12,avg=km/months,dur=fmtDur(sum(b.list,function(a){return a.secs}));
                var say='<b>'+many(b.list.length,'activity')+'</b> '+T('and '+dur+' on the move in '+MONTHS[bi],'en '+dur+' in beweging in '+MONTHS[bi])
                  +'. '+(b.km>avg*1.15?Math.round((b.km/avg-1)*100)+T('% more than an average month.','% meer dan een gemiddelde maand.'):T('Steady all year round.','Het hele jaar door even regelmatig.'));
                var W=600,H=230,s='';
                mo.forEach(function(o,i){var x=i*50+6,y=H-26;ORDER.forEach(function(c){if(!o[c])return;var h=o[c]/b.km*(H-70);
                  s+='<rect x="'+x+'" y="'+(y-h).toFixed(1)+'" width="36" height="'+h.toFixed(1)+'" fill="'+COLOR[c]+'" opacity="'+(i==bi?1:.45)+'"/>';y-=h});
                  s+='<text class="tick" x="'+(x+18)+'" y="'+H+'" text-anchor="middle">'+MONTHS[i].charAt(0).toUpperCase()+'</text>'});
                s+='<text class="lbl" x="'+(bi*50+24)+'" y="'+(H-26-(H-70)-12)+'" text-anchor="middle">'+fmtInt(b.km)+'</text>';
                cards.push({c:dominant(b.list,function(a){return a.m}),label:T('Biggest month','Grootste maand'),fact:[T('Biggest month','Grootste maand'),cap(MONTHS[bi])],
                  html:words(T('Your biggest month','Je grootste maand'),Math.round(b.km),T('kilometres in ','kilometer in ')+MONTHS[bi],say)
                  +'<div class="vis"><svg viewBox="0 0 '+W+' '+(H+4)+'" role="img" aria-label="'+T('Kilometres per month','Kilometer per maand')+'">'+s+'</svg></div>'})})();

              // 11. every route of the year, glowing
              (function(){var list=TRACKS.filter(function(t){return t.y==Y});if(!list.length)return;
                var box=homeBox(list),away=list.filter(function(t){var p=startOf(t);return p[0]<box[0]||p[0]>box[2]||p[1]<box[1]||p[1]>box[3]});
                var gkm=sum(list,function(t){return t.km}),awayPlaces=[];
                away.forEach(function(t){var p=placeOf(t.name);if(p&&awayPlaces.indexOf(p)<0)awayPlaces.push(p)});
                var like=awayPlaces.length?T(', like ',', zoals ')+awayPlaces.slice(0,3).map(esc).join(', '):'';
                var say=T(fmtInt(gkm)+'\\u00a0km with GPS, drawn on top of each other: the brighter, the more often.',
                    fmtInt(gkm)+'\\u00a0km met gps, over elkaar getekend: hoe feller, hoe vaker.')
                  +(away.length?T(' Plus '+many(away.length,'route')+' further from home'+like+'.',' En nog '+many(away.length,'route')+' verder van huis'+like+'.'):'');
                cards.push({c:'other',full:true,label:T('Every route','Alle routes'),html:'<canvas class="bg" aria-hidden="true"></canvas>'
                  +words(T('Everywhere you went','Overal waar je was'),list.length,noun(list.length,'route')+so,say),
                  after:function(el){var cv=el.querySelector('canvas.bg'),w=cv.clientWidth,h=cv.clientHeight,b=box.slice(),
                      bw=b[2]-b[0],bh=b[3]-b[1];
                    // wide screens: home in the right part of the card, the words on the left
                    if(w>900){b[0]=b[2]-bw/.62;bw=b[2]-b[0];if(bw/bh<w/h){var extra=bh*w/h-bw;b[0]-=extra*.38;b[2]+=extra*.62}}
                    // tall screens: home in the upper part, the words below
                    else if(h>w){b[3]=b[1]+bh/.6;bh=b[3]-b[1];if(bw/bh>w/h){var ex=bw*h/w-bh;b[1]-=ex*.4;b[3]+=ex*.6}}
                    drawRoutes(cv,list,b,true)}})})();

              // 12. day to day: steps and sleep
              (function(){var ds=DAYS.filter(function(d){return +d[0].slice(0,4)==Y}),pds=DAYS.filter(function(d){return +d[0].slice(0,4)==Y-1&&d[0].slice(5)<=cut});
                function night(d){return d[5]>=3*3600&&d[5]<=14*3600}
                function avg(list,f){var v=list.map(f).filter(function(x){return x>0});return v.length>=20?sum(v,function(x){return x})/v.length:0}
                var st=avg(ds,function(d){return d[1]>=1000?d[1]:0}),sl=avg(ds,function(d){return night(d)?d[5]/3600:0}),hr=avg(ds,function(d){return night(d)?d[2]:0});
                var pst=avg(pds,function(d){return d[1]>=1000?d[1]:0}),psl=avg(pds,function(d){return night(d)?d[5]/3600:0}),phr=avg(pds,function(d){return night(d)?d[2]:0});
                if(!st&&!sl)return;
                function vs(a,b,f,unit){if(!a||!b)return '';var d=a-b;
                  return Math.abs(d)<(unit=='bpm'?.5:unit=='h'?.05:a*.02)?T('same as ','zelfde als ')+(Y-1):(d>0?'+':'−')+f(Math.abs(d))+T(' on ',' t.o.v. ')+(Y-1)}
                var tiles=[];
                if(st)tiles.push([T('Steps a day','Stappen per dag'),fmtInt(st),vs(st,pst,fmtInt)]);
                if(sl)tiles.push([T('Sleep a night','Slaap per nacht'),Math.floor(sl)+':'+String(Math.round(sl%1*60)%60).padStart(2,'0'),vs(sl,psl,function(v){return Math.round(v*60)+' min'},'h')]);
                if(hr)tiles.push([T('Resting heart rate','Rusthartslag'),Math.round(hr),vs(hr,phr,function(v){return Math.round(v)+' bpm'},'bpm')]);
                var say=st?(sl?T('And about '+fmtDur(sl*3600)+' of sleep a night'+(hr?', with a resting heart rate of '+Math.round(hr)+'.':'.'),
                        'En zo’n '+fmtDur(sl*3600)+' slaap per nacht'+(hr?', met een rusthartslag van '+Math.round(hr)+'.':'.'))
                    :T('On the days you wore your watch.','Op de dagen dat je je horloge droeg.'))
                    +(pst?' '+(st>pst*1.02?T('Up from '+fmtInt(pst)+' in '+(Y-1)+'.','Meer dan de '+fmtInt(pst)+' van '+(Y-1)+'.')
                      :st<pst*.98?T('Down from '+fmtInt(pst)+' in '+(Y-1)+'.','Minder dan de '+fmtInt(pst)+' van '+(Y-1)+'.'):''):'')
                  :T('On the nights you wore your watch.','Op de nachten dat je je horloge droeg.');
                cards.push({c:'walk',label:T('Day to day','Dag na dag'),html:words(T('Day to day','Dag na dag'),st?Math.round(st):+sl.toFixed(1),
                    st?T('steps a day','stappen per dag'):T('hours of sleep a night','uur slaap per nacht'),say,{dec:st?0:1})
                  +'<div class="vis">'+(tiles.length>1?'<dl class="tiles">'+tiles.slice(1).map(function(t){return '<div><dt>'+t[0]+'</dt><dd>'+t[1]+(t[2]?'<small>'+t[2]+'</small>':'')+'</dd></div>'}).join('')+'</dl>'
                    :monthly(ds,st?function(d){return d[1]>=1000?d[1]:0}:function(d){return night(d)?d[5]/3600:0},st?fmtInt:function(v){return dec(v.toFixed(1))}))+'</div>'})
                // the month-by-month average, when there is only one thing to show
                function monthly(list,f,fmt){var mo=[];for(var m=0;m<12;m++)mo.push([]);list.forEach(function(d){var v=f(d);if(v>0)mo[+d[0].slice(5,7)-1].push(v)});
                  var av=mo.map(function(a){return a.length>=5?sum(a,function(x){return x})/a.length:0}),mx=Math.max.apply(null,av)||1,W=600,H=230,s='';
                  av.forEach(function(v,i){var x=i*50+6;if(v){var h=v/mx*(H-70);s+='<rect x="'+x+'" y="'+(H-26-h).toFixed(1)+'" width="36" height="'+h.toFixed(1)+'" rx="3" fill="#199e70" opacity="'+(v==mx?1:.5)+'"/>'
                    +(v==mx?'<text class="lbl" x="'+(x+18)+'" y="'+(H-26-h-12).toFixed(1)+'" text-anchor="middle">'+fmt(v)+'</text>':'')}
                    s+='<text class="tick" x="'+(x+18)+'" y="'+H+'" text-anchor="middle">'+MONTHS[i].charAt(0).toUpperCase()+'</text>'});
                  return '<svg viewBox="0 0 '+W+' '+(H+4)+'" role="img" aria-label="'+T('Average per month','Gemiddelde per maand')+'">'+s+'</svg>'}
              })();

              // 13. the end
              (function(){var i=YEARS.indexOf(Y),pv=YEARS[i-1],nx=YEARS[i+1];
                var btn='<div class="acts"><button type="button" class="main" data-act="again">'+T('Watch again','Opnieuw bekijken')+'</button>'
                  +(pv?'<button type="button" data-year="'+pv+'">'+pv+'</button>':'')+(nx?'<button type="button" data-year="'+nx+'">'+nx+'</button>':'')
                  +'<a href="index.html">'+T('Back to the overview','Terug naar het overzicht')+'</a></div>';
                var tot=many(acts.length,'activity')+', '+fmtInt(km)+'\\u00a0km '+T('and','en')+' '+many(secs/3600,'hour')+'. ';
                cards.push({c:top,label:T('The end','Het einde'),html:words(T('That was '+Y+so+'.','Dat was '+Y+so+'.'),
                  partial?T('Keep going','Doe zo voort'):T('See you out there','Tot ziens onderweg'),'',
                  tot+(partial?T('There is still some '+Y+' left.','Er schiet nog wat van '+Y+' over.'):T('On to '+(Y+1)+'.','Op naar '+(Y+1)+'.')),
                  {word:true,extra:btn})+'<div class="vis"><dl class="tiles recap">'+cards.filter(function(c){return c.fact}).map(function(c){
                    return '<div><dt>'+c.fact[0]+'</dt><dd'+(c.fact[2]?' class="w"':'')+'>'+c.fact[1]+'</dd></div>'}).join('')+'</dl></div>'})})();
              return cards;
            }

            // ---- the player: starts paused; Play runs it, about ten seconds a card, longer when there is more to read
            var stage=document.getElementById('stage'),segEl=document.getElementById('segs'),countEl=document.getElementById('count'),
                sel=document.getElementById('year'),playBtn=document.getElementById('play'),menuBtn=document.getElementById('menu'),navBox=document.getElementById('navbox');
            var year=null,cards=[],cur=0,playing=false,elapsed=0,dur=10000,holds={},last=null,pressT=0;

            // the menu: behind a button on narrower screens; the other account opens on the same year and card
            function menu(open){navBox.classList.toggle('open',open);menuBtn.setAttribute('aria-expanded',open)}
            menuBtn.onclick=function(){menu(!navBox.classList.contains('open'))};
            document.addEventListener('click',function(e){if(navBox.classList.contains('open')&&!e.target.closest('#navbox,#menu')){menu(false);e.stopPropagation();e.preventDefault()}},true);
            navBox.querySelectorAll('.site-switch a[href]:not([data-lang])').forEach(function(a){
              a.addEventListener('click',function(){a.href=a.getAttribute('href').split('#')[0]+location.hash})});

            if(!YEARS.length){stage.innerHTML='<section class="card on"><div class="inner"><div class="words"><p class="big word">'+T('No activities yet','Nog geen activiteiten')+'</p></div></div></section>';return}
            sel.innerHTML=YEARS.slice().reverse().map(function(y){return '<option value="'+y+'">'+y+'</option>'}).join('');
            function defaultYear(){var now=new Date(),cy=now.getFullYear();if(YEARS.indexOf(cy)>=0&&now.getMonth()>=3)return cy;
              var before=YEARS.filter(function(y){return y<cy});return before.length?before[before.length-1]:YEARS[YEARS.length-1]}
            function load(y,at){year=y;sel.value=y;cards=build(y);
              var CYCLE=['ride','run','walk','other'];cards.forEach(function(c,i){var p=cards[i-1];if(p&&p.c==c.c&&!c.full){var k=CYCLE.indexOf(c.c);
                for(var j=1;j<4;j++){var n=CYCLE[(k+j)%4];if(n!=p.c&&(!cards[i+1]||n!=cards[i+1].c)){c.c=n;break}}}});
              stage.innerHTML=cards.map(function(c,i){return '<section class="card'+(c.full?' full':'')+(c.cls?' '+c.cls:'')+'" aria-roledescription="'+T('slide','dia')
                +'" aria-label="'+esc(c.label)+', '+(i+1)+T(' of ',' van ')+cards.length+'" style="background:'+bg(c.c)+'"><div class="inner">'+c.html+'</div></section>'}).join('');
              document.title=T('Year in review ','Jaaroverzicht ')+y+' · '+WHO;show(at||0)}
            // reading time: ten seconds, plus a little for every word past the first twenty or so
            function readTime(el){var t='';el.querySelectorAll('.words,.vis li,.vis dt,.vis dd,.vis p').forEach(function(x){t+=' '+x.textContent});
              var n=t.split(/\\s+/).filter(Boolean).length;return Math.max(10000,Math.min(25000,4000+n*300))}
            function renderSegs(){segEl.innerHTML=cards.map(function(c,i){return '<button type="button" tabindex="-1" class="seg'+(i<cur?' done':i==cur?' now':'')
              +'" aria-label="'+esc(c.label)+'"><i></i></button>'}).join('');paint()}
            function paint(){var s=segEl.children[cur],i=s&&s.firstChild;if(!i)return;
              var k=Math.min(1,elapsed/dur);if(reduced)k=Math.min(1,Math.floor(elapsed/1000)*1000/dur);i.style.width=(k*100).toFixed(2)+'%'}
            function held(){for(var k in holds)return true;return false}
            function hold(k,on){if(on)holds[k]=1;else delete holds[k];document.body.classList.toggle('held',playing&&held())}
            function frame(t){var dt=last==null?0:Math.min(500,t-last);last=t;
              if(playing&&!held()&&cards.length){elapsed+=dt;paint();
                if(elapsed>=dur){if(cur<cards.length-1)show(cur+1);else{elapsed=dur;paint();setPlaying(false)}}}
              requestAnimationFrame(frame)}
            function countUp(el){if(reduced)return;el.querySelectorAll('.num').forEach(function(n){var v=+n.dataset.n,d=+n.dataset.d,t0=performance.now();
              (function f(t){var k=Math.min(1,(t-t0)/900),e=1-Math.pow(1-k,3);n.textContent=fmtN(v*e,d);if(k<1)requestAnimationFrame(f)})(t0)})}
            function show(i){cur=Math.max(0,Math.min(cards.length-1,i));drawToken++;elapsed=0;
              Array.prototype.forEach.call(stage.children,function(el,k){el.classList.toggle('on',k==cur);el.setAttribute('aria-hidden',k!=cur)});
              var el=stage.children[cur];dur=readTime(el);
              countEl.textContent=(cur+1)+' / '+cards.length;renderSegs();
              try{history.replaceState(null,'','#'+year+(cur?'/'+(cur+1):''))}catch(e){}
              if(cards[cur].after)cards[cur].after(el);countUp(el)}
            function next(){if(cur<cards.length-1)show(cur+1)}
            function prev(){if(cur>0)show(cur-1)}
            function setPlaying(p){playing=p;playBtn.textContent=p?T('Pause','Pauzeren'):T('Play','Afspelen');playBtn.setAttribute('aria-pressed',p);
              if(p&&cur==cards.length-1&&elapsed>=dur)show(0);hold('x',false)}

            // press and hold a card to pause it, like stories elsewhere; a short tap still steps
            stage.addEventListener('pointerdown',function(e){if(e.button>0||e.target.closest('a,button,select'))return;pressT=Date.now();hold('press',true)});
            ['pointerup','pointercancel'].forEach(function(ev){stage.addEventListener(ev,function(){hold('press',false)})});
            stage.addEventListener('pointerleave',function(){hold('press',false);hold('hover',false)});
            // resting the mouse on what a card says pauses it too
            stage.addEventListener('pointerover',function(e){if(e.pointerType=='mouse')hold('hover',!!e.target.closest('.card.on .words,.card.on .vis'))});
            stage.addEventListener('contextmenu',function(e){if(!e.target.closest('a'))e.preventDefault()});
            stage.addEventListener('click',function(e){var long=pressT&&Date.now()-pressT>450;pressT=0;
              var b=e.target.closest('[data-act],[data-year]');
              if(b){if(b.dataset.year)load(+b.dataset.year,0);else{show(0);setPlaying(true)}return}
              if(long||e.target.closest('a,button,select'))return;(e.clientX<innerWidth/2?prev:next)()});
            segEl.addEventListener('click',function(e){var b=e.target.closest('.seg');if(b)show(Array.prototype.indexOf.call(segEl.children,b))});
            document.getElementById('prev').onclick=prev;document.getElementById('next').onclick=next;
            playBtn.onclick=function(){setPlaying(!playing)};
            sel.onchange=function(){load(+sel.value,0)};
            document.addEventListener('keydown',function(e){if(e.target.closest&&e.target.closest('select,input'))return;
              if(e.key=='ArrowRight'||e.key=='PageDown'){e.preventDefault();next()}
              else if(e.key=='ArrowLeft'||e.key=='PageUp'){e.preventDefault();prev()}
              else if(e.key=='Home')show(0);else if(e.key=='End')show(cards.length-1);
              else if(e.key==' '&&!(e.target.closest&&e.target.closest('button,a'))){e.preventDefault();setPlaying(!playing)}
              else if(e.key=='Escape'){if(navBox.classList.contains('open')){menu(false);menuBtn.focus()}else location.href='index.html'}});
            document.addEventListener('visibilitychange',function(){hold('hidden',document.hidden)});
            var rt;window.addEventListener('resize',function(){clearTimeout(rt);rt=setTimeout(function(){if(cards[cur]&&cards[cur].after)cards[cur].after(stage.children[cur])},150)});
            function fromHash(){var m=/^#(\\d{4})(?:\\/(\\d+))?/.exec(location.hash);if(!m||YEARS.indexOf(+m[1])<0)return null;return {y:+m[1],i:m[2]?+m[2]-1:0}}
            window.addEventListener('hashchange',function(){var h=fromHash();if(h&&(h.y!=year||h.i!=cur)){if(h.y!=year)load(h.y,h.i);else show(h.i)}});
            var h=fromHash();setPlaying(false);
            load(h?h.y:defaultYear(),h?h.i:0);
            requestAnimationFrame(frame);
            })();
            """;
}
