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
          function measure(frame) {
            try {
              const doc=frame.contentDocument;
              const newHeight=Math.max(doc.documentElement.scrollHeight,doc.body.scrollHeight,120);
              frame.style.height=(newHeight+8)+'px';
              try {
                const body=doc.body;
                if(body) {
                  body.style.fontSize='${preferences.fontSizeSp}px';
                  body.style.lineHeight='${preferences.lineHeightSp / preferences.fontSizeSp.toFloat()}';
                  body.style.padding='0 ${preferences.horizontalMarginDp}px';
                  body.style.fontFamily='${when(preferences.fontFamily) { "serif" -> "serif"; "sans" -> "sans-serif"; "mono" -> "monospace"; else -> "inherit" }}';
                  for(const p of doc.querySelectorAll('p'))p.style.marginBottom='${preferences.paragraphSpacingDp}px';
                  body.style.color='$fore';
                  body.style.backgroundColor='$back';
                }
              } catch(e) {}
              const corrected=Math.max(doc.documentElement.scrollHeight,doc.body.scrollHeight,120);
              frame.style.height=(corrected+8)+'px';
            } catch(e) { frame.style.height='1200px'; }
          }
          // Android WebView sends gestures to the iframe beneath the finger. A child
          // document can swallow vertical swipes even when its iframe is expanded to
          // its full height. Forward them to the outer, single chapter scroller.
          function attachChildScrollRelay(frame) {
            let doc;
            try { doc=frame.contentDocument; } catch(e) { return; }
            if(!doc || !doc.documentElement || doc._melaanScrollRelay) return;
            doc._melaanScrollRelay=true;
            frame.setAttribute('scrolling','no');
            doc.documentElement.style.overflowY='hidden';
            if(doc.body) doc.body.style.overflowY='hidden';

            let lastY=null;
            doc.addEventListener('touchstart',function(e) {
              lastY=e.touches.length===1 ? e.touches[0].clientY : null;
            },{passive:true});
            doc.addEventListener('touchmove',function(e) {
              if(lastY===null || e.touches.length!==1) {lastY=null;return;}
              const now=e.touches[0].clientY;
              const delta=lastY-now;
              lastY=now;
              if(Math.abs(delta)<0.5)return;
              if(e.cancelable)e.preventDefault();
              window.scrollBy(0,delta);
            },{passive:false});
            doc.addEventListener('touchend',function(){lastY=null;},{passive:true});
            doc.addEventListener('touchcancel',function(){lastY=null;},{passive:true});
            doc.addEventListener('wheel',function(e) {
              if(e.cancelable)e.preventDefault();
              window.scrollBy(e.deltaX,e.deltaY);
            },{passive:false});

            // Restore the outer viewport when a browser forcibly scrolls a child frame.
            doc.addEventListener('scroll',function() {
              const child=doc.scrollingElement;
              if(child && child.scrollTop) {
                const dy=child.scrollTop;
                child.scrollTop=0;
                window.scrollBy(0,dy);
              }
            },{passive:true});

            // Late image/font layout must expand the iframe, otherwise WebView
            // leaves a tiny internal scroll area that traps vertical swipes.
            for(const img of doc.images)img.addEventListener('load',function(){
              measure(frame);if(restored)update();
            },{passive:true});
            if(doc.fonts && doc.fonts.ready)doc.fonts.ready.then(function(){
              measure(frame);if(restored)update();
            });
            if(window.ResizeObserver && doc.body){
              const observer=new ResizeObserver(function(){
                const height=frame.offsetHeight;
                measure(frame);
                if(restored && Math.abs(frame.offsetHeight-height)>2)update();
              });
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
