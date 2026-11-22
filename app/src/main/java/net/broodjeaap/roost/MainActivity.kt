package net.broodjeaap.roost

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import net.broodjeaap.roost.data.WebViewRedditSource
import net.broodjeaap.roost.ui.AppState
import net.broodjeaap.roost.ui.LocalVideoDataSource
import net.broodjeaap.roost.ui.RoostApp
import net.broodjeaap.roost.ui.theme.RoostTheme
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // chrome://inspect access for checking Reddit's markup; debuggable builds only.
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        // Default user agent on purpose: it must stay identical between login and browsing.
        webView = WebView(this).apply {
            // AndroidView defaults to wrap_content, which makes Chromium resolve vh/dvh to 0 and
            // collapses Reddit's bottom sheets (cookie consent, login) to nothing.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        val source = WebViewRedditSource(webView, assets.open("extract.js").bufferedReader().use { it.readText() })

        // Media comes from Reddit's CDNs outside the WebView; at least send the same user agent.
        val userAgent = webView.settings.userAgentString
        val http = OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
                .crossfade(true)
                .build()
        }
        val videoDataSource = OkHttpDataSource.Factory(http)

        val state = AppState(source, lifecycleScope) { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                // No browser installed; nothing sensible to do.
            }
        }
        state.refresh()

        setContent {
            RoostTheme {
                CompositionLocalProvider(LocalVideoDataSource provides videoDataSource) {
                    RoostApp(state, webView, source)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
