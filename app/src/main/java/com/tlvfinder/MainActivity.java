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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private boolean pageReady = false;
    private String pendingText = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Your Render server URL — will be set after deploy
    private static final String SERVER_URL = BuildConfig.SERVER_URL;

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
                    processSharedText(pendingText);
                    pendingText = null;
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
        if (pageReady) processSharedText(sharedText);
        else pendingText = sharedText;
    }

    private void processSharedText(final String text) {
        setStatus("Extracting address\u2026");

        executor.execute(() -> {
            try {
                // Build JSON body
                String jsonBody;
                if (text.trim().startsWith("http")) {
                    jsonBody = "{\"url\":\"" + text.trim().replace("\"", "\\\"") + "\"}";
                } else {
                    String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
                    jsonBody = "{\"text\":\"" + safeText + "\"}";
                }

                URL url = new URL(SERVER_URL + "/extract");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();

                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String response = sb.toString();

                // Parse "address":"VALUE"
                String address = null;
                int idx = response.indexOf("\"address\":\"");
                if (idx != -1) {
                    int start = idx + 11;
                    int end = response.indexOf("\"", start);
                    if (end > start) address = response.substring(start, end).trim();
                }

                final String finalAddress = address;
                mainHandler.post(() -> {
                    if (finalAddress != null && !finalAddress.isEmpty()) {
                        String escaped = finalAddress.replace("\\", "\\\\").replace("'", "\\'");
                        webView.evaluateJavascript(
                            "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
                    } else {
                        setStatus("No address found. Try searching manually.");
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> setStatus("Server error. Try searching manually."));
            }
        });
    }

    private void setStatus(String msg) {
        String escaped = msg.replace("'", "\\'");
        webView.evaluateJavascript(
            "document.getElementById('status').textContent='" + escaped + "';", null);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
