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

        // Try both EXTRA_TEXT and EXTRA_SUBJECT
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        String sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        String combined = "";
        if (sharedSubject != null) combined += sharedSubject + " ";
        if (sharedText != null) combined += sharedText;
        combined = combined.trim();

        if (combined.isEmpty()) return;
        if (pageReady) {
            processSharedText(combined);
        } else {
            pendingSharedText = combined;
        }
    }

    private void processSharedText(String text) {
        // Show what we received in the status bar for debugging
        String preview = text.length() > 60 ? text.substring(0, 60) : text;
        String escapedPreview = preview.replace("\\", "").replace("'", "").replace("\n", " ").replace("\"", "");
        webView.evaluateJavascript(
            "document.getElementById('status').textContent='Received: " + escapedPreview + "';", null);

        String address = extractAddress(text);
        if (address != null && !address.isEmpty()) {
            String escaped = address.replace("\\", "\\\\").replace("'", "\\'");
            webView.evaluateJavascript(
                "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
        }
        // If no address found, status bar already shows what was received — user can type manually
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
