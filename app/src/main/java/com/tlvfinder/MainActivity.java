package com.tlvfinder;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private boolean pageReady = false;
    private String pendingSharedText = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingSharedText != null) {
                    processSharedText(pendingSharedText);
                    pendingSharedText = null;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type == null || !type.startsWith("text/")) return;
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText == null || sharedText.isEmpty()) return;
        if (pageReady) {
            processSharedText(sharedText);
        } else {
            pendingSharedText = sharedText;
        }
    }

    private void processSharedText(final String text) {
        setStatus("Reading post\u2026");

        // If it's a URL, fetch the page and extract text from it
        if (text.trim().startsWith("http")) {
            executor.execute(() -> {
                String pageText = fetchPageText(text.trim());
                final String extracted = pageText != null ? extractAddress(pageText) : null;
                mainHandler.post(() -> {
                    if (extracted != null && !extracted.isEmpty()) {
                        searchAddress(extracted);
                    } else {
                        setStatus("Could not extract address from post. Try searching manually.");
                    }
                });
            });
        } else {
            // Direct text — extract immediately
            String address = extractAddress(text);
            if (address != null && !address.isEmpty()) {
                searchAddress(address);
            } else {
                setStatus("No address found. Try searching manually.");
            }
        }
    }

    private String fetchPageText(String urlStr) {
        try {
            // Facebook share URLs redirect — follow redirects and get og:description / page text
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            // Pretend to be a browser so Facebook returns HTML
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Language", "he,en;q=0.9");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int maxLines = 300;
            while ((line = br.readLine()) != null && maxLines-- > 0) {
                sb.append(line).append("\n");
            }
            br.close();

            String html = sb.toString();

            // Try og:description first (most reliable)
            Pattern ogDesc = Pattern.compile("og:description\"[^>]*content=\"([^\"]{10,500})\"", Pattern.CASE_INSENSITIVE);
            Matcher m = ogDesc.matcher(html);
            if (m.find()) return m.group(1);

            // Try meta description
            Pattern metaDesc = Pattern.compile("<meta[^>]*name=\"description\"[^>]*content=\"([^\"]{10,500})\"", Pattern.CASE_INSENSITIVE);
            Matcher m2 = metaDesc.matcher(html);
            if (m2.find()) return m2.group(1);

            // Return raw HTML for keyword scanning as last resort
            return html.length() > 3000 ? html.substring(0, 3000) : html;

        } catch (Exception e) {
            return null;
        }
    }

    private void searchAddress(String address) {
        String escaped = address.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
        webView.evaluateJavascript(
            "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
    }

    private void setStatus(String msg) {
        String escaped = msg.replace("'", "\\'");
        webView.evaluateJavascript(
            "document.getElementById('status').textContent='" + escaped + "';", null);
    }

    private String extractAddress(String text) {
        String[] keywords = {
            "רחוב ",
            "רח' ",
            "שדרות ",
            "סמטת ",
            " on ",
            " at ",
            " in "
        };
        List<String> cityWords = Arrays.asList("תל", "אביב", "tel", "aviv", "israel", "ישראל");

        for (String kw : keywords) {
            int idx = text.indexOf(kw);
            if (idx == -1) idx = text.toLowerCase().indexOf(kw.toLowerCase());
            if (idx == -1) continue;

            String after = text.substring(idx + kw.length()).trim();
            String chunk = after.split("[\n\\(\\-]")[0].trim();
            String[] allWords = chunk.split("\\s+");
            int take = Math.min(3, allWords.length);

            while (take > 0 && cityWords.contains(allWords[take - 1].toLowerCase().replaceAll("[,.]", ""))) {
                take--;
            }
            if (take == 0) continue;

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < take; i++) {
                if (i > 0) result.append(" ");
                result.append(allWords[i]);
            }
            String finalResult = result.toString().replaceAll("[,\\.]+$", "").trim();
            if (finalResult.length() > 1) return finalResult;
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
