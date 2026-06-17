package com.tlvfinder;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
                    handleText(pendingText);
                    pendingText = null;
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        String clipboard = getClipboardText();
        if (clipboard != null && !clipboard.isEmpty() && !clipboard.startsWith("http")) {
            if (pageReady) handleText(clipboard);
            else pendingText = clipboard;
        }
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

    private void handleText(String text) {
        String address = extractAddress(text);
        if (address != null && !address.isEmpty()) {
            // Put ONLY the extracted address in the search box
            String escaped = address.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
            webView.evaluateJavascript(
                "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
        }
        // If no address found, do nothing — don't put garbage in the search box
    }

    private String extractAddress(String text) {
        String[] keywords = { "רחוב ", "רח' ", "שדרות ", "סמטת ", " on ", " at " };
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
