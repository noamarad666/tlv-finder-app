package com.tlvfinder;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private boolean pageReady = false;
    private String pendingText = null;

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
                if (pendingText != null) {
                    processText(pendingText);
                    pendingText = null;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        // Check for shared intent first, then clipboard
        String sharedText = getSharedText(getIntent());
        if (sharedText != null && !sharedText.isEmpty()) {
            pendingText = sharedText;
        } else {
            String clipboard = getClipboardText();
            if (clipboard != null && !clipboard.isEmpty() && !clipboard.startsWith("http")) {
                pendingText = clipboard;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String sharedText = getSharedText(intent);
        if (sharedText != null && !sharedText.isEmpty()) {
            if (pageReady) processText(sharedText);
            else pendingText = sharedText;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Each time app comes to foreground, check clipboard
        if (pageReady) {
            String clipboard = getClipboardText();
            if (clipboard != null && !clipboard.isEmpty() && !clipboard.startsWith("http")) {
                processText(clipboard);
            }
        }
    }

    private String getSharedText(Intent intent) {
        if (intent == null) return null;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String type = intent.getType();
        if (type == null || !type.startsWith("text/")) return null;
        return intent.getStringExtra(Intent.EXTRA_TEXT);
    }

    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            if (item == null) return null;
            CharSequence text = item.getText();
            return text != null ? text.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void processText(String text) {
        String address = extractAddress(text);
        if (address != null && !address.isEmpty()) {
            String escaped = address.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
            webView.evaluateJavascript(
                "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
        } else {
            String preview = text.length() > 60 ? text.substring(0, 60) : text;
            String escapedPreview = preview.replace("\\", "").replace("'", "").replace("\n", " ").replace("\"", "");
            webView.evaluateJavascript(
                "document.getElementById('status').textContent='No address found in: " + escapedPreview + "';", null);
        }
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
