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

    private void processSharedText(String text) {
        webView.evaluateJavascript(
            "document.getElementById('extracting-banner').style.display='block';" +
            "document.getElementById('status').textContent='Reading shared post\u2026';", null);

        final String apiKey = BuildConfig.CLAUDE_API_KEY;
        final String inputText = text;

        executor.execute(() -> {
            String address = extractAddressFromClaude(apiKey, inputText);
            if (address == null || address.equals("NOT_FOUND") || address.isEmpty()) {
                address = extractAddressWithRegex(inputText);
            }

            final String finalAddress = address;
            mainHandler.post(() -> {
                webView.evaluateJavascript(
                    "document.getElementById('extracting-banner').style.display='none';", null);
                if (finalAddress != null && !finalAddress.isEmpty()) {
                    String escaped = finalAddress
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", " ")
                        .replace("\r", "");
                    webView.evaluateJavascript(
                        "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
                } else {
                    webView.evaluateJavascript(
                        "document.getElementById('status').textContent='No address found. Try searching manually.';", null);
                }
            });
        });
    }

    private String extractAddressWithRegex(String text) {
        // 1. English: "on/at X Street/Ave/Road" (most common in English posts)
        Pattern p1 = Pattern.compile(
            "(?:on|at)\\s+([A-Za-z][a-zA-Z'.\\s]{1,25}?\\s+(?:Street|St|Avenue|Ave|Boulevard|Blvd|Road|Rd|Lane|Drive|Dr))",
            Pattern.CASE_INSENSITIVE);
        Matcher m1 = p1.matcher(text);
        if (m1.find()) return m1.group(1).trim();

        // 2. English: "X Street (between Y and Z)"
        Pattern p2 = Pattern.compile(
            "([A-Za-z][a-zA-Z'.\\s]{1,25}?)\\s+[Ss]treet\\s*\\(",
            Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(text);
        if (m2.find()) return m2.group(1).trim() + " Street";

        // 3. Hebrew: רחוב + street name (stop at dash, comma, newline, or end)
        Pattern p3 = Pattern.compile(
            "\u05E8\u05D7\u05D5\u05D1\\s+([\\u0590-\\u05FF\\s'.\"]+?)(?:\\s*[-,\\n]|$)");
        Matcher m3 = p3.matcher(text);
        if (m3.find()) return m3.group(1).trim();

        // 4. Hebrew: ברחוב / בשדרות
        Pattern p4 = Pattern.compile(
            "[\u05D1][\u05E8\u05E9][\u05D7\u05D3][\u05D5][\u05D1\\u05D1]\\s+([\\u0590-\\u05FF\\s'.]+?)(?:\\s*[-,\\n]|$)");
        Matcher m4 = p4.matcher(text);
        if (m4.find()) return m4.group(1).trim();

        // 5. Hebrew: word before ,תל אביב
        Pattern p5 = Pattern.compile(
            "([\\u0590-\\u05FF'.]{2,20})(?:\\s*,\\s*\u05EA\u05DC[ -]?\u05D0\u05D1\u05D9\u05D1)");
        Matcher m5 = p5.matcher(text);
        if (m5.find()) return m5.group(1).trim();

        return null;
    }

    private String extractAddressFromClaude(String apiKey, String text) {
        try {
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("null") || apiKey.equals("\"\"")) return null;

            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            String safeText = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", " ");
            if (safeText.length() > 800) safeText = safeText.substring(0, 800);

            String body = "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":50,"
                + "\"messages\":[{\"role\":\"user\",\"content\":"
                + "\"Extract only the street name from this apartment listing. Reply with just the street name only, nothing else. If none found reply NOT_FOUND.\\n\\n"
                + safeText + "\"}]}";

            byte[] bodyBytes = body.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.close();

            int code = conn.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String response = sb.toString();
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
