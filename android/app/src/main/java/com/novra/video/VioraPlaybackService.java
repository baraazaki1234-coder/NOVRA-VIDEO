package com.novra.video;

import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;

public final class VioraPlaybackService extends MediaSessionService {
    public static final String ACTION_PLAY = "com.novra.video.PLAY";
    public static final String ACTION_PAUSE = "com.novra.video.PAUSE";
    public static final String ACTION_RESUME = "com.novra.video.RESUME";
    public static final String ACTION_STOP = "com.novra.video.STOP";
    public static final String ACTION_SEEK = "com.novra.video.SEEK";
    public static final String EXTRA_URI = "media_uri";
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_ARTIST = "media_artist";
    public static final String EXTRA_POSITION = "media_position";

    private ExoPlayer player;
    private MediaSession mediaSession;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateTicker = new Runnable() {
        @Override public void run() {
            broadcastPlaybackState(false);
            if (player != null && player.isPlaying()) stateHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(audioAttributes, true);
        player.setHandleAudioBecomingNoisy(true);
        mediaSession = new MediaSession.Builder(this, player).build();
        player.addListener(new androidx.media3.common.Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                broadcastPlaybackState(false);
                stateHandler.removeCallbacks(stateTicker);
                if (isPlaying) stateHandler.post(stateTicker);
            }
            @Override public void onPlaybackStateChanged(int state) {
                broadcastPlaybackState(state == androidx.media3.common.Player.STATE_ENDED);
            }
        });
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                String rawUri = intent.getStringExtra(EXTRA_URI);
                if (rawUri != null && !rawUri.isEmpty()) {
                    MediaMetadata metadata = new MediaMetadata.Builder()
                        .setTitle(intent.getStringExtra(EXTRA_TITLE))
                        .setArtist(intent.getStringExtra(EXTRA_ARTIST))
                        .setDisplayTitle(intent.getStringExtra(EXTRA_TITLE))
                        .build();
                    MediaItem item = new MediaItem.Builder()
                        .setUri(rawUri)
                        .setMediaMetadata(metadata)
                        .build();
                    player.setMediaItem(item);
                    player.prepare();
                    player.play();
                }
            } else if (ACTION_PAUSE.equals(action)) {
                player.pause();
            } else if (ACTION_RESUME.equals(action)) {
                player.play();
            } else if (ACTION_STOP.equals(action)) {
                player.stop();
                player.clearMediaItems();
                stopSelf();
            } else if (ACTION_SEEK.equals(action)) {
                player.seekTo(Math.max(0L, intent.getLongExtra(EXTRA_POSITION, 0L)));
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void broadcastPlaybackState(boolean ended) {
        if (player == null) return;
        Intent update = new Intent("com.novra.video.PLAYBACK_STATE");
        update.setPackage(getPackageName());
        update.putExtra("playing", player.isPlaying());
        update.putExtra("ended", ended);
        update.putExtra("position", player.getCurrentPosition());
        update.putExtra("duration", player.getDuration());
        sendBroadcast(update);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        stateHandler.removeCallbacksAndMessages(null);
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
