package com.tlvfinder;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private boolean pageReady = false;
    private String pendingSharedText = null;

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

    private void processSharedText(String text) {
        String address = extractAddress(text);
        if (address != null && !address.isEmpty()) {
            String escaped = address.replace("\\", "\\\\").replace("'", "\\'");
            webView.evaluateJavascript(
                "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
        } else {
            webView.evaluateJavascript(
                "document.getElementById('status').textContent='No address found. Try searching manually.';", null);
        }
    }

    private String extractAddress(String text) {
        String[] keywords = {"\u05E8\u05D7\u05D5\u05D1", "\u05E8\u05D7'", "\u05E9\u05D3\u05E8\u05D5\u05EA", "\u05E1\u05DE\u05D8\u05EA", " on ", " at ", " in "};
        List<String> cityWords = Arrays.asList("תל", "אביב", "tel", "aviv", "israel", "ישראל");

        for (String kw : keywords) {
            int idx = text.toLowerCase().indexOf(kw.toLowerCase());
            if (idx == -1) continue;

            String after = text.substring(idx + kw.length()).trim();

            // Split on newline, dash, or open paren
            String chunk = after.split("[\n\\(\\-]")[0].trim();

            // Take up to 3 words
            String[] allWords = chunk.split("\\s+");
            int take = Math.min(3, allWords.length);
            String[] words = Arrays.copyOf(allWords, take);

            // Drop trailing city names
            while (take > 0 && cityWords.contains(words[take - 1].toLowerCase())) {
                take--;
            }

            if (take == 0) continue;

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < take; i++) {
                if (i > 0) result.append(" ");
                result.append(words[i]);
            }

            // Strip trailing punctuation
            String final_ = result.toString().replaceAll("[,\\.]+$", "").trim();
            if (final_.length() > 1) return final_;
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
