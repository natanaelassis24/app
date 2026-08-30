package br.com.folio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns a Folio narration while it is active. Keeping the neural narrator here instead of in an
 * Activity lets playback survive when the app is minimized or its reading screen is recreated.
 */
public final class NarrationPlaybackService extends Service {
    public static final String ACTION_START = "br.com.folio.action.START_NARRATION";
    public static final String ACTION_PAUSE = "br.com.folio.action.PAUSE_NARRATION";
    public static final String ACTION_RESUME = "br.com.folio.action.RESUME_NARRATION";
    public static final String ACTION_STOP = "br.com.folio.action.STOP_NARRATION";
    public static final String ACTION_PLAYBACK_EVENT = "br.com.folio.action.NARRATION_EVENT";

    public static final String EXTRA_EVENT_STATE = "event_state";
    public static final String EXTRA_EVENT_MESSAGE = "event_message";
    public static final String EXTRA_EVENT_SEGMENT = "event_segment";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_SITE_SESSION = "site_session";

    public static final int EVENT_PREPARING = 1;
    public static final int EVENT_PLAYING = 2;
    public static final int EVENT_PAUSED = 3;
    public static final int EVENT_STOPPED = 4;
    public static final int EVENT_COMPLETED = 5;
    public static final int EVENT_ERROR = 6;

    private static final String TAG = "FolioNarration";
    private static final String CHANNEL_ID = "folio_narration";
    private static final String REQUEST_DIRECTORY = "folio-narration";
    private static final String EXTRA_REQUEST_FILE = "request_file";
    private static final String EXTRA_LANGUAGE = "voice_language";
    private static final String EXTRA_SPEED = "speech_speed";
    private static final int NOTIFICATION_ID = 4127;
    private static final int TOGGLE_PENDING_INTENT = 4128;
    private static final int STOP_PENDING_INTENT = 4129;
    private static final int OPEN_PENDING_INTENT = 4130;
    private static final int INVALID_SEGMENT = -1;

    private static volatile int reportedState = EVENT_STOPPED;
    private static volatile long reportedRequestId;
    private static volatile int reportedSiteSession;

    private final Object playbackLock = new Object();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver noisyAudioReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pauseNarration();
            }
        }
    };

    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaSession mediaSession;
    private SystemTtsService narrator;
    private ArrayList<String> segments = new ArrayList<>();
    private String activeRequestFile;
    private long activeRequestId;
    private int activeSiteSession;
    private int currentSegmentIndex;
    private int playbackGeneration;
    private int playbackState = EVENT_STOPPED;
    private boolean audioFocusHeld;
    private boolean foregroundStarted;
    private boolean receiverRegistered;
    private boolean serviceDestroyed;
    private SystemTtsService.VoiceLanguage voiceLanguage =
            SystemTtsService.VoiceLanguage.PORTUGUESE;
    private float speechSpeed = 1.0f;

    /** Saves a request privately, then starts the media foreground service from a visible UI. */
    public static boolean start(Context context, List<String> sourceSegments,
                                SystemTtsService.VoiceLanguage language, float speed,
                                long requestId, int siteSession) {
        if (context == null || sourceSegments == null || sourceSegments.isEmpty()) return false;
        ArrayList<String> cleanSegments = cleanSegments(sourceSegments);
        if (cleanSegments.isEmpty()) return false;
        String requestFile = null;
        try {
            Context appContext = context.getApplicationContext();
            requestFile = writeRequest(appContext, cleanSegments);
            Intent intent = new Intent(appContext, NarrationPlaybackService.class)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_REQUEST_FILE, requestFile)
                    .putExtra(EXTRA_LANGUAGE, language == null ? 0 : language.ordinal())
                    .putExtra(EXTRA_SPEED, sanitizeSpeed(speed))
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_SITE_SESSION, siteSession);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
            return true;
        } catch (IOException | RuntimeException error) {
            Log.e(TAG, "Nao foi possivel iniciar a leitura em segundo plano", error);
            if (requestFile != null) deleteRequest(context.getApplicationContext(), requestFile);
            return false;
        }
    }

    public static void pause(Context context) {
        sendControl(context, ACTION_PAUSE);
    }

    public static void resume(Context context) {
        sendControl(context, ACTION_RESUME);
    }

    public static void stop(Context context) {
        sendControl(context, ACTION_STOP);
    }

    static int getReportedState() {
        return reportedState;
    }

    static long getReportedRequestId() {
        return reportedRequestId;
    }

    static int getReportedSiteSession() {
        return reportedSiteSession;
    }

    private static void sendControl(Context context, String action) {
        if (context == null) return;
        try {
            context.getApplicationContext().startService(new Intent(context,
                    NarrationPlaybackService.class).setAction(action));
        } catch (RuntimeException error) {
            Log.w(TAG, "Controle de narracao indisponivel", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        audioManager = getSystemService(AudioManager.class);
        createNotificationChannel();
        createMediaSession();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyAudioReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        }
        receiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            startNarration(intent);
        } else if (ACTION_PAUSE.equals(action)) {
            pauseNarration();
        } else if (ACTION_RESUME.equals(action)) {
            resumeNarration();
        } else if (ACTION_STOP.equals(action)) {
            stopNarration(EVENT_STOPPED, "Leitura interrompida");
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startNarration(Intent intent) {
        String requestFile = intent.getStringExtra(EXTRA_REQUEST_FILE);
        ArrayList<String> requestSegments;
        try {
            requestSegments = readRequest(this, requestFile);
        } catch (IOException error) {
            Log.e(TAG, "Falha ao abrir a solicitacao de leitura", error);
            if (hasRunningNarration()) {
                deleteRequest(this, requestFile);
                return;
            }
            dispatchEvent(EVENT_ERROR, intent.getLongExtra(EXTRA_REQUEST_ID, 0L),
                    intent.getIntExtra(EXTRA_SITE_SESSION, 0), INVALID_SEGMENT,
                    "Nao foi possivel preparar o texto para leitura.");
            deleteRequest(this, requestFile);
            stopSelf();
            return;
        }
        if (requestSegments.isEmpty()) {
            if (hasRunningNarration()) {
                deleteRequest(this, requestFile);
                return;
            }
            dispatchEvent(EVENT_ERROR, intent.getLongExtra(EXTRA_REQUEST_ID, 0L),
                    intent.getIntExtra(EXTRA_SITE_SESSION, 0), INVALID_SEGMENT,
                    "Nao encontrei texto para narrar.");
            deleteRequest(this, requestFile);
            stopSelf();
            return;
        }

        SystemTtsService previousNarrator;
        String previousRequest;
        long requestId = intent.getLongExtra(EXTRA_REQUEST_ID, SystemClock.elapsedRealtimeNanos());
        int siteSession = intent.getIntExtra(EXTRA_SITE_SESSION, 0);
        int generation;
        synchronized (playbackLock) {
            playbackGeneration++;
            generation = playbackGeneration;
            previousNarrator = narrator;
            narrator = null;
            previousRequest = activeRequestFile;
            activeRequestFile = requestFile;
            segments = requestSegments;
            activeRequestId = requestId;
            activeSiteSession = siteSession;
            currentSegmentIndex = 0;
            voiceLanguage = voiceLanguageAt(intent.getIntExtra(EXTRA_LANGUAGE, 0));
            speechSpeed = sanitizeSpeed(intent.getFloatExtra(EXTRA_SPEED, 1.0f));
            playbackState = EVENT_PREPARING;
        }
        if (previousNarrator != null) {
            previousNarrator.stop();
            closeNarratorLater(previousNarrator);
        }
        if (!TextUtils.isEmpty(previousRequest) && !previousRequest.equals(requestFile)) {
            deleteRequest(this, previousRequest);
        }
        ensureForeground();
        updateMediaState(EVENT_PREPARING);
        dispatchEvent(EVENT_PREPARING, requestId, siteSession, INVALID_SEGMENT,
                "Preparando a voz local");
        lifecycleExecutor.execute(() -> loadAndPlay(generation, 0));
    }

    private void resumeNarration() {
        int generation;
        int startIndex;
        long requestId;
        int siteSession;
        synchronized (playbackLock) {
            if (serviceDestroyed || playbackState != EVENT_PAUSED || segments.isEmpty()) return;
            playbackGeneration++;
            generation = playbackGeneration;
            startIndex = Math.max(0, Math.min(currentSegmentIndex, segments.size() - 1));
            playbackState = EVENT_PREPARING;
            requestId = activeRequestId;
            siteSession = activeSiteSession;
        }
        ensureForeground();
        updateMediaState(EVENT_PREPARING);
        dispatchEvent(EVENT_PREPARING, requestId, siteSession, startIndex,
                "Retomando a leitura");
        lifecycleExecutor.execute(() -> loadAndPlay(generation, startIndex));
    }

    private void loadAndPlay(int generation, int startIndex) {
        SystemTtsService localNarrator;
        SystemTtsService.VoiceLanguage language;
        float speed;
        List<String> playbackSegments;
        long requestId;
        int siteSession;
        synchronized (playbackLock) {
            if (!isCurrentGenerationLocked(generation)) return;
            localNarrator = narrator;
            language = voiceLanguage;
            speed = speechSpeed;
            playbackSegments = new ArrayList<>(segments.subList(startIndex, segments.size()));
            requestId = activeRequestId;
            siteSession = activeSiteSession;
        }

        try {
            if (localNarrator == null) {
                VoiceModelManager manager = VoiceModelManager.getInstance(getApplicationContext());
                if (!manager.isModelReady() || !manager.verifyModelForUse()) {
                    finishNarration(generation, EVENT_ERROR,
                            "A voz local precisa ser baixada novamente.");
                    return;
                }
                localNarrator = new SystemTtsService(manager.getModelDirectory());
                synchronized (playbackLock) {
                    if (!isCurrentGenerationLocked(generation)) {
                        localNarrator.close();
                        return;
                    }
                    narrator = localNarrator;
                }
            }
            localNarrator.setVoiceLanguage(language);
            localNarrator.setSpeechSpeed(speed);
            if (!requestAudioFocus()) {
                finishNarration(generation, EVENT_ERROR,
                        "Outro aplicativo esta usando o audio neste momento.");
                return;
            }
            synchronized (playbackLock) {
                if (!isCurrentGenerationLocked(generation)) return;
                playbackState = EVENT_PLAYING;
            }
            updateMediaState(EVENT_PLAYING);
            dispatchEvent(EVENT_PLAYING, requestId, siteSession, startIndex,
                    "Leitura em andamento");
            final int playbackStartIndex = startIndex;
            localNarrator.speak(playbackSegments, new SystemTtsService.ProgressListener() {
                @Override public void onSegmentStarted(int segmentIndex) {
                    int globalIndex = playbackStartIndex + segmentIndex;
                    synchronized (playbackLock) {
                        if (!isCurrentGenerationLocked(generation)) return;
                        currentSegmentIndex = globalIndex;
                    }
                    dispatchEvent(EVENT_PLAYING, requestId, siteSession, globalIndex,
                            "Leitura em andamento");
                }

                @Override public void onCompleted() {
                    finishNarration(generation, EVENT_COMPLETED, "Leitura concluida");
                }

                @Override public void onError(String message) {
                    finishNarration(generation, EVENT_ERROR, TextUtils.isEmpty(message)
                            ? "Nao foi possivel gerar a voz local." : message);
                }
            });
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Memoria insuficiente para a narracao", error);
            finishNarration(generation, EVENT_ERROR,
                    "Memoria insuficiente para iniciar a voz local.");
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Falha ao iniciar a narracao", error);
            finishNarration(generation, EVENT_ERROR,
                    "Nao foi possivel iniciar a voz local neste celular.");
        }
    }

    /** Pausing restarts the current text block on resume, because AudioTrack has no word position. */
    private void pauseNarration() {
        SystemTtsService localNarrator;
        long requestId;
        int siteSession;
        int segmentIndex;
        synchronized (playbackLock) {
            if (serviceDestroyed || (playbackState != EVENT_PLAYING
                    && playbackState != EVENT_PREPARING)) return;
            playbackGeneration++;
            localNarrator = narrator;
            playbackState = EVENT_PAUSED;
            requestId = activeRequestId;
            siteSession = activeSiteSession;
            segmentIndex = currentSegmentIndex;
        }
        if (localNarrator != null) localNarrator.stop();
        abandonAudioFocus();
        updateMediaState(EVENT_PAUSED);
        dispatchEvent(EVENT_PAUSED, requestId, siteSession, segmentIndex, "Leitura pausada");
    }

    private void stopNarration(int eventState, String message) {
        int generation;
        synchronized (playbackLock) {
            generation = playbackGeneration;
        }
        finishNarration(generation, eventState, message);
    }

    private void finishNarration(int generation, int eventState, String message) {
        SystemTtsService localNarrator;
        String requestFile;
        long requestId;
        int siteSession;
        synchronized (playbackLock) {
            if (serviceDestroyed || generation != playbackGeneration) return;
            playbackGeneration++;
            localNarrator = narrator;
            narrator = null;
            requestFile = activeRequestFile;
            activeRequestFile = null;
            requestId = activeRequestId;
            siteSession = activeSiteSession;
            activeRequestId = 0L;
            activeSiteSession = 0;
            currentSegmentIndex = 0;
            segments.clear();
            playbackState = EVENT_STOPPED;
        }
        if (localNarrator != null) {
            localNarrator.stop();
            closeNarratorLater(localNarrator);
        }
        abandonAudioFocus();
        updateMediaState(EVENT_STOPPED);
        dispatchEvent(eventState, requestId, siteSession, INVALID_SEGMENT, message);
        deleteRequest(this, requestFile);
        removeForeground();
        lifecycleExecutor.execute(() -> {
            synchronized (playbackLock) {
                if (playbackGeneration != generation + 1 || playbackState != EVENT_STOPPED) return;
            }
            stopSelf();
        });
    }

    private void closeNarratorLater(SystemTtsService voice) {
        lifecycleExecutor.execute(() -> {
            try {
                voice.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "Falha ao liberar a voz local", error);
            }
        });
    }

    private boolean requestAudioFocus() {
        if (audioManager == null || audioFocusRequest == null) return true;
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        synchronized (playbackLock) {
            audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            return audioFocusHeld;
        }
    }

    private void abandonAudioFocus() {
        boolean abandon;
        synchronized (playbackLock) {
            abandon = audioFocusHeld;
            audioFocusHeld = false;
        }
        if (abandon && audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }

    private void createNotificationChannel() {
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Leitura do Folio",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controles da leitura em segundo plano");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void createMediaSession() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        if (audioManager != null) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(this::onAudioFocusChanged)
                    .build();
        }
        mediaSession = new MediaSession(this, "FolioNarration");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                resumeNarration();
            }

            @Override public void onPause() {
                pauseNarration();
            }

            @Override public void onStop() {
                stopNarration(EVENT_STOPPED, "Leitura interrompida");
            }
        });
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Folio")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Leitura local")
                .build());
        mediaSession.setActive(true);
        updateMediaState(EVENT_STOPPED);
    }

    private void onAudioFocusChanged(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            pauseNarration();
        }
    }

    private void ensureForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void removeForeground() {
        if (!foregroundStarted) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
    }

    private void updateMediaState(int state) {
        MediaSession session = mediaSession;
        if (session != null) {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
            int frameworkState;
            switch (state) {
                case EVENT_PLAYING:
                    frameworkState = PlaybackState.STATE_PLAYING;
                    break;
                case EVENT_PAUSED:
                    frameworkState = PlaybackState.STATE_PAUSED;
                    break;
                case EVENT_PREPARING:
                    frameworkState = PlaybackState.STATE_BUFFERING;
                    break;
                default:
                    frameworkState = PlaybackState.STATE_STOPPED;
                    break;
            }
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(frameworkState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build());
        }
        if (foregroundStarted && notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        int state;
        synchronized (playbackLock) {
            state = playbackState;
        }
        boolean paused = state == EVENT_PAUSED;
        boolean preparing = state == EVENT_PREPARING;
        String text = preparing ? "Preparando a voz local"
                : paused ? "Leitura pausada" : "Leitura em andamento";

        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, OPEN_PENDING_INTENT,
                openIntent, pendingIntentFlags());
        Intent stopIntent = new Intent(this, NarrationPlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, STOP_PENDING_INTENT,
                stopIntent, pendingIntentFlags());

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_volume)
                .setContentTitle("Folio")
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT);
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mediaSession == null ? null : mediaSession.getSessionToken());
        if (preparing) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                    "Parar", stopPendingIntent).build());
            style.setShowActionsInCompactView(0);
        } else {
            Intent toggleIntent = new Intent(this, NarrationPlaybackService.class)
                    .setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
            PendingIntent togglePendingIntent = PendingIntent.getService(this, TOGGLE_PENDING_INTENT,
                    toggleIntent, pendingIntentFlags());
            builder.addAction(new Notification.Action.Builder(paused
                            ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                    paused ? "Retomar" : "Pausar", togglePendingIntent).build());
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                    "Parar", stopPendingIntent).build());
            style.setShowActionsInCompactView(0, 1);
        }
        builder.setStyle(style);
        return builder.build();
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private void dispatchEvent(int eventState, long requestId, int siteSession, int segmentIndex,
                               String message) {
        if (eventState == EVENT_PREPARING || eventState == EVENT_PLAYING
                || eventState == EVENT_PAUSED) {
            reportedState = eventState;
            reportedRequestId = requestId;
            reportedSiteSession = siteSession;
        } else {
            reportedState = EVENT_STOPPED;
            reportedRequestId = 0L;
            reportedSiteSession = 0;
        }
        Intent event = new Intent(ACTION_PLAYBACK_EVENT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_EVENT_STATE, eventState)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_SITE_SESSION, siteSession)
                .putExtra(EXTRA_EVENT_SEGMENT, segmentIndex)
                .putExtra(EXTRA_EVENT_MESSAGE, message == null ? "" : message);
        sendBroadcast(event);
    }

    private boolean isCurrentGenerationLocked(int generation) {
        return !serviceDestroyed && generation == playbackGeneration
                && playbackState != EVENT_STOPPED && playbackState != EVENT_PAUSED;
    }

    private boolean hasRunningNarration() {
        synchronized (playbackLock) {
            return playbackState != EVENT_STOPPED;
        }
    }

    private static SystemTtsService.VoiceLanguage voiceLanguageAt(int index) {
        return SystemTtsService.VoiceLanguage.fromIndex(index);
    }

    private static float sanitizeSpeed(float speed) {
        if (!Float.isFinite(speed)) return 1.0f;
        return Math.max(0.5f, Math.min(2.0f, speed));
    }

    private static ArrayList<String> cleanSegments(List<String> source) {
        ArrayList<String> clean = new ArrayList<>();
        for (String segment : source) {
            if (segment == null) continue;
            String text = segment.trim();
            if (!text.isEmpty()) clean.add(text);
        }
        return clean;
    }

    private static String writeRequest(Context context, ArrayList<String> requestSegments)
            throws IOException {
        File directory = requestDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Nao foi possivel criar o armazenamento da leitura");
        }
        String fileName = "request-" + UUID.randomUUID() + ".bin";
        File target = new File(directory, fileName);
        File temporary = new File(directory, fileName + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(temporary)))) {
            output.writeInt(requestSegments.size());
            for (String segment : requestSegments) {
                byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
                output.writeInt(bytes.length);
                output.write(bytes);
            }
            output.flush();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Nao foi possivel salvar a leitura");
        }
        return fileName;
    }

    private static ArrayList<String> readRequest(Context context, String fileName) throws IOException {
        if (TextUtils.isEmpty(fileName) || !fileName.matches("request-[0-9a-fA-F-]+\\.bin")) {
            throw new IOException("Solicitacao de leitura invalida");
        }
        File file = new File(requestDirectory(context), fileName).getCanonicalFile();
        File directory = requestDirectory(context).getCanonicalFile();
        if (!file.getParentFile().equals(directory) || !file.isFile()) {
            throw new IOException("Solicitacao de leitura ausente");
        }
        ArrayList<String> result = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)))) {
            int count = input.readInt();
            if (count <= 0 || count > 2048) throw new IOException("Solicitacao de leitura invalida");
            for (int index = 0; index < count; index++) {
                int size = input.readInt();
                if (size <= 0 || size > 2_000_000) {
                    throw new IOException("Texto de leitura invalido");
                }
                byte[] bytes = new byte[size];
                input.readFully(bytes);
                String text = new String(bytes, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) result.add(text);
            }
        }
        return result;
    }

    private static File requestDirectory(Context context) {
        return new File(context.getCacheDir(), REQUEST_DIRECTORY);
    }

    private static void deleteRequest(Context context, String fileName) {
        if (context == null || TextUtils.isEmpty(fileName)) return;
        if (!fileName.matches("request-[0-9a-fA-F-]+\\.bin")) return;
        File file = new File(requestDirectory(context), fileName);
        if (file.isFile() && !file.delete()) {
            Log.w(TAG, "Nao foi possivel apagar a solicitacao de leitura");
        }
    }

    @Override
    public void onDestroy() {
        SystemTtsService localNarrator;
        String requestFile;
        synchronized (playbackLock) {
            serviceDestroyed = true;
            playbackGeneration++;
            localNarrator = narrator;
            narrator = null;
            requestFile = activeRequestFile;
            activeRequestFile = null;
            segments.clear();
            playbackState = EVENT_STOPPED;
        }
        if (localNarrator != null) {
            try {
                localNarrator.stop();
                localNarrator.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "Falha ao encerrar a voz local", error);
            }
        }
        deleteRequest(this, requestFile);
        abandonAudioFocus();
        removeForeground();
        if (receiverRegistered) {
            try {
                unregisterReceiver(noisyAudioReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        reportedState = EVENT_STOPPED;
        reportedRequestId = 0L;
        reportedSiteSession = 0;
        lifecycleExecutor.shutdownNow();
        super.onDestroy();
    }
}
