package com.openpodcasts.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android Auto MediaBrowserService implementation for Podcasts.
 * Allows browsing subscribed podcasts, queues, downloads, and recent releases
 * on car dashboards, steering wheel controls, and Google Assistant.
 */
public class AutoMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "AutoMediaService";

    public static final String MEDIA_ROOT_ID = "root_podcasts";
    public static final String CAT_SUBSCRIPTIONS = "cat_subscriptions";
    public static final String CAT_QUEUE = "cat_queue";
    public static final String CAT_DOWNLOADS = "cat_downloads";
    public static final String CAT_RECENTS = "cat_recents";

    public static final String ACTION_PLAY = "com.openpodcasts.app.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.openpodcasts.app.ACTION_PAUSE";
    public static final String ACTION_REWIND = "com.openpodcasts.app.ACTION_REWIND";
    public static final String ACTION_FAST_FORWARD = "com.openpodcasts.app.ACTION_FAST_FORWARD";
    public static final String ACTION_STOP = "com.openpodcasts.app.ACTION_STOP";
    public static final String ACTION_SYNC_FROM_UI = "com.openpodcasts.app.SYNC_FROM_UI";
    public static final String ACTION_SYNC_TO_UI = "com.openpodcasts.app.SYNC_TO_UI";

    private static final String NOTIFICATION_CHANNEL_ID = "podcasts_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MediaSession mediaSession;
    private MediaPlayer mediaPlayer;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    private List<JSONObject> showsList = new ArrayList<>();
    private Map<String, JSONObject> episodeCache = new HashMap<>();
    private Map<String, String> episodePodcastMap = new HashMap<>();
    private LruCache<String, Bitmap> artworkCache = new LruCache<>(6);

    private String currentMediaId = "";
    private JSONObject currentEpisode = null;
    private String currentPodcastTitle = "Podcasts";
    private boolean isPrepared = false;
    private boolean resumeOnFocusGain = false;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    resumeOnFocusGain = false;
                    pauseAudio();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                        resumeOnFocusGain = true;
                        pauseAudio();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                        mediaPlayer.setVolume(0.2f, 0.2f);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mediaPlayer != null && isPrepared) {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false;
                            resumeAudio();
                        }
                    }
                    break;
            }
        }
    };

    private final BroadcastReceiver noisyAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                Log.i(TAG, "Audio becoming noisy (car disconnect / headphones unplugged), pausing playback");
                pauseAudio();
            }
        }
    };

    private BroadcastReceiver uiSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (ACTION_SYNC_FROM_UI.equals(intent.getAction())) {
                String epId = intent.getStringExtra("epId");
                String title = intent.getStringExtra("title");
                String podcast = intent.getStringExtra("podcast");
                String artwork = intent.getStringExtra("artwork");
                long position = intent.getLongExtra("position", 0);
                long duration = intent.getLongExtra("duration", 0);
                boolean isPlaying = intent.getBooleanExtra("isPlaying", false);

                if (title != null && mediaSession != null) {
                    currentPodcastTitle = podcast != null ? podcast : "Podcasts";
                    updateMetadata(epId != null ? epId : "current", title, currentPodcastTitle, artwork, duration, null);
                    updatePlaybackState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position, 1.0f);
                    if (isPlaying) {
                        Notification n = buildNotification(title, currentPodcastTitle, true, null);
                        try {
                            startForeground(NOTIFICATION_ID, n);
                        } catch (Exception e) {
                            Log.w(TAG, "startForeground error", e);
                        }
                    } else {
                        stopForeground(false);
                    }
                }
            }
        }
    };

    private Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
                long pos = mediaPlayer.getCurrentPosition();
                updatePlaybackState(PlaybackState.STATE_PLAYING, pos, 1.0f);
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        loadCatalogData();

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        );
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                isPrepared = true;
                if (!requestAudioFocus()) {
                    Log.w(TAG, "Audio focus request rejected on onPrepared");
                    return;
                }
                mp.start();
                long duration = mp.getDuration();
                updatePlaybackState(PlaybackState.STATE_PLAYING, 0, 1.0f);
                handler.post(progressRunnable);

                String title = currentEpisode != null ? currentEpisode.optString("title", "Episode") : "Episode";
                String artwork = currentEpisode != null ? currentEpisode.optString("artwork") : null;
                updateMetadata(currentMediaId, title, currentPodcastTitle, artwork, duration, artworkCache.get(currentMediaId));

                Notification n = buildNotification(title, currentPodcastTitle, true, artworkCache.get(currentMediaId));
                try {
                    startForeground(NOTIFICATION_ID, n);
                } catch (Exception e) {
                    Log.w(TAG, "startForeground failed", e);
                }

                broadcastToUI(currentMediaId, title, currentPodcastTitle, artwork, 0, duration, true);
            }
        });

        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                isPrepared = false;
                updatePlaybackState(PlaybackState.STATE_PAUSED, mp.getDuration(), 1.0f);
                handler.removeCallbacks(progressRunnable);
                stopForeground(false);
                broadcastToUI(currentMediaId, "", "", "", mp.getDuration(), mp.getDuration(), false);
                skipToNextEpisode();
            }
        });

        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                isPrepared = false;
                updatePlaybackState(PlaybackState.STATE_ERROR, 0, 0);
                handler.removeCallbacks(progressRunnable);
                stopForeground(false);
                return true;
            }
        });

        mediaSession = new MediaSession(this, "OpenPodcastsAutoSession");
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setCallback(new MediaSessionCallback());
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent sessionActivity = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        mediaSession.setSessionActivity(sessionActivity);

        updatePlaybackState(PlaybackState.STATE_NONE, 0, 1.0f);
        mediaSession.setActive(true);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(noisyAudioReceiver, noisyFilter);

        IntentFilter filter = new IntentFilter(ACTION_SYNC_FROM_UI);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiSyncReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(uiSyncReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (ACTION_PLAY.equals(act)) {
                if (currentEpisode == null && !showsList.isEmpty()) {
                    JSONArray eps = showsList.get(0).optJSONArray("episodes");
                    if (eps != null && eps.length() > 0) {
                        playEpisodeById(eps.optJSONObject(0).optString("id"));
                    }
                } else {
                    resumeAudio();
                }
            } else if (ACTION_PAUSE.equals(act)) {
                pauseAudio();
            } else if (ACTION_REWIND.equals(act)) {
                rewind10Seconds();
            } else if (ACTION_FAST_FORWARD.equals(act)) {
                fastForward30Seconds();
            } else if (ACTION_STOP.equals(act)) {
                stopAudio();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(progressRunnable);
        try {
            unregisterReceiver(uiSyncReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(noisyAudioReceiver);
        } catch (Exception ignored) {}

        abandonAudioFocus();

        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Exception ignored) {}
        }
        if (artworkCache != null) {
            artworkCache.evictAll();
        }
        showsList.clear();
        episodeCache.clear();
        episodePodcastMap.clear();

        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "onGetRoot called by: " + clientPackageName);
        Bundle extras = new Bundle();
        extras.putBoolean("android.service.media.extra.OFFLINE", true);
        extras.putBoolean("android.service.media.extra.RECENT", true);
        return new BrowserRoot(MEDIA_ROOT_ID, extras);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowser.MediaItem>> result) {
        Log.i(TAG, "onLoadChildren for parent: " + parentMediaId);
        loadCatalogData();

        List<MediaBrowser.MediaItem> items = new ArrayList<>();

        if (MEDIA_ROOT_ID.equals(parentMediaId)) {
            items.add(createCategoryItem(CAT_SUBSCRIPTIONS, "Subscriptions", "Your subscribed podcasts"));
            items.add(createCategoryItem(CAT_QUEUE, "Queue", "Up Next episodes"));
            items.add(createCategoryItem(CAT_RECENTS, "Recent Episodes", "Latest releases"));
            items.add(createCategoryItem(CAT_DOWNLOADS, "Downloads", "Offline episodes"));
            result.sendResult(items);
            return;
        }

        if (CAT_SUBSCRIPTIONS.equals(parentMediaId)) {
            for (JSONObject show : showsList) {
                String id = show.optString("id");
                String title = show.optString("title", "Podcast");
                String author = show.optString("author", "");
                String artwork = show.optString("artwork", "");

                MediaDescription.Builder desc = new MediaDescription.Builder()
                    .setMediaId("show:" + id)
                    .setTitle(title)
                    .setSubtitle(author);
                if (!TextUtils.isEmpty(artwork)) {
                    desc.setIconUri(Uri.parse(artwork));
                }

                items.add(new MediaBrowser.MediaItem(desc.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
            }
            result.sendResult(items);
            return;
        }

        if (parentMediaId.startsWith("show:")) {
            String showId = parentMediaId.substring(5);
            JSONObject targetShow = null;
            for (JSONObject show : showsList) {
                if (showId.equals(show.optString("id"))) {
                    targetShow = show;
                    break;
                }
            }

            if (targetShow != null) {
                String podcastTitle = targetShow.optString("title", "");
                JSONArray episodes = targetShow.optJSONArray("episodes");
                if (episodes != null) {
                    for (int i = 0; i < episodes.length(); i++) {
                        JSONObject ep = episodes.optJSONObject(i);
                        if (ep != null) {
                            items.add(createPlayableEpisodeItem(ep, podcastTitle));
                        }
                    }
                }
            }
            result.sendResult(items);
            return;
        }

        if (CAT_RECENTS.equals(parentMediaId)) {
            for (JSONObject show : showsList) {
                String podcastTitle = show.optString("title", "");
                JSONArray episodes = show.optJSONArray("episodes");
                if (episodes != null && episodes.length() > 0) {
                    JSONObject ep = episodes.optJSONObject(0);
                    if (ep != null) {
                        items.add(createPlayableEpisodeItem(ep, podcastTitle));
                    }
                }
            }
            result.sendResult(items);
            return;
        }

        if (CAT_QUEUE.equals(parentMediaId) || CAT_DOWNLOADS.equals(parentMediaId)) {
            // Read from SharedPreferences
            SharedPreferences prefs = getSharedPreferences("podcasts_storage", Context.MODE_PRIVATE);
            String rawJson = prefs.getString(CAT_QUEUE.equals(parentMediaId) ? "gp_queue" : "gp_downloads", null);
            if (rawJson != null) {
                try {
                    JSONArray arr = new JSONArray(rawJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject ep = arr.optJSONObject(i);
                        if (ep != null) {
                            String pod = ep.optString("podcast", "Podcasts");
                            items.add(createPlayableEpisodeItem(ep, pod));
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: if empty, show first episode of each subscribed show
            if (items.isEmpty()) {
                for (JSONObject show : showsList) {
                    String podcastTitle = show.optString("title", "");
                    JSONArray episodes = show.optJSONArray("episodes");
                    if (episodes != null && episodes.length() > 0) {
                        JSONObject ep = episodes.optJSONObject(0);
                        if (ep != null) {
                            items.add(createPlayableEpisodeItem(ep, podcastTitle));
                        }
                    }
                }
            }
            result.sendResult(items);
            return;
        }

        result.sendResult(items);
    }

    private MediaBrowser.MediaItem createCategoryItem(String id, String title, String subtitle) {
        MediaDescription desc = new MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .build();
        return new MediaBrowser.MediaItem(desc, MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowser.MediaItem createPlayableEpisodeItem(JSONObject ep, String podcastTitle) {
        String epId = ep.optString("id");
        String epTitle = ep.optString("title", "Episode");
        String artwork = ep.optString("artwork", "");
        String duration = ep.optString("duration", "");

        MediaDescription.Builder desc = new MediaDescription.Builder()
            .setMediaId("ep:" + epId)
            .setTitle(epTitle)
            .setSubtitle(podcastTitle)
            .setDescription(duration);
        if (!TextUtils.isEmpty(artwork)) {
            desc.setIconUri(Uri.parse(artwork));
        }
        return new MediaBrowser.MediaItem(desc.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    private void loadCatalogData() {
        if (!showsList.isEmpty()) return;
        showsList.clear();
        episodeCache.clear();
        episodePodcastMap.clear();

        // 1. Check custom subscriptions in SharedPreferences
        SharedPreferences prefs = getSharedPreferences("podcasts_storage", Context.MODE_PRIVATE);
        String customSubsJson = prefs.getString("gp_subscriptions", null);
        if (customSubsJson != null) {
            try {
                JSONArray subsArr = new JSONArray(customSubsJson);
                for (int i = 0; i < subsArr.length(); i++) {
                    JSONObject show = subsArr.optJSONObject(i);
                    if (show != null && show.optBoolean("subscribed", true)) {
                        showsList.add(show);
                        indexShowEpisodes(show);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error loading custom subscriptions", e);
            }
        }

        // 2. Load bundled podcasts from assets if list is small or empty
        if (showsList.isEmpty()) {
            try {
                InputStream is = getAssets().open("real_podcasts.json");
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                JSONArray allShows = new JSONArray(sb.toString());
                for (int i = 0; i < allShows.length(); i++) {
                    JSONObject show = allShows.optJSONObject(i);
                    if (show != null) {
                        showsList.add(show);
                        indexShowEpisodes(show);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading bundled real_podcasts.json", e);
            }
        }
    }

    private void indexShowEpisodes(JSONObject show) {
        String podTitle = show.optString("title", "Podcast");
        JSONArray episodes = show.optJSONArray("episodes");
        if (episodes != null) {
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject ep = episodes.optJSONObject(i);
                if (ep != null) {
                    String epId = ep.optString("id");
                    episodeCache.put(epId, ep);
                    episodeCache.put("ep:" + epId, ep);
                    episodePodcastMap.put(epId, podTitle);
                    episodePodcastMap.put("ep:" + epId, podTitle);
                }
            }
        }
    }

    private class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.i(TAG, "onPlayFromMediaId: " + mediaId);
            playEpisodeById(mediaId);
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            Log.i(TAG, "onPlayFromSearch: " + query);
            if (TextUtils.isEmpty(query)) {
                resumeAudio();
                return;
            }
            String q = query.toLowerCase();
            // Search episodes or podcasts
            for (Map.Entry<String, JSONObject> entry : episodeCache.entrySet()) {
                JSONObject ep = entry.getValue();
                String title = ep.optString("title", "").toLowerCase();
                if (title.contains(q)) {
                    playEpisodeById(entry.getKey());
                    return;
                }
            }
            for (JSONObject show : showsList) {
                String podTitle = show.optString("title", "").toLowerCase();
                if (podTitle.contains(q)) {
                    JSONArray eps = show.optJSONArray("episodes");
                    if (eps != null && eps.length() > 0) {
                        playEpisodeById(eps.optJSONObject(0).optString("id"));
                        return;
                    }
                }
            }
            resumeAudio();
        }

        @Override
        public void onPlay() {
            if (currentEpisode == null && !showsList.isEmpty()) {
                JSONArray eps = showsList.get(0).optJSONArray("episodes");
                if (eps != null && eps.length() > 0) {
                    playEpisodeById(eps.optJSONObject(0).optString("id"));
                    return;
                }
            }
            resumeAudio();
        }

        @Override
        public void onPause() {
            pauseAudio();
        }

        @Override
        public void onSkipToNext() {
            skipToNextEpisode();
        }

        @Override
        public void onSkipToPrevious() {
            rewind10Seconds();
        }

        @Override
        public void onFastForward() {
            fastForward30Seconds();
        }

        @Override
        public void onRewind() {
            rewind10Seconds();
        }

        @Override
        public void onSeekTo(long pos) {
            seekToPosition(pos);
        }

        @Override
        public void onStop() {
            stopAudio();
        }
    }

    private void playEpisodeById(String mediaId) {
        currentMediaId = mediaId;
        currentEpisode = episodeCache.get(mediaId);
        if (currentEpisode == null && mediaId.startsWith("ep:")) {
            currentEpisode = episodeCache.get(mediaId.substring(3));
        }
        if (currentEpisode == null) {
            Log.w(TAG, "Episode not found for mediaId: " + mediaId);
            return;
        }

        currentPodcastTitle = episodePodcastMap.containsKey(mediaId) ? episodePodcastMap.get(mediaId) : "Podcasts";
        String audioUrl = currentEpisode.optString("audioUrl");
        String title = currentEpisode.optString("title", "Episode");
        String artwork = currentEpisode.optString("artwork");

        if (TextUtils.isEmpty(audioUrl)) {
            Log.w(TAG, "No audio URL for episode: " + title);
            return;
        }

        updatePlaybackState(PlaybackState.STATE_BUFFERING, 0, 1.0f);
        updateMetadata(currentMediaId, title, currentPodcastTitle, artwork, 0, null);

        fetchArtworkAsync(artwork, currentMediaId);

        try {
            isPrepared = false;
            mediaPlayer.reset();
            mediaPlayer.setDataSource(audioUrl);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error starting playback for: " + audioUrl, e);
            updatePlaybackState(PlaybackState.STATE_ERROR, 0, 0);
        }
    }

    private void resumeAudio() {
        if (mediaPlayer != null && isPrepared) {
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio focus request rejected on resume");
                return;
            }
            mediaPlayer.start();
            long pos = mediaPlayer.getCurrentPosition();
            updatePlaybackState(PlaybackState.STATE_PLAYING, pos, 1.0f);
            handler.post(progressRunnable);

            String title = currentEpisode != null ? currentEpisode.optString("title", "Episode") : "Episode";
            Notification n = buildNotification(title, currentPodcastTitle, true, artworkCache.get(currentMediaId));
            try {
                startForeground(NOTIFICATION_ID, n);
            } catch (Exception ignored) {}

            broadcastToUI(currentMediaId, title, currentPodcastTitle, "", pos, mediaPlayer.getDuration(), true);
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            long pos = mediaPlayer.getCurrentPosition();
            updatePlaybackState(PlaybackState.STATE_PAUSED, pos, 1.0f);
            handler.removeCallbacks(progressRunnable);
            stopForeground(false);
            abandonAudioFocus();

            String title = currentEpisode != null ? currentEpisode.optString("title", "Episode") : "Episode";
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(title, currentPodcastTitle, false, artworkCache.get(currentMediaId)));
            }

            broadcastToUI(currentMediaId, title, currentPodcastTitle, "", pos, mediaPlayer.getDuration(), false);
        }
    }

    private void fastForward30Seconds() {
        if (mediaPlayer != null && isPrepared) {
            int newPos = Math.min(mediaPlayer.getDuration(), mediaPlayer.getCurrentPosition() + 30000);
            mediaPlayer.seekTo(newPos);
            updatePlaybackState(mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, newPos, 1.0f);
        }
    }

    private void rewind10Seconds() {
        if (mediaPlayer != null && isPrepared) {
            int newPos = Math.max(0, mediaPlayer.getCurrentPosition() - 10000);
            mediaPlayer.seekTo(newPos);
            updatePlaybackState(mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, newPos, 1.0f);
        }
    }

    private void seekToPosition(long pos) {
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.seekTo((int) pos);
            updatePlaybackState(mediaPlayer.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, 1.0f);
        }
    }

    private void skipToNextEpisode() {
        // Find next episode in catalog
        boolean foundCurrent = false;
        for (JSONObject show : showsList) {
            JSONArray eps = show.optJSONArray("episodes");
            if (eps != null) {
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject ep = eps.optJSONObject(i);
                    if (ep != null) {
                        String id = ep.optString("id");
                        if (foundCurrent) {
                            playEpisodeById(id);
                            return;
                        }
                        if (id.equals(currentMediaId) || ("ep:" + id).equals(currentMediaId)) {
                            foundCurrent = true;
                        }
                    }
                }
            }
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Exception ignored) {}
            isPrepared = false;
        }
        abandonAudioFocus();
        updatePlaybackState(PlaybackState.STATE_STOPPED, 0, 1.0f);
        handler.removeCallbacks(progressRunnable);
        stopForeground(true);
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                AudioAttributes playbackAttrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
                    .build();
            }
            int res = audioManager.requestAudioFocus(audioFocusRequest);
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int res = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            );
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private void updatePlaybackState(int state, long position, float speed) {
        long actions = PlaybackState.ACTION_PLAY |
                       PlaybackState.ACTION_PAUSE |
                       PlaybackState.ACTION_PLAY_PAUSE |
                       PlaybackState.ACTION_SKIP_TO_NEXT |
                       PlaybackState.ACTION_SKIP_TO_PREVIOUS |
                       PlaybackState.ACTION_FAST_FORWARD |
                       PlaybackState.ACTION_REWIND |
                       PlaybackState.ACTION_SEEK_TO |
                       PlaybackState.ACTION_STOP;

        PlaybackState.Builder builder = new PlaybackState.Builder()
            .setActions(actions)
            .setState(state, position, speed, SystemClock.elapsedRealtime());

        if (mediaSession != null) {
            mediaSession.setPlaybackState(builder.build());
        }
    }

    private void updateMetadata(String mediaId, String title, String artist, String artworkUrl, long durationMs, Bitmap artBitmap) {
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);

        if (artBitmap != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap);
        }

        if (mediaSession != null) {
            mediaSession.setMetadata(mb.build());
        }
    }

    private void fetchArtworkAsync(final String artworkUrl, final String mediaId) {
        if (TextUtils.isEmpty(artworkUrl)) return;
        if (artworkCache.get(mediaId) != null) return;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(artworkUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);

                    // Decode bounds only
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inJustDecodeBounds = true;
                    InputStream is = conn.getInputStream();
                    BitmapFactory.decodeStream(is, null, opt);
                    is.close();
                    conn.disconnect();

                    // Calculate downsample factor targeting max 256px for notification/car
                    int sample = 1;
                    int maxDim = Math.max(opt.outWidth, opt.outHeight);
                    if (maxDim > 256) {
                        sample = Math.max(1, maxDim / 256);
                    }

                    // Decode actual bitmap scaled down with RGB_565 (2 bytes/px vs 4 bytes/px)
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    InputStream is2 = conn.getInputStream();
                    BitmapFactory.Options decodeOpt = new BitmapFactory.Options();
                    decodeOpt.inSampleSize = sample;
                    decodeOpt.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bmp = BitmapFactory.decodeStream(is2, null, decodeOpt);
                    is2.close();

                    if (bmp != null) {
                        artworkCache.put(mediaId, bmp);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mediaId.equals(currentMediaId)) {
                                    String title = currentEpisode != null ? currentEpisode.optString("title", "Episode") : "Episode";
                                    long dur = mediaPlayer != null && isPrepared ? mediaPlayer.getDuration() : 0;
                                    updateMetadata(currentMediaId, title, currentPodcastTitle, artworkUrl, dur, bmp);
                                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                                        Notification n = buildNotification(title, currentPodcastTitle, true, bmp);
                                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                                        if (nm != null) nm.notify(NOTIFICATION_ID, n);
                                    }
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Podcast Playback",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows active playback controls for Android Auto and phone screen");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String artist, boolean isPlaying, Bitmap artBitmap) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent playPauseIntent = new Intent(this, AutoMediaBrowserService.class);
        playPauseIntent.setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePI = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent rewindIntent = new Intent(this, AutoMediaBrowserService.class);
        rewindIntent.setAction(ACTION_REWIND);
        PendingIntent rewindPI = PendingIntent.getService(
            this, 2, rewindIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent ffIntent = new Intent(this, AutoMediaBrowserService.class);
        ffIntent.setAction(ACTION_FAST_FORWARD);
        PendingIntent ffPI = PendingIntent.getService(
            this, 3, ffIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nb = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }

        nb.setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle(title)
          .setContentText(artist)
          .setContentIntent(contentIntent)
          .setVisibility(Notification.VISIBILITY_PUBLIC)
          .addAction(new Notification.Action.Builder(
              android.R.drawable.ic_media_rew, "Rewind 10s", rewindPI).build())
          .addAction(new Notification.Action.Builder(
              isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
              isPlaying ? "Pause" : "Play", playPausePI).build())
          .addAction(new Notification.Action.Builder(
              android.R.drawable.ic_media_ff, "Forward 30s", ffPI).build());

        if (artBitmap != null) {
            nb.setLargeIcon(artBitmap);
        }

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2);
        nb.setStyle(mediaStyle);

        return nb.build();
    }

    private void broadcastToUI(String epId, String title, String podcast, String artwork, long pos, long dur, boolean isPlaying) {
        Intent intent = new Intent(ACTION_SYNC_TO_UI);
        intent.putExtra("epId", epId);
        intent.putExtra("title", title);
        intent.putExtra("podcast", podcast);
        intent.putExtra("artwork", artwork);
        intent.putExtra("position", pos);
        intent.putExtra("duration", dur);
        intent.putExtra("isPlaying", isPlaying);
        sendBroadcast(intent);
    }
}
