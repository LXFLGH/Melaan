package com.melavonmen.an.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.DocumentProgress
import com.melavonmen.an.data.DocumentReaderPreferences
import com.melavonmen.an.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Continuous EPUB HTML without concatenating the entire book. A single WebView hosts
 * sandboxed XHTML iframes for current/adjacent spine entries, with a sliding
 * five-chapter DOM window. Images/styles/fonts retain their original base URLs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SeamlessEpubReader(
    entry: ComicEntry,
    initialChapter: Int,
    initialUnits: Int,
    requestedChapter: Int,
    requestedUnits: Int,
    seekToken: Int,
    preferences: DocumentReaderPreferences,
    onPositionChange: (Int, Int) -> Unit,
    onLinkChapter: (Int) -> Unit,
    onReaderTap: () -> Unit,
) {
    val context = LocalContext.current
    val positionCallback = rememberUpdatedState(onPositionChange)
    val chapterCallback = rememberUpdatedState(onLinkChapter)
    val tapCallback = rememberUpdatedState(onReaderTap)
    val activeChapter = rememberUpdatedState(requestedChapter)
    val urls = remember(entry.id) {
        entry.chapters.map { chapter ->
            val path = chapter.pageEntryPaths.firstOrNull().orEmpty()
            "https://melaan.epub/" + path.split('/').joinToString("/") { Uri.encode(it) }
        }
    }
    val bridge = remember(entry.id) {
        SeamlessEpubBridge { chapter, units -> positionCallback.value(chapter, units) }
    }
    val view = remember(context, entry.id) {
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true // Only app-authored, CSP-nonced parent script executes.
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            isVerticalScrollBarEnabled = false
            val tapDetector = android.view.GestureDetector(context,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: android.view.MotionEvent): Boolean = true
                    override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                        tapCallback.value()
                        return false
                    }
                },
            )
            // Let WebView deliver vertical swipes and links normally; only observe single taps.
            setOnTouchListener { _, event -> tapDetector.onTouchEvent(event); false }
            addJavascriptInterface(bridge, "MelaanProgress")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (!isSafeEpubUri(uri)) return true
                    // A newly created sandbox iframe must be allowed to load its own chapter.
                    // Only user-initiated navigation is routed to the book-level chapter state.
                    if (!request.isForMainFrame && !request.hasGesture()) return false
                    val path = uri.path?.trimStart('/')
                    val target = entry.chapters.indexOfFirst { it.pageEntryPaths.firstOrNull() == path }
                    if (target >= 0) {
                        // Keep same-XHTML footnotes and anchors within their sandboxed frame.
                        if (target == activeChapter.value && uri.fragment != null) return false
                        chapterCallback.value(target)
                        return true
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (!isSafeEpubUri(url)) return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                    val path = url.path?.trimStart('/') ?: return null
                    val bytes = runBlocking {
                        runCatching { LibraryRepository.readResourceBytes(entry.id, path) }.getOrNull()
                    } ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    val mime = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "xhtml", "html", "htm" -> "application/xhtml+xml"
                        "css" -> "text/css"
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "svg" -> "image/svg+xml"
                        "woff" -> "font/woff"
                        "woff2" -> "font/woff2"
                        "ttf" -> "font/ttf"
                        "otf" -> "font/otf"
                        else -> "application/octet-stream"
                    }
                    return WebResourceResponse(
                        mime,
                        if (mime.startsWith("text/") || mime == "application/xhtml+xml") "utf-8" else null,
                        ByteArrayInputStream(bytes),
                    )
                }
            }
        }
    }
    val html = remember(entry.id, preferences.fontSizeSp, preferences.lineHeightSp, preferences.paragraphSpacingDp, preferences.fontFamily, preferences.horizontalMarginDp, preferences.nightMode) {
        // Escape HTML delimiters so an EPUB filename cannot terminate this trusted script.
        val bookUrls = JSONArray(urls).toString()
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
        val fore = if (preferences.nightMode) "#e9e1d5" else "#202127"
        val back = if (preferences.nightMode) "#14171b" else "#ffffff"
        // Script is authored by the app; untrusted EPUB XHTML runs inside sandboxed iframes.
        """
        <!doctype html><html lang="zh"><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=3">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://melaan.epub; style-src 'unsafe-inline'; script-src 'nonce-melaan';">
        <style>html,body{margin:0;padding:0;background:$back;color:$fore;overflow-x:hidden}
          iframe{width:100%;border:0;display:block;min-height:120px;overflow:hidden}
          .chapter{padding:0;margin:0}
        </style></head><body><main id="chapters"></main>
        <script nonce="melaan">
        (function(){
          const urls=$bookUrls;
          let target=${initialChapter.coerceIn(0, (urls.size-1).coerceAtLeast(0))};
          let restoreUnits=${initialUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS)};
          const root=document.getElementById('chapters');
          const frames=new Map();
          let first=Math.max(0,target-1),last=Math.min(urls.length-1,target+1);
          let active=target,restored=false,scheduled=false;
          // A single RAF per frame remeasurement: never re-apply all paragraph styles
          // from a ResizeObserver callback (that previously caused layout thrashing).
          let kineticFrame=0;
          function cancelKinetic() {
            if(kineticFrame)cancelAnimationFrame(kineticFrame);
            kineticFrame=0;
          }
          function applyReaderStyles(frame) {
            let doc;
            try { doc=frame.contentDocument; } catch(e) { return; }
            if(!doc || !doc.body || doc.body.dataset.melaanStyled==='1')return;
            const body=doc.body;
            body.dataset.melaanStyled='1';
            body.style.fontSize='${preferences.fontSizeSp}px';
            body.style.lineHeight='${preferences.lineHeightSp / preferences.fontSizeSp.toFloat()}';
            body.style.padding='0 ${preferences.horizontalMarginDp}px';
            body.style.fontFamily='${when(preferences.fontFamily) { "serif" -> "serif"; "sans" -> "sans-serif"; "mono" -> "monospace"; else -> "inherit" }}';
            body.style.color='$fore';
            body.style.backgroundColor='$back';
            body.style.overflowY='hidden';
            body.style.touchAction='none';
            doc.documentElement.style.overflowY='hidden';
            doc.documentElement.style.touchAction='none';
            for(const paragraph of doc.querySelectorAll('p')) {
              paragraph.style.marginBottom='${preferences.paragraphSpacingDp}px';
            }
          }
          function measure(frame) {
            try {
              const doc=frame.contentDocument;
              if(!doc || !doc.documentElement)return;
              const body=doc.body;
              const height=Math.ceil(Math.max(120,
                doc.documentElement.scrollHeight,
                body ? body.scrollHeight : 0));
              if(Math.abs(frame.offsetHeight-height)>2)frame.style.height=height+'px';
            } catch(e) {
              if(frame.offsetHeight<120)frame.style.height='120px';
            }
          }
          function scheduleMeasure(frame) {
            if(frame._measureFrame)return;
            frame._measureFrame=requestAnimationFrame(function(){
              frame._measureFrame=0;
              const oldHeight=frame.offsetHeight;
              measure(frame);
              if(restored && Math.abs(oldHeight-frame.offsetHeight)>2)update();
            });
          }
          // Nested iframes intercept Android WebView touches. Use a captured
          // single-finger gesture relay with coalesced scroll and kinetic continuation.
          // Short taps are not consumed, so links and reader UI tapping still work.
          function attachChildScrollRelay(frame) {
            let doc;
            try { doc=frame.contentDocument; } catch(e) { return; }
            if(!doc || !doc.documentElement || doc._melaanScrollRelay)return;
            doc._melaanScrollRelay=true;
            frame.setAttribute('scrolling','no');
            doc.documentElement.style.overflowY='hidden';
            if(doc.body)doc.body.style.overflowY='hidden';

            let lastY=null,lastAt=0,velocity=0,dragged=false;
            let queuedDelta=0,scrollFrame=0;
            function flushScroll() {
              scrollFrame=0;
              if(queuedDelta!==0)window.scrollBy(0,queuedDelta);
              queuedDelta=0;
            }
            function scrollBy(delta) {
              queuedDelta+=delta;
              if(!scrollFrame)scrollFrame=requestAnimationFrame(flushScroll);
            }
            function fling() {
              cancelKinetic();
              if(!dragged || Math.abs(velocity)<0.06)return;
              let speed=velocity;
              let prev=performance.now();
              function tick(now) {
                const dt=Math.min(Math.max(now-prev,1),32);
                prev=now;
                speed*=Math.pow(0.91,dt/16);
                if(Math.abs(speed)<0.018){kineticFrame=0;return;}
                const before=window.scrollY;
                window.scrollBy(0,speed*dt);
                if(Math.abs(window.scrollY-before)<0.5){kineticFrame=0;return;}
                kineticFrame=requestAnimationFrame(tick);
              }
              kineticFrame=requestAnimationFrame(tick);
            }
            doc.addEventListener('touchstart',function(e) {
              cancelKinetic();
              if(e.touches.length===1) {
                lastY=e.touches[0].clientY;
                lastAt=performance.now();
                velocity=0;dragged=false;
              }else lastY=null;
            },{capture:true,passive:true});
            doc.addEventListener('touchmove',function(e) {
              if(lastY===null || e.touches.length!==1){lastY=null;return;}
              const y=e.touches[0].clientY;
              const delta=lastY-y;
              const now=performance.now();
              const elapsed=Math.max(1,now-lastAt);
              lastY=y;lastAt=now;
              if(Math.abs(delta)<0.2)return;
              dragged=true;
              if(e.cancelable)e.preventDefault();
              // Capture before EPUB-side listeners can cancel the gesture.
              if(e.stopImmediatePropagation)e.stopImmediatePropagation();
              velocity=velocity*0.65+Math.max(-3,Math.min(3,delta/elapsed))*0.35;
              scrollBy(delta);
            },{capture:true,passive:false});
            doc.addEventListener('touchend',function(e){
              if(scrollFrame){cancelAnimationFrame(scrollFrame);flushScroll();}
              fling();
              lastY=null;
            },{capture:true,passive:true});
            doc.addEventListener('touchcancel',function(){
              if(scrollFrame){cancelAnimationFrame(scrollFrame);flushScroll();}
              cancelKinetic();lastY=null;
            },{capture:true,passive:true});
            doc.addEventListener('wheel',function(e){
              if(e.cancelable)e.preventDefault();
              scrollBy(e.deltaY);
            },{capture:true,passive:false});

            // Some EPUB CSS triggers a child scroll despite overflow:hidden.
            doc.addEventListener('scroll',function(){
              const child=doc.scrollingElement;
              if(child && child.scrollTop){
                const delta=child.scrollTop;
                child.scrollTop=0;
                scrollBy(delta);
              }
            },{passive:true});

            for(const img of doc.images)img.addEventListener('load',function(){
              scheduleMeasure(frame);
            },{passive:true});
            if(doc.fonts && doc.fonts.ready)doc.fonts.ready.then(function(){
              scheduleMeasure(frame);
            });
            if(window.ResizeObserver && doc.body){
              const observer=new ResizeObserver(function(){scheduleMeasure(frame);});
              observer.observe(doc.body);
              frame._melaanObserver=observer;
            }
          }
          function create(index,prepend) {
            if(index<0||index>=urls.length||frames.has(index))return;
            const frame=document.createElement('iframe');
            frame.setAttribute('sandbox','allow-same-origin');
            frame.setAttribute('scrolling','no');
            frame.src=urls[index];
            frame.dataset.chapter=String(index);
            frame.loading='eager';
            frame.addEventListener('load',function(){
              const original=frame.offsetHeight;
              applyReaderStyles(frame);
              measure(frame);
              attachChildScrollRelay(frame);
              if(prepend&&restored&&index<active)window.scrollBy(0,frame.offsetHeight-original);
              if(index===target&&!restored) {
                setTimeout(function(){
                  measure(frame);
                  const usable=Math.max(0,frame.offsetHeight-innerHeight);
                  window.scrollTo(0,frame.offsetTop+(restoreUnits/10000)*usable);
                  restored=true; update();
                },150);
              }
              if(restored) update();
            });
            frames.set(index,frame);
            if(prepend)root.insertBefore(frame,root.firstChild);
            else root.appendChild(frame);
          }
          for(let i=first;i<=last;i++)create(i,false);
          function update() {
            if(!restored) return;
            let selected=active;
            for(let i=first;i<=last;i++){
              const f=frames.get(i);
              if(f&&f.getBoundingClientRect().bottom>0){selected=i;break;}
            }
            active=selected;
            const frame=frames.get(active);
            if(frame){
              const rect=frame.getBoundingClientRect();
              const denominator=Math.max(1,rect.height-innerHeight);
              const units=Math.max(0,Math.min(10000,Math.round(-rect.top/denominator*10000)));
              try{MelaanProgress.onPosition(active,units);}catch(e){}
            }
            const tail=frames.get(last);
            if(last<urls.length-1&&tail&&tail.getBoundingClientRect().bottom<innerHeight*2.2) {
              last++;create(last,false);
            }
            const head=frames.get(first);
            if(first>0&&head&&head.getBoundingClientRect().top>-innerHeight*1.2) {
              first--;create(first,true);
            }
            while(frames.size>5) {
              const h=frames.get(first),t=frames.get(last);
              if(h&&h.getBoundingClientRect().bottom < -innerHeight*1.5) {
                const height=h.offsetHeight;
                if(h._melaanObserver)h._melaanObserver.disconnect();
                h.remove();frames.delete(first);first++;
                window.scrollBy(0,-height);
              }else if(t&&t.getBoundingClientRect().top>innerHeight*3) {
                if(t._melaanObserver)t._melaanObserver.disconnect();
                t.remove();frames.delete(last);last--;
              }else break;
            }
          }
          window.melaanSeek=function(index,units){
            if(index<0||index>=urls.length)return;
            // reset bounded window to requested chapter; preserve navigation semantics
            for(const old of frames.values())if(old._melaanObserver)old._melaanObserver.disconnect();
            root.replaceChildren();frames.clear();
            first=Math.max(0,index-1);last=Math.min(urls.length-1,index+1);
            active=index;target=index;restoreUnits=units;restored=false;
            for(let i=first;i<=last;i++)create(i,false);
            // create() restores target after its frame loads, including new seek units.
          };
          window.addEventListener('scroll',function(){
            if(scheduled)return;scheduled=true;
            requestAnimationFrame(function(){scheduled=false;update();});
          },{passive:true});
          window.addEventListener('resize',update);
        })();
        </script></body></html>
        """.trimIndent()
    }
    LaunchedEffect(view, html) {
        view.loadDataWithBaseURL("https://melaan.epub/_reader.html", html, "text/html", "UTF-8", null)
    }
    LaunchedEffect(view, seekToken) {
        if (seekToken > 0) {
            view.evaluateJavascript(
                "window.melaanSeek && window.melaanSeek(${requestedChapter.coerceIn(0, (entry.chapters.size-1).coerceAtLeast(0))},${requestedUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS)});",
                null,
            )
        }
    }
    DisposableEffect(view) {
        onDispose {
            view.stopLoading()
            view.removeJavascriptInterface("MelaanProgress")
            view.destroy()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

private class SeamlessEpubBridge(private val onPosition: (Int, Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPosition(chapter: Int, progressUnits: Int) {
        handler.post { onPosition(chapter, progressUnits.coerceIn(0, DocumentProgress.SCROLL_UNITS)) }
    }
}
