package com.deutschaktiv.worttrainer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_FILE = 4101;
    private static final int REQ_SAVE_FILE = 4102;
    private static final int REQ_AUDIO = 4103;
    private static final long SILENCE_LIMIT_MS = 50_000L;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private byte[] pendingSaveBytes;
    private String pendingSaveName = "export.dat";
    private String pendingSaveMime = "application/octet-stream";

    private SpeechRecognizer speechRecognizer;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private boolean speechSessionActive = false;
    private boolean pendingSpeechAfterPermission = false;
    private long lastVoiceAt = 0L;

    private final Runnable silenceStop = new Runnable() {
        @Override public void run() {
            if (!speechSessionActive) return;
            long elapsed = SystemClock.elapsedRealtime() - lastVoiceAt;
            if (elapsed >= SILENCE_LIMIT_MS) {
                stopSpeechSession("Automatisch gestoppt: 50 Sekunden Pause.", true);
            } else {
                speechHandler.postDelayed(this, SILENCE_LIMIT_MS - elapsed);
            }
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        webView.addJavascriptInterface(new AndroidSpeechBridge(), "AndroidSpeech");
        webView.addJavascriptInterface(new AndroidFilesBridge(), "AndroidFiles");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "*/*";
                String[] accepts = params != null ? params.getAcceptTypes() : null;
                if (accepts != null && accepts.length > 0) {
                    boolean onlyImages = true;
                    boolean hasValue = false;
                    for (String a : accepts) {
                        if (a == null || a.trim().isEmpty()) continue;
                        hasValue = true;
                        if (!a.toLowerCase(Locale.ROOT).startsWith("image/")) onlyImages = false;
                    }
                    if (hasValue && onlyImages) type = "image/*";
                }
                intent.setType(type);
                try {
                    startActivityForResult(intent, REQ_OPEN_FILE);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Dateiauswahl konnte nicht geöffnet werden.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == REQ_SAVE_FILE) {
            boolean ok = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) {
                        out.write(pendingSaveBytes);
                        out.flush();
                        ok = true;
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Speichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            final boolean saved = ok;
            final String savedName = pendingSaveName;
            pendingSaveBytes = null;
            sendJs("window.onNativeFileSaved && window.onNativeFileSaved(" + saved + "," + JSONObject.quote(savedName) + ")");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingSpeechAfterPermission) startSpeechSession();
            else sendJs("window.onNativeSpeechError && window.onNativeSpeechError('Mikrofon-Berechtigung wurde nicht erteilt.')");
            pendingSpeechAfterPermission = false;
        }
    }

    private void requestOrStartSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingSpeechAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startSpeechSession();
    }

    private void startSpeechSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendJs("window.onNativeSpeechError && window.onNativeSpeechError('Auf diesem Gerät ist keine Android-Spracherkennung verfügbar.')");
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { sendJs("window.onNativeSpeechStart && window.onNativeSpeechStart()"); }
                @Override public void onBeginningOfSpeech() { markVoiceActivity(); }
                @Override public void onRmsChanged(float rmsdB) { if (rmsdB > 2.2f) markVoiceActivity(); }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    if (!speechSessionActive) return;
                    long quiet = SystemClock.elapsedRealtime() - lastVoiceAt;
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) && quiet < SILENCE_LIMIT_MS) {
                        restartRecognizer(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900 : 350);
                    } else {
                        String msg = speechErrorMessage(error);
                        stopSpeechSession(msg, false);
                        sendJs("window.onNativeSpeechError && window.onNativeSpeechError(" + JSONObject.quote(msg) + ")");
                    }
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        String text = list.get(0).trim();
                        if (!text.isEmpty()) {
                            markVoiceActivity();
                            sendJs("window.onNativeSpeechFinal && window.onNativeSpeechFinal(" + JSONObject.quote(text) + ")");
                        }
                    }
                    if (speechSessionActive) restartRecognizer(350);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        String text = list.get(0).trim();
                        if (!text.isEmpty()) {
                            markVoiceActivity();
                            sendJs("window.onNativeSpeechPartial && window.onNativeSpeechPartial(" + JSONObject.quote(text) + ")");
                        }
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
        }
        speechSessionActive = true;
        lastVoiceAt = SystemClock.elapsedRealtime();
        speechHandler.removeCallbacks(silenceStop);
        speechHandler.postDelayed(silenceStop, SILENCE_LIMIT_MS);
        startRecognizerNow();
    }

    private void startRecognizerNow() {
        if (!speechSessionActive || speechRecognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LIMIT_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_LIMIT_MS);
        try { speechRecognizer.startListening(i); }
        catch (Exception e) { restartRecognizer(700); }
    }

    private void restartRecognizer(long delayMs) {
        speechHandler.postDelayed(() -> {
            if (!speechSessionActive || speechRecognizer == null) return;
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            speechHandler.postDelayed(this::startRecognizerNow, 120);
        }, delayMs);
    }

    private void markVoiceActivity() {
        lastVoiceAt = SystemClock.elapsedRealtime();
        speechHandler.removeCallbacks(silenceStop);
        speechHandler.postDelayed(silenceStop, SILENCE_LIMIT_MS);
    }

    private void stopSpeechSession(String reason, boolean graceful) {
        speechSessionActive = false;
        speechHandler.removeCallbacks(silenceStop);
        if (speechRecognizer != null) {
            try { if (graceful) speechRecognizer.stopListening(); else speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        sendJs("window.onNativeSpeechEnd && window.onNativeSpeechEnd(" + JSONObject.quote(reason) + ")");
    }

    private String speechErrorMessage(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audiofehler bei der Spracherkennung.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Mikrofon-Berechtigung fehlt.";
            case SpeechRecognizer.ERROR_NETWORK: return "Netzwerkfehler bei der Spracherkennung.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Zeitüberschreitung bei der Spracherkennung.";
            case SpeechRecognizer.ERROR_SERVER: return "Spracherkennungsdienst ist momentan nicht verfügbar.";
            default: return "Spracherkennung beendet (Fehler " + code + ").";
        }
    }

    private void sendJs(String js) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    public class AndroidSpeechBridge {
        @JavascriptInterface public void startSpeech() { runOnUiThread(MainActivity.this::requestOrStartSpeech); }
        @JavascriptInterface public void stopSpeech() { runOnUiThread(() -> stopSpeechSession("Aufnahme manuell gestoppt.", true)); }
        @JavascriptInterface public boolean isAvailable() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this); }
    }

    public class AndroidFilesBridge {
        @JavascriptInterface public void saveBase64(String fileName, String base64, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                runOnUiThread(() -> {
                    pendingSaveBytes = bytes;
                    pendingSaveName = (fileName == null || fileName.trim().isEmpty()) ? "export.dat" : fileName;
                    pendingSaveMime = (mimeType == null || mimeType.trim().isEmpty()) ? "application/octet-stream" : mimeType;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(pendingSaveMime);
                    intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                    startActivityForResult(intent, REQ_SAVE_FILE);
                });
            } catch (Exception e) {
                sendJs("window.onNativeFileSaved && window.onNativeFileSaved(false,'')");
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        speechSessionActive = false;
        speechHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
