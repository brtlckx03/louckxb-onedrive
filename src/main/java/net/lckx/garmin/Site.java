package net.lckx.garmin;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static net.lckx.garmin.DescribeGarminFile.esc;

/**
 * What every page shares: the menu with all pages, the account switch and the English/Dutch switch.
 * <p>Usage in a page generator:
 * <ul>
 *   <li>put {@link #HEAD} inside {@code <head>}, before the page's own scripts: it defines {@code LANG} ('en' or 'nl'),
 *       {@code L(en, nl)} for text built in JavaScript, and fills in every element with a {@code data-nl} attribute
 *       (its Dutch inner HTML) once the page has loaded; {@code data-nl-title}, {@code data-nl-aria} and
 *       {@code data-nl-placeholder} do the same for those attributes. Call {@code applyLang(el)} after inserting HTML
 *       that has {@code data-nl} attributes;</li>
 *   <li>put {@code Site.nav(PAGE_FILE, 0)} where the page's menu goes (depth = folders below the account folder,
 *       2 for the day reports in YYYY/MM/).</li>
 * </ul>
 * English is the default; the choice is kept in localStorage ("garmin-lang"), so it holds for every page, and
 * {@code ?lang=nl} in the address sets it too.
 */
final class Site {
    private Site() {}

    /** [file, English, Dutch]; the together page is added per partner. */
    private static final String[][] PAGES = {
            {"index.html", "Overview", "Overzicht"},
            {Heatmap.PAGE_FILE, "Heatmap", "Heatmap"},
            {YearsInMotion.PAGE_FILE, "Years in motion", "Jaren in beweging"},
            {YearReview.PAGE_FILE, "Year in review", "Jaaroverzicht"},
            {Explorer.PAGE_FILE, "Explorer", "Verkenner"},
            {Segments.PAGE_FILE, "Segments", "Segmenten"},
            {Records.PAGE_FILE, "Records", "Records"},
            {BestEfforts.PAGE_FILE, "Best efforts", "Beste prestaties"},
            {Fitness.PAGE_FILE, "Fitness", "Conditie"},
    };

    /** The menu: every page of this account, the accounts, and the language. */
    static String nav(String current, int depth) {
        String up = "../".repeat(depth), me = DescribeGarminFile.account();
        List<String[]> pages = new ArrayList<>(List.of(PAGES));
        for (String p : Together.partners()) {
            String label = DescribeGarminFile.accountLabelOf(p);
            pages.add(new String[]{Together.pageFile(p), "With " + label, "Met " + label});
        }
        StringBuilder h = new StringBuilder("<nav class=\"site-nav\" aria-label=\"Pages\" data-nl-aria=\"Pagina's\"><div class=\"site-pages\">");
        for (String[] p : pages) {
            boolean on = p[0].equals(current);
            h.append("<a href=\"").append(up).append(p[0]).append('"').append(on ? " aria-current=\"page\"" : "")
                    .append(" data-nl=\"").append(esc(p[2])).append("\">").append(esc(p[1])).append("</a>");
        }
        h.append("</div><div class=\"site-switch\">");
        // The same page for the other account; a day report goes to that account's overview.
        List<String> accounts = new ArrayList<>();
        accounts.add(me);
        accounts.addAll(Together.partners());
        accounts.sort(String::compareTo);
        if (accounts.size() > 1) {
            h.append("<span class=\"site-seg\" role=\"group\" aria-label=\"Account\">");
            for (String a : accounts) {
                String target;
                if (a.equals(me)) target = depth == 0 ? current : "";
                else if (depth > 0) target = "index.html";
                else if (current.startsWith("together-")) target = Together.pageFile(me);
                else target = current;
                if (a.equals(me)) {
                    h.append("<a aria-current=\"true\">").append(esc(DescribeGarminFile.accountLabelOf(a))).append("</a>");
                } else {
                    String dir = DescribeGarminFile.dataDir(a).getFileName().toString();
                    if (!Files.isDirectory(DescribeGarminFile.dataDir(a))) continue;
                    h.append("<a href=\"").append(up).append("../").append(dir).append('/').append(target).append("\">")
                            .append(esc(DescribeGarminFile.accountLabelOf(a))).append("</a>");
                }
            }
            h.append("</span>");
        }
        h.append("<span class=\"site-seg\" role=\"group\" aria-label=\"Language\" data-nl-aria=\"Taal\">")
                .append("<a href=\"#\" data-lang=\"en\" onclick=\"setLang('en');return false\">EN</a>")
                .append("<a href=\"#\" data-lang=\"nl\" onclick=\"setLang('nl');return false\">NL</a></span></div></nav>")
                // Moved to the top right away, not only when the whole page (heatmap data, a long report) has loaded.
                .append("<script>(function(n){if(n&&document.body&&n.parentNode!==document.body)document.body.insertBefore(n,document.body.firstChild)})")
                .append("(document.currentScript&&document.currentScript.previousElementSibling)</script>");
        return h.toString();
    }

    /**
     * Style for the menu. It takes its colours from the page (currentColor), so it fits the light overview and the dark
     * pages alike; a page can tune it with --site-active-bg / --site-active-fg.
     */
    private static final String CSS = """
            :root{--site-h:44px}
            html{scroll-padding-top:calc(var(--site-h) + 10px)}
            body{padding-top:var(--site-h)!important}
            /* One bar, fixed at the top, identical on every page: it is moved to the start of <body> by the script. */
            body>nav.site-nav{position:fixed!important;top:0!important;left:0!important;right:0!important;height:var(--site-h)!important;
              margin:0!important;padding:0 clamp(10px,2vw,24px)!important;border:0!important;box-sizing:border-box;z-index:10000!important;
              display:flex!important;flex-wrap:nowrap!important;align-items:center!important;justify-content:space-between!important;gap:12px!important;
              background:rgba(10,18,32,.96)!important;color:#dfe5f0!important;box-shadow:0 1px 0 rgba(255,255,255,.08),0 4px 14px rgba(0,0,0,.18);
              font:14px/1.2 system-ui,-apple-system,"Segoe UI",sans-serif!important;letter-spacing:0!important;transform:none!important}
            body>nav.site-nav .site-pages{display:flex;flex-wrap:nowrap!important;align-items:center;gap:2px;min-width:0;overflow-x:auto;scrollbar-width:none;flex:1 1 auto}
            body>nav.site-nav .site-pages::-webkit-scrollbar{display:none}
            body>nav.site-nav .site-switch{display:flex;flex:none;align-items:center;gap:6px;margin:0!important}
            body>nav.site-nav a{color:inherit!important;text-decoration:none;padding:5px 10px!important;border-radius:999px;opacity:.78;white-space:nowrap;font:inherit!important}
            body>nav.site-nav a:hover{opacity:1;background:rgba(255,255,255,.1)}
            body>nav.site-nav a[aria-current]{opacity:1;background:rgba(255,255,255,.18)!important;color:#fff!important;font-weight:600!important}
            body>nav.site-nav .site-seg{display:inline-flex;border:1px solid rgba(255,255,255,.28);border-radius:999px;padding:1px}
            body>nav.site-nav .site-seg a{padding:3px 9px!important}
            body>nav.site-nav a:focus-visible{outline:2px solid #fff;outline-offset:1px;opacity:1}
            /* before the script has moved it (or without JavaScript) */
            nav.site-nav{display:flex;flex-wrap:wrap;gap:6px;font:14px/1.3 system-ui,sans-serif}
            nav.site-nav a{color:inherit;padding:4px 8px}
            """;

    private static final String SCRIPT = """
            (function(){
              var m=/[?&]lang=(en|nl)/.exec(location.search),l=null;
              try{l=m?m[1]:localStorage.getItem('garmin-lang');if(m)localStorage.setItem('garmin-lang',m[1])}catch(e){}
              var lang=window.LANG=l=='nl'?'nl':'en';document.documentElement.lang=lang;
              // L(en, nl) translates. Leaflet also claims the global L: whenever it sets it (on load, or in noConflict),
              // its namespace is copied onto the translator, so L('…','…') and L.map(…) both keep working.
              var T=function(en,nl){return lang=='nl'&&nl!=null?nl:en};
              if(window.L&&window.L.map)Object.assign(T,window.L);
              try{Object.defineProperty(window,'L',{configurable:true,get:function(){return T},
                set:function(v){if(v&&v!==T&&typeof v=='object')Object.assign(T,v)}})}catch(e){window.L=T}
              window.setLang=function(x){try{localStorage.setItem('garmin-lang',x)}catch(e){}
                if(/[?&]lang=/.test(location.search))location.search='';else location.reload()};
              window.applyLang=function(root){root=root||document;
                root.querySelectorAll('.site-nav [data-lang]').forEach(function(a){if(a.dataset.lang==lang)a.setAttribute('aria-current','true');else a.removeAttribute('aria-current')});
                if(lang!='nl')return;
                root.querySelectorAll('[data-nl]').forEach(function(e){e.innerHTML=e.getAttribute('data-nl')});
                [['data-nl-title','title'],['data-nl-aria','aria-label'],['data-nl-placeholder','placeholder']].forEach(function(p){
                  root.querySelectorAll('['+p[0]+']').forEach(function(e){e.setAttribute(p[1],e.getAttribute(p[0]))})});
              };
              // The menu always sits in the same place: first in <body>, as the fixed bar; the current page scrolled into view.
              function bar(){var n=document.querySelector('nav.site-nav');if(n&&n.parentNode!==document.body)document.body.insertBefore(n,document.body.firstChild);
                var c=n&&n.querySelector('.site-pages [aria-current]');if(c){var p=c.parentNode;p.scrollLeft=Math.max(0,c.offsetLeft-p.clientWidth/2+c.offsetWidth/2)}}
              function ready(){bar();applyLang()}
              if(document.readyState=='loading')document.addEventListener('DOMContentLoaded',ready);else ready();
            })();
            """;

    /** Goes in {@code <head>} before the page's own scripts. */
    static final String HEAD = "<style>" + CSS + "</style><script>" + SCRIPT + "</script>";
}
