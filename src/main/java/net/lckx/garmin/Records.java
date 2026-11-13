package net.lckx.garmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static net.lckx.garmin.DescribeGarminFile.at;
import static net.lckx.garmin.DescribeGarminFile.esc;
import static net.lckx.garmin.DescribeGarminFile.jsStr;
import static net.lckx.garmin.DescribeGarminFile.num;
import static net.lckx.garmin.DescribeGarminFile.str;

/**
 * The records page: the best ever per kind of record, the best of every year, and per year the distance and time of
 * each sport. Built from the same activities as the overview (hidden and excluded ones left out), so it agrees with it.
 */
final class Records {
    static final String PAGE_FILE = "records.html";

    private Records() {}

    static Path write() throws IOException {
        Path dir = DescribeGarminFile.dataDir(DescribeGarminFile.account());
        StringBuilder data = new StringBuilder("[");
        int n = 0;
        for (var e : DescribeGarminFile.Overview.entries()) {
            for (Object o : DescribeGarminFile.Overview.shown(e.summary())) {
                String sport = str(at(o, "sport"));
                Double d = DescribeGarminFile.Overview.distanceOf(o), dur = num(at(o, "duration"));
                boolean norec = DescribeGarminFile.Overview.NOT_A_RECORD.contains(e.date() + " " + str(at(o, "start")))
                        || sport.equals("treadmill_running");
                if (n++ > 0) data.append(',');
                data.append('[').append(jsStr(e.date().toString())).append(',')
                        .append(jsStr(e.href() + "#act" + DescribeGarminFile.Overview.sectionOf(e.summary(), o))).append(',')
                        .append(jsStr(str(at(o, "name")))).append(',').append(jsStr(DescribeGarminFile.Overview.sportGroup(sport))).append(',')
                        .append(d == null || d.isNaN() ? 0 : Math.round(d)).append(',').append(dur == null || dur.isNaN() ? 0 : Math.round(dur))
                        .append(',').append(norec ? 1 : 0).append(']');
            }
        }
        data.append(']');
        Path page = dir.resolve(PAGE_FILE);
        Path tmp = page.resolveSibling(PAGE_FILE + ".tmp");
        Files.writeString(tmp, page(data.toString().replace("</", "<\\/")), StandardCharsets.UTF_8);
        Files.move(tmp, page, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("Records: %d activities.%n", n);
        return page;
    }

    private static String page(String data) {
        String label = esc(DescribeGarminFile.accountLabelOf(DescribeGarminFile.account()));
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + DescribeGarminFile.favicon("#2563eb")
                + "<title>Records · " + label + "</title>" + Site.HEAD
                + "<style>" + DescribeGarminFile.CSS + CSS + "</style></head><body><main>" + Site.nav(PAGE_FILE, 0)
                + "<header><h1>Records</h1><p class=\"sub\" id=\"span\"></p></header>"
                + "<section class=\"card\"><h2 data-nl=\"Beste ooit\">Best ever</h2>"
                + "<p class=\"sub\" data-nl=\"Klik op een record om de activiteit te openen.\">Click a record to open the activity.</p>"
                + "<div class=\"tiles\" id=\"best\"></div></section>"
                + "<section class=\"card\"><h2 data-nl=\"De beste van elk jaar\">Each year’s best</h2>"
                + "<p class=\"sub\" data-nl=\"De beste van alle jaren is gemarkeerd. Klik op een waarde om de activiteit te openen.\">"
                + "The best of all years is marked. Click a value to open the activity.</p>"
                + "<div class=\"ranges\" id=\"yearSport\" role=\"group\" aria-label=\"Sport\"></div><div class=\"scroll\" id=\"years\"></div></section>"
                + "<section class=\"card\"><h2 data-nl=\"Afstand en tijd per jaar\">Distance and time per year</h2>"
                + "<p class=\"sub\" data-nl=\"Alles wat je per sport deed, per jaar; het grootste jaar van elke sport is gemarkeerd.\">"
                + "Everything you did per sport, year by year; each sport’s biggest year is marked.</p>"
                + "<div class=\"scroll\" id=\"totals\"></div></section>"
                + "</main><script>window.ACTS=" + data + ";</script><script>" + JS + "</script></body></html>";
    }

    private static final String CSS = """
            table.peryear td small{display:block;font-size:11.5px;color:var(--muted);font-weight:400}
            table.peryear td.total{border-left:1px solid var(--line)}
            #best .tile small{color:var(--muted)}
            #yearSport{margin:0 0 10px;display:flex;flex-wrap:wrap;gap:6px}
            #years table.peryear{font-size:14px}#years table.peryear th{font-weight:600}
            /* the totals: narrower cells, so all sports fit next to each other */
            #totals table.peryear td,#totals table.peryear th{padding:6px 5px}
            @media (max-width:700px){#totals table.peryear{font-size:12px}}
            """;

    private static final String JS = """
            (function(){
            var ACTS=window.ACTS.map(function(r){return {date:r[0],href:r[1],name:r[2],group:r[3],dist:r[4],dur:r[5],norec:r[6],
              kmh:r[4]>0&&r[5]>0?r[4]/r[5]*3.6:0};});
            function grp(n){var s=String(n),o='';while(s.length>3){o=' '+s.slice(-3)+o;s=s.slice(0,-3);}return s+o;}
            function fmtKm(m){if(m<1000)return Math.round(m)+' m';var p=(m/1000).toFixed(2).split('.');return grp(p[0])+'.'+p[1]+' km';}
            function fmtDur(s){s=Math.round(s);var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;
              return (h?h+':'+(m<10?'0':''):'')+m+':'+(x<10?'0':'')+x;}
            function fmtHours(s){var h=Math.floor(s/3600),m=Math.round(s%3600/60);if(m==60){h++;m=0;}return grp(h)+' h '+(m<10?'0':'')+m;}
            function escH(t){return String(t).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
            function longDate(d){var M=L(['January','February','March','April','May','June','July','August','September','October','November','December'],
              ['januari','februari','maart','april','mei','juni','juli','augustus','september','oktober','november','december']);
              return +d.slice(8,10)+' '+M[+d.slice(5,7)-1]+' '+d.slice(0,4);}

            // Speeds above these are GPS glitches (or a ride saved as a run), not records.
            var MAX_KMH={running:20,walking:12,swimming:8,cycling:65,indoor_cycling:70,rowing_and_paddling:20};
            function paceStr(kmh,per100){return fmtDur((per100?360:3600)/kmh)+(per100?' /100 m':' /km');}
            var RECORDS=[
              {label:'Longest run',nl:'Langste loop',h:['Run','longest','Loop','langste'],g:'running',by:'dist'},
              {label:'Fastest run, 5 km or more',nl:'Snelste loop, 5 km of meer',h:['Run','fastest ≥ 5 km','Loop','snelste ≥ 5 km'],g:'running',min:5000,by:'kmh'},
              {label:'Fastest run, 10 km or more',nl:'Snelste loop, 10 km of meer',h:['Run','fastest ≥ 10 km','Loop','snelste ≥ 10 km'],g:'running',min:10000,by:'kmh'},
              {label:'Fastest half marathon or more',nl:'Snelste halve marathon of meer',h:['Run','fastest ≥ half','Loop','snelste ≥ halve'],g:'running',min:21000,by:'kmh'},
              {label:'Fastest marathon or more',nl:'Snelste marathon of meer',h:['Run','fastest ≥ marathon','Loop','snelste ≥ marathon'],g:'running',min:42000,by:'kmh'},
              {label:'Longest ride',nl:'Langste rit',h:['Ride','longest','Rit','langste'],g:'cycling',by:'dist'},
              {label:'Fastest ride, 40 km or more',nl:'Snelste rit, 40 km of meer',h:['Ride','fastest ≥ 40 km','Rit','snelste ≥ 40 km'],g:'cycling',min:40000,by:'kmh'},
              {label:'Fastest ride, 100 km or more',nl:'Snelste rit, 100 km of meer',h:['Ride','fastest ≥ 100 km','Rit','snelste ≥ 100 km'],g:'cycling',min:100000,by:'kmh'},
              {label:'Longest indoor ride',nl:'Langste indoorrit',h:['Indoor ride','longest','Indoorrit','langste'],g:'indoor_cycling',by:'dur'},
              {label:'Longest walk or hike',nl:'Langste wandeling of tocht',h:['Walk','longest','Wandeling','langste'],g:'walking',by:'dist'},
              {label:'Longest swim',nl:'Langste zwembeurt',h:['Swim','longest','Zwemmen','langste'],g:'swimming',by:'dist'},
              {label:'Fastest swim, 1 km or more',nl:'Snelste zwembeurt, 1 km of meer',h:['Swim','fastest ≥ 1 km','Zwemmen','snelste ≥ 1 km'],g:'swimming',min:1000,by:'kmh'},
              {label:'Longest row or paddle',nl:'Langste roei- of peddeltocht',h:['Row/paddle','longest','Roeien','langste'],g:'rowing_and_paddling',by:'dist'}];
            function bestOf(rec,list){var best=null;
              list.forEach(function(x){
                if(x.norec||x.group!==rec.g||(rec.min&&x.dist<rec.min))return;
                if(rec.by==='kmh'&&(!(x.kmh>0)||x.kmh>(MAX_KMH[x.group]||999)))return;
                var v=x[rec.by];if(!(v>0))return;if(!best||v>best[rec.by])best=x;});
              return best;}
            function recText(rec,b){
              var per100=b.group==='swimming',pace=b.group==='running'||b.group==='walking'||per100;
              var sp=function(){return pace?paceStr(b.kmh,per100):b.kmh.toFixed(1)+' km/h';};
              var v=rec.by==='dist'?fmtKm(b.dist):rec.by==='dur'?fmtDur(b.dur):sp();
              var extra=rec.by==='kmh'?fmtKm(b.dist)+' in '+fmtDur(b.dur):rec.by==='dist'?fmtDur(b.dur)+(b.kmh>0?' · '+sp():''):(b.dist>0?fmtKm(b.dist):'');
              return [v,extra];}

            var by={};ACTS.forEach(function(x){var y=x.date.slice(0,4);(by[y]=by[y]||[]).push(x);});
            var years=Object.keys(by).sort().reverse();
            if(ACTS.length)document.getElementById('span').textContent=L(grp(ACTS.length)+' activities, from '+longDate(ACTS[0].date)+' (the first one recorded) to '+longDate(ACTS[ACTS.length-1].date)+'.',
              grp(ACTS.length)+' activiteiten, van '+longDate(ACTS[0].date)+' (de eerste die geregistreerd is) tot '+longDate(ACTS[ACTS.length-1].date)+'.');

            // ---- best ever
            var cols=[],tiles=document.getElementById('best');
            RECORDS.forEach(function(rec){var b=bestOf(rec,ACTS);if(!b)return;cols.push([rec,b]);var tx=recText(rec,b);
              var t=document.createElement('a');t.className='tile link';t.href=b.href;t.title=L('Open this activity','Open deze activiteit');
              t.innerHTML='<div class="v">'+escH(tx[0])+'</div><div class="l">'+escH(L(rec.label,rec.nl))+'<small><br>'+escH(b.date+' · '+b.name)+'<br>'+escH(tx[1])+'</small></div>';
              tiles.appendChild(t);});

            // ---- each year's best, one sport at a time so the table fits without scrolling sideways
            var SPORTS=[['running','Running','Hardlopen'],['cycling','Cycling','Fietsen'],['indoor_cycling','Indoor cycling','Indoor fietsen'],
              ['walking','Walking','Wandelen'],['swimming','Swimming','Zwemmen'],['rowing_and_paddling','Rowing','Roeien']]
              .filter(function(g){return cols.some(function(c){return c[0].g===g[0];});});
            var SP='running';try{SP=localStorage.getItem('garmin-records-sport')||SP;}catch(e){}
            if(!SPORTS.some(function(g){return g[0]===SP;})&&SPORTS.length)SP=SPORTS[0][0];
            var pick=document.getElementById('yearSport');
            pick.innerHTML=SPORTS.map(function(g){return '<a href="#" data-g="'+g[0]+'">'+escH(L(g[1],g[2]))+'</a>';}).join('');
            pick.onclick=function(ev){var a=ev.target.closest('a[data-g]');if(!a)return;ev.preventDefault();SP=a.dataset.g;
              try{localStorage.setItem('garmin-records-sport',SP);}catch(e){}yearTable();};
            function yearTable(){
              pick.querySelectorAll('a').forEach(function(a){a.classList.toggle('on',a.dataset.g===SP);});
              var cs=cols.filter(function(c){return c[0].g===SP;});
              var o=['<table class="peryear"><thead><tr><th>'+L('Year','Jaar')+'</th>'];
              cs.forEach(function(c){var h=c[0].h;o.push('<th title="'+escH(L(c[0].label,c[0].nl))+'">'+escH(L(h[1],h[3]))+'</th>');});
              o.push('</tr></thead><tbody>');
              years.forEach(function(y){var cells=cs.map(function(c){return bestOf(c[0],by[y]);});
                if(!cells.some(function(b){return b;}))return;
                o.push('<tr><td><b>'+y+'</b></td>');
                cells.forEach(function(b,i){if(!b){o.push('<td class="num muted">–</td>');return;}
                  var tx=recText(cs[i][0],b),top=b===cs[i][1];
                  o.push('<td class="num'+(top?' best':'')+'"><a href="'+escH(b.href)+'" title="'
                    +escH(b.date+' · '+b.name+(top?' · '+L('best of all years','beste van alle jaren'):''))+'">'+escH(tx[0])+'</a><small>'+escH(tx[1])+'</small></td>');});
                o.push('</tr>');});
              o.push('</tbody></table>');document.getElementById('years').innerHTML=o.join('');}
            yearTable();

            // ---- distance and time per year, per sport
            var GROUPS=[['running','Running','Hardlopen'],['cycling','Cycling','Fietsen'],['indoor_cycling','Indoor cycling','Indoor fietsen'],
              ['walking','Walking','Wandelen'],['swimming','Swimming','Zwemmen'],['rowing_and_paddling','Rowing and paddling','Roeien en peddelen'],['other','Other','Overige']];
            var known={};GROUPS.forEach(function(g){known[g[0]]=1;});
            function gOf(x){return known[x.group]&&x.group!=='other'?x.group:'other';}
            var sum={},used={};
            ACTS.forEach(function(x){var y=x.date.slice(0,4),g=gOf(x),k=y+' '+g,t=y+' total';used[g]=1;
              [k,t].forEach(function(key){var s=sum[key]=sum[key]||{dist:0,dur:0,n:0};s.dist+=x.dist;s.dur+=x.dur;s.n++;});});
            var gs=GROUPS.filter(function(g){return used[g[0]];}).concat([['total','Total','Totaal']]);
            var top={};gs.forEach(function(g){years.forEach(function(y){var s=sum[y+' '+g[0]];if(!s)return;
              var v=s.dist>0?s.dist:s.dur,b=top[g[0]];if(!b||v>b.v)top[g[0]]={v:v,y:y};});});
            o=['<table class="peryear"><thead><tr><th>'+L('Year','Jaar')+'</th>'];
            gs.forEach(function(g){o.push('<th'+(g[0]==='total'?' class="total"':'')+'>'+escH(L(g[1],g[2]))+'<br><small>'+L('distance · time','afstand · tijd')+'</small></th>');});
            o.push('</tr></thead><tbody>');
            years.forEach(function(y){o.push('<tr><td><b>'+y+'</b></td>');
              gs.forEach(function(g){var s=sum[y+' '+g[0]],cls='num'+(g[0]==='total'?' total':'');
                if(!s){o.push('<td class="'+cls+' muted">–</td>');return;}
                var best=top[g[0]]&&top[g[0]].y===y;
                var main=s.dist>=1000?grp(Math.round(s.dist/1000))+' km':fmtHours(s.dur);
                var small=(s.dist>=1000?fmtHours(s.dur)+' · ':'')+s.n+'×';
                o.push('<td class="'+cls+(best?' best':'')+'" title="'+escH(y+' · '+L(g[1],g[2])+': '+s.n+' '+L('activities','activiteiten')+(best?' · '+L('biggest year','grootste jaar'):''))+'">'
                  +main+'<small>'+small+'</small></td>');});
              o.push('</tr>');});
            o.push('</tbody></table>');document.getElementById('totals').innerHTML=o.join('');
            })();
            """;
}
