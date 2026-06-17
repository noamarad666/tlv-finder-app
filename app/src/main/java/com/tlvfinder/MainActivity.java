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

import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private void processSharedText(String text) {
        // Show extracting banner in JS
        webView.evaluateJavascript(
            "document.getElementById('extracting-banner').style.display='block';" +
            "document.getElementById('status').textContent='Reading shared post…';", null);

        final String apiKey = BuildConfig.CLAUDE_API_KEY;
        final String inputText = text;

        executor.execute(() -> {
            String address = extractAddressFromClaude(apiKey, inputText);
            mainHandler.post(() -> {
                webView.evaluateJavascript(
                    "document.getElementById('extracting-banner').style.display='none';", null);
                if (address != null && !address.equals("NOT_FOUND") && !address.isEmpty()) {
                    String escaped = address.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
                    webView.evaluateJavascript(
                        "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
                } else {
                    webView.evaluateJavascript(
                        "document.getElementById('status').textContent='No address found in post. Try searching manually.';", null);
                }
            });
        });
    }

    private String extractAddressFromClaude(String apiKey, String text) {
        try {
            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            // Escape text for JSON
            String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            String body = "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"Extract only the street name or address from this text. Reply with just the street name or address in the original language, nothing else. If no address found reply NOT_FOUND.\\n\\nText: " + safeText + "\"}]}";

            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String response = sb.toString();
            // Simple JSON parse for "text":"..."
            int idx = response.indexOf("\"text\":");
            if (idx == -1) return null;
            int start = response.indexOf("\"", idx + 7) + 1;
            int end = response.indexOf("\"", start);
            if (start <= 0 || end <= start) return null;
            return response.substring(start, end).replace("\\n", " ").trim();

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
