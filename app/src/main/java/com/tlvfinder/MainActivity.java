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
            "document.getElementById('status').textContent='Reading shared post…';", null);

        final String apiKey = BuildConfig.CLAUDE_API_KEY;
        final String inputText = text;

        executor.execute(() -> {
            // Try Claude API first
            String address = extractAddressFromClaude(apiKey, inputText);

            // Fallback: regex extraction
            if (address == null || address.equals("NOT_FOUND") || address.isEmpty()) {
                address = extractAddressWithRegex(inputText);
            }

            final String finalAddress = address;
            mainHandler.post(() -> {
                webView.evaluateJavascript(
                    "document.getElementById('extracting-banner').style.display='none';", null);
                if (finalAddress != null && !finalAddress.isEmpty()) {
                    String escaped = finalAddress.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
                    webView.evaluateJavascript(
                        "document.getElementById('search-input').value='" + escaped + "'; doSearch();", null);
                } else {
                    webView.evaluateJavascript(
                        "document.getElementById('status').textContent='No address found. Try searching manually.';", null);
                }
            });
        });
    }

    // Regex fallback: extract street names from Hebrew or English text
    private String extractAddressWithRegex(String text) {
        // English: "on X Street", "at X Street", "X Street", "X Ave", "X Blvd", "X Road"
        Pattern englishStreet = Pattern.compile(
            "(?:on|at|in)?\\s*([A-Z][a-zA-Z\\s]{2,30}(?:Street|St|Avenue|Ave|Boulevard|Blvd|Road|Rd|Lane|Ln|Drive|Dr))",
            Pattern.CASE_INSENSITIVE);
        Matcher em = englishStreet.matcher(text);
        if (em.find()) return em.group(1).trim();

        // Hebrew: רחוב X or just a Hebrew word followed by common street context
        // Look for "רחוב" (street) followed by Hebrew word
        Pattern hebrewStreet = Pattern.compile("רחוב\\s+([\\u0590-\\u05FF\\s\\'\"]{2,30})");
        Matcher hm = hebrewStreet.matcher(text);
        if (hm.find()) return hm.group(1).trim();

        // Look for "ברחוב" or "בשדרות" or "בסמטת"
        Pattern hebrewIn = Pattern.compile("[בב][רשס][חד][ו][ב\\u05D1]\\s+([\\u0590-\\u05FF\\s]{2,30})");
        Matcher him = hebrewIn.matcher(text);
        if (him.find()) return him.group(1).trim();

        // Hebrew: look for "street, Tel Aviv" pattern — word before "תל אביב"
        Pattern beforeTLV = Pattern.compile("([\\u0590-\\u05FF]{2,20})(?:\\s*,\\s*תל אביב)");
        Matcher btm = beforeTLV.matcher(text);
        if (btm.find()) return btm.group(1).trim();

        // English: "X Street (between..." — common in rental posts
        Pattern betweenPattern = Pattern.compile("([A-Z][a-zA-Z\\s]{2,20})\\s*(?:Street)?\\s*\\(between");
        Matcher bpm = betweenPattern.matcher(text);
        if (bpm.find()) return bpm.group(1).trim() + " Street";

        return null;
    }

    private String extractAddressFromClaude(String apiKey, String text) {
        try {
            if (apiKey == null || apiKey.isEmpty() || apiKey.equals("null")) return null;

            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            String safeText = text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", " ");
            if (safeText.length() > 1000) safeText = safeText.substring(0, 1000);

            String body = "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":50,\"messages\":[{\"role\":\"user\",\"content\":\"Extract only the street name or address from this apartment listing text. Reply with just the street name, nothing else. If no address found reply NOT_FOUND.\\n\\nText: " + safeText + "\"}]}";

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
