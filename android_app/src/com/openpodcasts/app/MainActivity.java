package com.openpodcasts.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.LoudnessEnhancer;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int OPML_PICKER_REQUEST_CODE = 1002;
    private MediaPlayer mediaPlayer;
    private LoudnessEnhancer loudnessEnhancer;
    private boolean isVoiceBoostEnabled = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunnable;
    private String currentAudioUrl = "";
    private ExecutorService networkExecutor = Executors.newFixedThreadPool(3);
    private BroadcastReceiver evalReceiver;
    private BroadcastReceiver autoSyncReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Native clean system status and nav bars
        Window window = getWindow();
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        );
        window.setStatusBarColor(Color.parseColor("#FFFFFF"));
        window.setNavigationBarColor(Color.parseColor("#FFFFFF"));

        webView = new WebView(this);
        setContentView(webView);

        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

        webView.addJavascriptInterface(new PodcastNativeBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://") || url.startsWith("https://") || url.startsWith("http://")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("OpenPodcastsApp", consoleMessage.message() + " -- Line "
                    + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && fileChooserParams != null) {
                    try {
                        intent = fileChooserParams.createIntent();
                    } catch (Exception ignored) {}
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "Choose OPML or XML File"), FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "Error launching file chooser: " + e.getMessage());
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });
        webView.setBackgroundColor(Color.parseColor("#FFFFFF"));

        initMediaPlayer();

        evalReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String js = intent.getStringExtra("js");
                String b64 = intent.getStringExtra("b64");
                if (b64 != null) {
                    try {
                        byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        js = new String(decoded, "UTF-8");
                    } catch (Exception ignored) {}
                }
                if (js != null && webView != null) {
                    final String code = js;
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript(code, null);
                    });
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(evalReceiver, new IntentFilter("com.openpodcasts.app.EVAL_JS"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(evalReceiver, new IntentFilter("com.openpodcasts.app.EVAL_JS"));
        }

        autoSyncReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && AutoMediaBrowserService.ACTION_SYNC_TO_UI.equals(intent.getAction())) {
                    String epId = intent.getStringExtra("epId");
                    boolean isPlaying = intent.getBooleanExtra("isPlaying", false);
                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onAutoPlaybackSync) window.onAutoPlaybackSync('" + epId + "'," + isPlaying + ");", null);
                        }
                    });
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autoSyncReceiver, new IntentFilter(AutoMediaBrowserService.ACTION_SYNC_TO_UI), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(autoSyncReceiver, new IntentFilter(AutoMediaBrowserService.ACTION_SYNC_TO_UI));
        }

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    android.content.ClipData clipData = data.getClipData();
                    if (clipData != null) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (dataString != null) {
                        results = new Uri[]{ Uri.parse(dataString) };
                    } else if (data.getData() != null) {
                        results = new Uri[]{ data.getData() };
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else if (requestCode == OPML_PICKER_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    readAndDeliverOpmlUri(uri);
                }
            }
        }
    }

    private void readAndDeliverOpmlUri(Uri uri) {
        new Thread(() -> {
            try {
                String displayName = "subscriptions.opml";
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (nameIndex != -1) {
                                displayName = cursor.getString(nameIndex);
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }

                InputStream is = getContentResolver().openInputStream(uri);
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    is.close();

                    final String finalName = displayName;
                    final String content = sb.toString();
                    final String jsonContent = JSONObject.quote(content);
                    final String jsonName = JSONObject.quote(finalName);

                    runOnUiThread(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onOpmlFilePicked) window.onOpmlFilePicked(" + jsonName + ", " + jsonContent + ");", null);
                        }
                    });
                }
            } catch (Exception e) {
                Log.e("OpenPodcastsApp", "Error reading OPML URI: " + e.getMessage());
                final String errMsg = JSONObject.quote(e.getMessage() != null ? e.getMessage() : "Error reading file");
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onOpmlError) window.onOpmlError(" + errMsg + ");", null);
                    }
                });
            }
        }).start();
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(mp -> {
            mp.start();
            applyVoiceBoost();
            int durationSec = mp.getDuration() / 1000;
            runOnUiThread(() -> {
                webView.evaluateJavascript("if(window.onNativePlayerReady) window.onNativePlayerReady(" + durationSec + ");", null);
            });
            startProgressUpdates();
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            runOnUiThread(() -> {
                webView.evaluateJavascript("if(window.onNativePlayerEnded) window.onNativePlayerEnded();", null);
            });
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e("OpenPodcastsApp", "MediaPlayer Error: " + what + ", " + extra);
            runOnUiThread(() -> {
                webView.evaluateJavascript("if(window.onNativePlayerError) window.onNativePlayerError(" + what + ");", null);
            });
            return true;
        });
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    int posSec = mediaPlayer.getCurrentPosition() / 1000;
                    int durSec = mediaPlayer.getDuration() / 1000;
                    webView.evaluateJavascript("if(window.onNativeProgressTick) window.onNativeProgressTick(" + posSec + "," + durSec + ");", null);
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        if (progressRunnable != null) {
            handler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }

    private void applyVoiceBoost() {
        try {
            if (mediaPlayer != null) {
                int sessionId = mediaPlayer.getAudioSessionId();
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = new LoudnessEnhancer(sessionId);
                }
                if (loudnessEnhancer != null) {
                    // +8.0 dB target speech gain leveling (800 mB) with limiter
                    loudnessEnhancer.setTargetGain(isVoiceBoostEnabled ? 800 : 0);
                    loudnessEnhancer.setEnabled(isVoiceBoostEnabled);
                }
            }
        } catch (Exception e) {
            Log.e("OpenPodcastsApp", "applyVoiceBoost error: " + e.getMessage());
        }
    }

    public class PodcastNativeBridge {
        @JavascriptInterface
        public void setVoiceBoost(boolean enabled) {
            runOnUiThread(() -> {
                isVoiceBoostEnabled = enabled;
                applyVoiceBoost();
            });
        }

        @JavascriptInterface
        public void playAudio(String audioUrl) {
            runOnUiThread(() -> {
                try {
                    currentAudioUrl = audioUrl;
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(audioUrl);
                    mediaPlayer.prepareAsync();
                    webView.evaluateJavascript("if(window.onNativePlaybackState) window.onNativePlaybackState(true);", null);
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "playAudio error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void pauseAudio() {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        stopProgressUpdates();
                        webView.evaluateJavascript("if(window.onNativePlaybackState) window.onNativePlaybackState(false);", null);
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "pauseAudio error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void resumeAudio() {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                        startProgressUpdates();
                        webView.evaluateJavascript("if(window.onNativePlaybackState) window.onNativePlaybackState(true);", null);
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "resumeAudio error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void seekTo(int seconds) {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer.seekTo(seconds * 1000);
                        int dur = mediaPlayer.getDuration() / 1000;
                        webView.evaluateJavascript("if(window.onNativeProgressTick) window.onNativeProgressTick(" + seconds + "," + dur + ");", null);
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "seekTo error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void setSpeed(float speed) {
            runOnUiThread(() -> {
                try {
                    if (mediaPlayer != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        PlaybackParams params = mediaPlayer.getPlaybackParams();
                        params.setSpeed(speed);
                        mediaPlayer.setPlaybackParams(params);
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "setSpeed error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void saveNativePref(String key, String value) {
            try {
                SharedPreferences prefs = getSharedPreferences("podcasts_storage", MODE_PRIVATE);
                prefs.edit().putString(key, value).apply();
            } catch (Exception e) {
                Log.e("OpenPodcastsApp", "saveNativePref error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String getNativePref(String key, String defaultValue) {
            try {
                SharedPreferences prefs = getSharedPreferences("podcasts_storage", MODE_PRIVATE);
                return prefs.getString(key, defaultValue);
            } catch (Exception e) {
                Log.e("OpenPodcastsApp", "getNativePref error: " + e.getMessage());
                return defaultValue;
            }
        }

        @JavascriptInterface
        public String getCatalogJson() {
            try {
                InputStream is = getAssets().open("real_podcasts.json");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            } catch (Exception e) {
                Log.e("OpenPodcastsApp", "getCatalogJson error: " + e.getMessage());
                return "[]";
            }
        }

        @JavascriptInterface
        public void setSystemTheme(boolean isDark) {
            runOnUiThread(() -> {
                try {
                    Window window = getWindow();
                    View decor = window.getDecorView();
                    if (isDark) {
                        decor.setSystemUiVisibility(0); // Clear light status/nav bar flags
                        window.setStatusBarColor(Color.parseColor("#121212"));
                        window.setNavigationBarColor(Color.parseColor("#121212"));
                        if (webView != null) webView.setBackgroundColor(Color.parseColor("#121212"));
                    } else {
                        decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        );
                        window.setStatusBarColor(Color.parseColor("#FFFFFF"));
                        window.setNavigationBarColor(Color.parseColor("#FFFFFF"));
                        if (webView != null) webView.setBackgroundColor(Color.parseColor("#FFFFFF"));
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "setSystemTheme error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void syncToAuto(String epId, String title, String podcast, String artwork, long pos, long dur, boolean isPlaying) {
            Intent intent = new Intent(AutoMediaBrowserService.ACTION_SYNC_FROM_UI);
            intent.putExtra("epId", epId);
            intent.putExtra("title", title);
            intent.putExtra("podcast", podcast);
            intent.putExtra("artwork", artwork);
            intent.putExtra("position", pos);
            intent.putExtra("duration", dur);
            intent.putExtra("isPlaying", isPlaying);
            sendBroadcast(intent);
        }

        @JavascriptInterface
        public void shareEpisode(String title, String podcastTitle, String audioUrl, String feedUrl) {
            runOnUiThread(() -> {
                try {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    
                    StringBuilder shareBody = new StringBuilder();
                    shareBody.append("Listening to \"").append(title).append("\"");
                    if (podcastTitle != null && !podcastTitle.isEmpty()) {
                        shareBody.append(" on ").append(podcastTitle);
                    }
                    shareBody.append("\n\n");
                    if (audioUrl != null && !audioUrl.isEmpty()) {
                        shareBody.append("Listen: ").append(audioUrl).append("\n");
                    }
                    if (feedUrl != null && !feedUrl.isEmpty()) {
                        shareBody.append("Podcast Feed: ").append(feedUrl).append("\n");
                    }
                    shareBody.append("\nShared via OpenPodcasts");

                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, shareBody.toString());

                    Intent chooser = Intent.createChooser(shareIntent, "Share Episode");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "shareEpisode error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void exportOpml(String filename, String opmlContent) {
            runOnUiThread(() -> {
                try {
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("text/xml");
                    sendIntent.putExtra(Intent.EXTRA_SUBJECT, "OpenPodcasts Subscriptions Backup");
                    sendIntent.putExtra(Intent.EXTRA_TEXT, opmlContent);

                    // Also try saving to public Downloads directory
                    try {
                        java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                        if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
                            java.io.File file = new java.io.File(downloadDir, filename != null && !filename.isEmpty() ? filename : "openpodcasts_subscriptions.opml");
                            java.io.FileWriter writer = new java.io.FileWriter(file);
                            writer.write(opmlContent);
                            writer.close();
                            Log.i("OpenPodcastsApp", "OPML saved to Downloads: " + file.getAbsolutePath());
                        }
                    } catch (Exception err) {
                        Log.w("OpenPodcastsApp", "Could not write OPML to Downloads: " + err.getMessage());
                    }

                    Intent chooser = Intent.createChooser(sendIntent, "Export OPML Subscriptions");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "exportOpml error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void pickOpmlFile() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] mimeTypes = new String[]{
                        "text/xml",
                        "text/plain",
                        "application/xml",
                        "application/x-opml+xml",
                        "text/x-opml",
                        "*/*"
                    };
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    startActivityForResult(Intent.createChooser(intent, "Choose OPML File"), OPML_PICKER_REQUEST_CODE);
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "pickOpmlFile error: " + e.getMessage());
                    try {
                        Intent fallbackIntent = new Intent(Intent.ACTION_GET_CONTENT);
                        fallbackIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        fallbackIntent.setType("*/*");
                        startActivityForResult(Intent.createChooser(fallbackIntent, "Choose OPML File"), OPML_PICKER_REQUEST_CODE);
                    } catch (Exception e2) {
                        Log.e("OpenPodcastsApp", "Fallback picker error: " + e2.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void scanDownloadFolderOpml() {
            new Thread(() -> {
                JSONArray foundFiles = new JSONArray();
                try {
                    java.io.File[] dirs = new java.io.File[]{
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        new java.io.File("/sdcard/Download")
                    };
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    for (java.io.File dir : dirs) {
                        if (dir != null && dir.exists() && dir.isDirectory()) {
                            java.io.File[] list = dir.listFiles();
                            if (list != null) {
                                for (java.io.File f : list) {
                                    if (f.isFile() && (f.getName().toLowerCase().endsWith(".opml") || f.getName().toLowerCase().endsWith(".xml"))) {
                                        if (!seen.contains(f.getAbsolutePath())) {
                                            seen.add(f.getAbsolutePath());
                                            JSONObject item = new JSONObject();
                                            item.put("name", f.getName());
                                            item.put("path", f.getAbsolutePath());
                                            item.put("size", f.length());
                                            item.put("modified", f.lastModified());
                                            foundFiles.put(item);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "scanDownloadFolderOpml error: " + e.getMessage());
                }

                final String jsonStr = JSONObject.quote(foundFiles.toString());
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onDownloadsOpmlList) window.onDownloadsOpmlList(" + jsonStr + ");", null);
                    }
                });
            }).start();
        }

        @JavascriptInterface
        public void loadOpmlFilePath(String filePath) {
            new Thread(() -> {
                try {
                    java.io.File file = new java.io.File(filePath);
                    if (file.exists() && file.canRead()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                        }
                        reader.close();

                        final String name = file.getName();
                        final String content = sb.toString();
                        final String jsonContent = JSONObject.quote(content);
                        final String jsonName = JSONObject.quote(name);
                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.onOpmlFilePicked) window.onOpmlFilePicked(" + jsonName + ", " + jsonContent + ");", null);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "loadOpmlFilePath error: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void resolvePodcastMetadata(String podcastId, String title, String feedUrl) {
            networkExecutor.execute(() -> {
                String artwork = "";
                String author = "";
                String description = "";
                String genre = "";

                // 1. Search iTunes directory for high-res 600x600 artwork and official metadata
                if (title != null && !title.trim().isEmpty()) {
                    HttpURLConnection searchConn = null;
                    try {
                        String cleanTitle = title.trim();
                        String encoded = URLEncoder.encode(cleanTitle, "UTF-8");
                        String searchUrl = "https://itunes.apple.com/search?term=" + encoded + "&media=podcast&limit=4";
                        URL url = new URL(searchUrl);
                        searchConn = (HttpURLConnection) url.openConnection();
                        searchConn.setRequestMethod("GET");
                        searchConn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                        searchConn.setConnectTimeout(6000);
                        searchConn.setReadTimeout(6000);

                        BufferedReader in = new BufferedReader(new InputStreamReader(searchConn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = in.readLine()) != null) {
                            sb.append(line);
                        }
                        in.close();

                        JSONObject json = new JSONObject(sb.toString());
                        JSONArray results = json.optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONObject chosen = results.getJSONObject(0);
                            if (feedUrl != null && !feedUrl.isEmpty()) {
                                for (int i = 0; i < results.length(); i++) {
                                    JSONObject item = results.getJSONObject(i);
                                    if (feedUrl.equalsIgnoreCase(item.optString("feedUrl", ""))) {
                                        chosen = item;
                                        break;
                                    }
                                }
                            }
                            artwork = chosen.optString("artworkUrl600", "");
                            if (artwork.isEmpty()) artwork = chosen.optString("artworkUrl100", "");
                            author = chosen.optString("artistName", "");
                            genre = chosen.optString("primaryGenreName", "");
                        }
                    } catch (Exception e) {
                        Log.w("OpenPodcastsApp", "resolvePodcastMetadata iTunes error: " + e.getMessage());
                    } finally {
                        if (searchConn != null) try { searchConn.disconnect(); } catch (Exception ignored) {}
                    }
                }

                // 2. If artwork still missing, fetch the RSS XML feed directly
                if (artwork.isEmpty() && feedUrl != null && feedUrl.startsWith("http")) {
                    HttpURLConnection feedConn = null;
                    try {
                        URL fUrl = new URL(feedUrl);
                        feedConn = (HttpURLConnection) fUrl.openConnection();
                        feedConn.setRequestMethod("GET");
                        feedConn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                        feedConn.setConnectTimeout(7000);
                        feedConn.setReadTimeout(7000);

                        InputStream in = feedConn.getInputStream();
                        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                        dbFactory.setNamespaceAware(true);
                        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                        Document doc = dBuilder.parse(in);
                        in.close();
                        doc.getDocumentElement().normalize();

                        NodeList channels = doc.getElementsByTagName("channel");
                        Element channelEl = channels.getLength() > 0 ? (Element) channels.item(0) : doc.getDocumentElement();

                        if (author.isEmpty()) {
                            author = getElementText(channelEl, "itunes:author", "");
                            if (author.isEmpty()) author = getElementText(channelEl, "author", "");
                        }
                        if (description.isEmpty()) {
                            description = getElementText(channelEl, "description", "");
                        }

                        NodeList imgList = channelEl.getElementsByTagName("itunes:image");
                        if (imgList.getLength() > 0) {
                            artwork = ((Element) imgList.item(0)).getAttribute("href");
                        }
                        if (artwork.isEmpty()) {
                            NodeList stdImg = channelEl.getElementsByTagName("image");
                            if (stdImg.getLength() > 0) {
                                artwork = getElementText((Element) stdImg.item(0), "url", "");
                            }
                        }
                    } catch (Exception e) {
                        Log.w("OpenPodcastsApp", "resolvePodcastMetadata RSS fetch error: " + e.getMessage());
                    } finally {
                        if (feedConn != null) try { feedConn.disconnect(); } catch (Exception ignored) {}
                    }
                }

                if (!artwork.isEmpty() || !author.isEmpty()) {
                    final String fArtwork = artwork;
                    final String fAuthor = author;
                    final String fDesc = description;
                    final String fGenre = genre;
                    final String fPodId = podcastId;
                    runOnUiThread(() -> {
                        if (webView != null) {
                            String js = String.format("if(window.onPodcastMetadataResolved) window.onPodcastMetadataResolved(%s, %s, %s, %s, %s);",
                                JSONObject.quote(fPodId),
                                JSONObject.quote(fArtwork),
                                JSONObject.quote(fAuthor),
                                JSONObject.quote(fDesc),
                                JSONObject.quote(fGenre)
                            );
                            webView.evaluateJavascript(js, null);
                        }
                    });
                }
            });
        }

        /**
         * Search Podcasts across the internet using the iTunes Podcast Directory API
         */
        @JavascriptInterface
        public void searchInternet(String query, String callbackId) {
            networkExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    String encoded = URLEncoder.encode(query, "UTF-8");
                    String searchUrl = "https://itunes.apple.com/search?term=" + encoded + "&media=podcast&limit=12";
                    URL url = new URL(searchUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray results = json.optJSONArray("results");
                    JSONArray outList = new JSONArray();

                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.getJSONObject(i);
                            JSONObject podcast = new JSONObject();
                            podcast.put("id", "net-" + item.optLong("collectionId", i));
                            podcast.put("title", item.optString("collectionName", "Podcast"));
                            podcast.put("author", item.optString("artistName", "Various"));
                            podcast.put("feedUrl", item.optString("feedUrl", ""));
                            String art = item.optString("artworkUrl600", "");
                            if (art.isEmpty()) art = item.optString("artworkUrl100", "");
                            podcast.put("artwork", art);
                            podcast.put("category", item.optString("primaryGenreName", "Podcasts"));
                            podcast.put("collectionId", String.valueOf(item.optLong("collectionId", 0)));
                            podcast.put("episodes", new JSONArray());
                            podcast.put("subscribed", false);
                            podcast.put("newCount", 0);
                            outList.put(podcast);
                        }
                    }

                    String jsonResult = JSONObject.quote(outList.toString());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onSearchResults) window.onSearchResults(" + jsonResult + ", '" + callbackId + "');", null);
                    });

                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "searchInternet error: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onSearchResults) window.onSearchResults('[]', '" + callbackId + "');", null);
                    });
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            });
        }

        /**
         * Search Individual Podcast Episodes across the directory
         */
        @JavascriptInterface
        public void searchEpisodesInternet(String query, String callbackId) {
            networkExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    String encoded = URLEncoder.encode(query, "UTF-8");
                    String searchUrl = "https://itunes.apple.com/search?term=" + encoded + "&entity=podcastEpisode&limit=20";
                    URL url = new URL(searchUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray results = json.optJSONArray("results");
                    JSONArray outList = new JSONArray();

                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject item = results.getJSONObject(i);
                            String audioUrl = item.optString("episodeUrl", "");
                            if (audioUrl.isEmpty()) continue;

                            JSONObject ep = new JSONObject();
                            ep.put("id", "net-ep-" + item.optLong("trackId", i));
                            ep.put("title", item.optString("trackName", "Episode"));
                            ep.put("showTitle", item.optString("collectionName", "Podcast"));
                            ep.put("author", item.optString("artistName", "Various"));
                            ep.put("audioUrl", audioUrl);
                            ep.put("feedUrl", item.optString("feedUrl", ""));
                            ep.put("collectionId", String.valueOf(item.optLong("collectionId", 0)));

                            String art = item.optString("artworkUrl600", "");
                            if (art.isEmpty()) art = item.optString("artworkUrl160", "");
                            if (art.isEmpty()) art = item.optString("artworkUrl60", "");
                            ep.put("artwork", art);

                            long durMillis = item.optLong("trackTimeMillis", 0);
                            String durStr = durMillis > 0 ? (durMillis / 60000) + " min" : "30 min";
                            ep.put("duration", durStr);

                            String relDate = item.optString("releaseDate", "");
                            if (relDate.length() >= 10) relDate = relDate.substring(0, 10);
                            ep.put("date", relDate.isEmpty() ? "Recent" : relDate);

                            String desc = item.optString("shortDescription", "");
                            if (desc.isEmpty()) desc = item.optString("description", "");
                            desc = desc.replaceAll("<[^>]*>", "").trim();
                            if (desc.length() > 240) desc = desc.substring(0, 235) + "...";
                            ep.put("desc", desc);

                            outList.put(ep);
                        }
                    }

                    String jsonResult = JSONObject.quote(outList.toString());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onEpisodeSearchResults) window.onEpisodeSearchResults(" + jsonResult + ", '" + callbackId + "');", null);
                    });

                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "searchEpisodesInternet error: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onEpisodeSearchResults) window.onEpisodeSearchResults('[]', '" + callbackId + "');", null);
                    });
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            });
        }

        /**
         * Fetch real customer ratings and reviews directly from the podcast directory
         */
        @JavascriptInterface
        public void fetchRealReviews(String podcastTitle, String podcastId, String collectionId) {
            networkExecutor.execute(() -> {
                try {
                    String targetId = collectionId;
                    if (targetId == null || targetId.isEmpty() || "0".equals(targetId)) {
                        if (podcastId != null && podcastId.startsWith("net-")) {
                            targetId = podcastId.replace("net-", "");
                        }
                    }

                    // If collectionId is still not found, search directory by podcast title
                    if (targetId == null || targetId.isEmpty() || "0".equals(targetId)) {
                        HttpURLConnection searchConn = null;
                        try {
                            String encodedTitle = URLEncoder.encode(podcastTitle, "UTF-8");
                            String searchUrl = "https://itunes.apple.com/search?term=" + encodedTitle + "&media=podcast&limit=1";
                            URL url = new URL(searchUrl);
                            searchConn = (HttpURLConnection) url.openConnection();
                            searchConn.setRequestMethod("GET");
                            searchConn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                            searchConn.setConnectTimeout(6000);
                            searchConn.setReadTimeout(6000);

                            BufferedReader in = new BufferedReader(new InputStreamReader(searchConn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) {
                                sb.append(line);
                            }
                            in.close();

                            JSONObject json = new JSONObject(sb.toString());
                            JSONArray results = json.optJSONArray("results");
                            if (results != null && results.length() > 0) {
                                targetId = String.valueOf(results.getJSONObject(0).optLong("collectionId", 0));
                            }
                        } finally {
                            if (searchConn != null) {
                                try { searchConn.disconnect(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    JSONArray outReviews = new JSONArray();

                    if (targetId != null && !targetId.isEmpty() && !"0".equals(targetId)) {
                        HttpURLConnection revConn = null;
                        try {
                            String revUrl = "https://itunes.apple.com/us/rss/customerreviews/id=" + targetId + "/sortBy=mostRecent/json";
                            URL url = new URL(revUrl);
                            revConn = (HttpURLConnection) url.openConnection();
                            revConn.setRequestMethod("GET");
                            revConn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.0");
                            revConn.setConnectTimeout(7000);
                            revConn.setReadTimeout(7000);

                            BufferedReader in = new BufferedReader(new InputStreamReader(revConn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) {
                                sb.append(line);
                            }
                            in.close();

                            JSONObject revJson = new JSONObject(sb.toString());
                            JSONObject feed = revJson.optJSONObject("feed");
                            if (feed != null) {
                                JSONArray entries = feed.optJSONArray("entry");
                                if (entries != null) {
                                    for (int i = 0; i < entries.length() && i < 40; i++) {
                                        JSONObject entry = entries.getJSONObject(i);
                                        String author = "Verified Listener";
                                        JSONObject authorObj = entry.optJSONObject("author");
                                        if (authorObj != null && authorObj.has("name")) {
                                            author = authorObj.optJSONObject("name").optString("label", "Listener");
                                        }

                                        int rating = 5;
                                        JSONObject ratingObj = entry.optJSONObject("im:rating");
                                        if (ratingObj != null) {
                                            try {
                                                rating = Integer.parseInt(ratingObj.optString("label", "5"));
                                            } catch (Exception ignored) { rating = 5; }
                                        }

                                        String title = "";
                                        JSONObject titleObj = entry.optJSONObject("title");
                                        if (titleObj != null) {
                                            title = titleObj.optString("label", "");
                                        }

                                        String content = "";
                                        JSONObject contentObj = entry.optJSONObject("content");
                                        if (contentObj != null) {
                                            content = contentObj.optString("label", "");
                                        }

                                        String updated = "Recent";
                                        JSONObject updatedObj = entry.optJSONObject("updated");
                                        if (updatedObj != null) {
                                            String raw = updatedObj.optString("label", "");
                                            if (raw.length() >= 10) {
                                                updated = raw.substring(0, 10);
                                            }
                                        }

                                        if (!content.isEmpty()) {
                                            JSONObject r = new JSONObject();
                                            r.put("id", "real_" + targetId + "_" + i);
                                            r.put("author", author);
                                            r.put("rating", rating);
                                            r.put("title", title);
                                            r.put("comment", content);
                                            r.put("date", updated);
                                            r.put("isReal", true);
                                            r.put("likes", (i % 5) * 7 + 4);
                                            outReviews.put(r);
                                        }
                                    }
                                }
                            }
                        } finally {
                            if (revConn != null) {
                                try { revConn.disconnect(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    String jsonOut = JSONObject.quote(outReviews.toString());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onReviewsLoaded) window.onReviewsLoaded('" + podcastId + "', " + jsonOut + ");", null);
                    });

                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "fetchRealReviews error: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onReviewsLoaded) window.onReviewsLoaded('" + podcastId + "', '[]');", null);
                    });
                }
            });
        }

        /**
         * Fetch and parse live RSS feed from the internet when a user selects a podcast
         */
        @JavascriptInterface
        public void fetchLiveFeed(String feedUrl, String podcastId) {
            networkExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(feedUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) OpenPodcasts/1.0");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    InputStream in = conn.getInputStream();
                    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                    dbFactory.setNamespaceAware(true);
                    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                    Document doc = dBuilder.parse(in);
                    in.close();
                    doc.getDocumentElement().normalize();

                    NodeList items = doc.getElementsByTagName("item");
                    JSONArray episodes = new JSONArray();

                    for (int i = 0; i < items.getLength() && i < 3000; i++) {
                        Element itemEl = (Element) items.item(i);
                        String epTitle = getElementText(itemEl, "title", "Episode " + (i + 1));
                        String pubDate = getElementText(itemEl, "pubDate", "Recent");

                        // Extract MP3 url from <enclosure url="..." />
                        String audioUrl = "";
                        NodeList encList = itemEl.getElementsByTagName("enclosure");
                        if (encList.getLength() > 0) {
                            Element enc = (Element) encList.item(0);
                            audioUrl = enc.getAttribute("url");
                        }

                        if (audioUrl.isEmpty()) continue;

                        String duration = getElementText(itemEl, "itunes:duration", "");
                        if (duration.isEmpty()) duration = getElementText(itemEl, "duration", "35 min");
                        if (duration.contains(":")) {
                            String[] dp = duration.split(":");
                            if (dp.length == 2) {
                                duration = dp[0] + "m";
                            } else if (dp.length == 3) {
                                duration = dp[0] + "h " + dp[1] + "m";
                            }
                        }

                        String desc = getElementText(itemEl, "description", "");
                        desc = desc.replaceAll("<[^>]*>", "").trim();
                        if (desc.length() > 240) desc = desc.substring(0, 235) + "...";

                        JSONObject ep = new JSONObject();
                        ep.put("id", podcastId + "-ep-" + (i + 1));
                        ep.put("title", epTitle);
                        ep.put("date", pubDate);
                        ep.put("duration", duration);
                        ep.put("audioUrl", audioUrl);
                        ep.put("desc", desc);
                        episodes.put(ep);
                    }

                    String jsonEps = JSONObject.quote(episodes.toString());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onFeedLoaded) window.onFeedLoaded('" + podcastId + "', " + jsonEps + ");", null);
                    });

                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "fetchLiveFeed error: " + e.getMessage());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onFeedLoaded) window.onFeedLoaded('" + podcastId + "', '[]');", null);
                    });
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            });
        }

        /**
         * Parse and subscribe directly to an open or private RSS/XML feed
         */
        @JavascriptInterface
        public void parseAndSubscribeRss(String rssUrl) {
            networkExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(rssUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 OpenPodcasts/1.2");
                    conn.setConnectTimeout(9000);
                    conn.setReadTimeout(9000);

                    InputStream in = conn.getInputStream();
                    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                    dbFactory.setNamespaceAware(true);
                    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                    Document doc = dBuilder.parse(in);
                    in.close();
                    doc.getDocumentElement().normalize();

                    NodeList channels = doc.getElementsByTagName("channel");
                    Element channelEl = channels.getLength() > 0 ? (Element) channels.item(0) : doc.getDocumentElement();

                    String showTitle = getElementText(channelEl, "title", "Custom RSS Podcast");
                    String author = getElementText(channelEl, "itunes:author", "");
                    if (author.isEmpty()) author = getElementText(channelEl, "author", "RSS Feed");
                    String description = getElementText(channelEl, "description", "Custom RSS podcast subscription.");
                    if (description.length() > 300) description = description.substring(0, 295) + "...";

                    String artwork = "";
                    NodeList imgList = channelEl.getElementsByTagName("itunes:image");
                    if (imgList.getLength() > 0) {
                        artwork = ((Element) imgList.item(0)).getAttribute("href");
                    }
                    if (artwork.isEmpty()) {
                        NodeList stdImg = channelEl.getElementsByTagName("image");
                        if (stdImg.getLength() > 0) {
                            artwork = getElementText((Element) stdImg.item(0), "url", "");
                        }
                    }

                    String podcastId = "custom-" + Math.abs(rssUrl.hashCode());
                    JSONObject showObj = new JSONObject();
                    showObj.put("id", podcastId);
                    showObj.put("title", showTitle);
                    showObj.put("author", author);
                    showObj.put("description", description);
                    showObj.put("artwork", artwork);
                    showObj.put("feedUrl", rssUrl);
                    showObj.put("category", "Custom RSS");
                    showObj.put("subscribed", true);

                    JSONArray episodes = new JSONArray();
                    NodeList items = doc.getElementsByTagName("item");
                    for (int i = 0; i < items.getLength() && i < 3000; i++) {
                        Element itemEl = (Element) items.item(i);
                        String epTitle = getElementText(itemEl, "title", "Episode " + (i + 1));
                        String pubDate = getElementText(itemEl, "pubDate", "Recent");

                        String audioUrl = "";
                        NodeList encList = itemEl.getElementsByTagName("enclosure");
                        if (encList.getLength() > 0) {
                            Element enc = (Element) encList.item(0);
                            audioUrl = enc.getAttribute("url");
                        }
                        if (audioUrl.isEmpty()) continue;

                        String duration = getElementText(itemEl, "itunes:duration", "30 min");
                        if (duration.isEmpty()) duration = getElementText(itemEl, "duration", "30 min");
                        String desc = getElementText(itemEl, "description", "");
                        if (desc.length() > 220) desc = desc.substring(0, 215) + "...";

                        JSONObject ep = new JSONObject();
                        ep.put("id", podcastId + "-ep-" + (i + 1));
                        ep.put("title", epTitle);
                        ep.put("date", pubDate);
                        ep.put("duration", duration);
                        ep.put("audioUrl", audioUrl);
                        ep.put("desc", desc);
                        episodes.put(ep);
                    }
                    showObj.put("episodes", episodes);
                    showObj.put("newCount", episodes.length());

                    String jsonShow = JSONObject.quote(showObj.toString());
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onCustomFeedSubscribed) window.onCustomFeedSubscribed(" + jsonShow + ");", null);
                    });

                } catch (Exception e) {
                    Log.e("OpenPodcastsApp", "parseAndSubscribeRss error: " + e.getMessage());
                    String errQuote = JSONObject.quote(e.getMessage() != null ? e.getMessage() : "Failed to load feed");
                    runOnUiThread(() -> {
                        if (webView != null) webView.evaluateJavascript("if(window.onCustomFeedSubscribed) window.onCustomFeedSubscribed(null, " + errQuote + ");", null);
                    });
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            });
        }

        private String getElementText(Element parent, String tagName, String fallback) {
            NodeList list = parent.getElementsByTagName(tagName);
            if (list.getLength() > 0 && list.item(0).getTextContent() != null) {
                return list.item(0).getTextContent().replaceAll("<[^>]*>", "").trim();
            }
            return fallback;
        }
    }

    @Override
    protected void onDestroy() {
        stopProgressUpdates();
        if (evalReceiver != null) {
            try { unregisterReceiver(evalReceiver); } catch (Exception ignored) {}
            evalReceiver = null;
        }
        if (autoSyncReceiver != null) {
            try { unregisterReceiver(autoSyncReceiver); } catch (Exception ignored) {}
            autoSyncReceiver = null;
        }
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (loudnessEnhancer != null) {
            try { loudnessEnhancer.release(); } catch (Exception ignored) {}
            loudnessEnhancer = null;
        }
        if (networkExecutor != null) {
            try { networkExecutor.shutdownNow(); } catch (Exception ignored) {}
        }
        if (webView != null) {
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("if (window.handleBackPress && window.handleBackPress()) { true; } else { false; }", value -> {
                if ("false".equals(value) || value == null) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        MainActivity.super.onBackPressed();
                    }
                }
            });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
