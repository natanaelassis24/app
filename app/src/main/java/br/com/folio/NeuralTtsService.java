package br.com.folio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

public final class NeuralTtsService {
    public enum VoiceLanguage {
        PORTUGUESE("português", new Locale("pt", "BR"), true),
        ENGLISH("inglês", Locale.US, false),
        SPANISH("espanhol", new Locale("es", "ES"), false),
        FRENCH("francês", Locale.FRANCE, false);

        final String displayName;
        final Locale locale;
        final boolean usesNeuralVoice;

        VoiceLanguage(String displayName, Locale locale, boolean usesNeuralVoice) {
            this.displayName = displayName;
            this.locale = locale;
            this.usesNeuralVoice = usesNeuralVoice;
        }
    }

    public interface ProgressListener {
        void onSegmentStarted(int segmentIndex);
        void onCompleted();
        void onError(String message);
    }

    private static final String TAG = "FolioNeuralTts";
    private static final String NARRATOR_MODEL_DIRECTORY =
            "tts/sherpa-onnx-supertonic-3-tts-int8-2026-05-11";
    private static final int MAX_TTS_THREADS = 2;
    private static final int FIRST_SEGMENT_CHARACTERS = 320;
    private static final int MAX_SEGMENT_CHARACTERS = 650;
    private static final int MIN_FALLBACK_SEGMENT_CHARACTERS = 96;
    private static final int AUDIO_QUEUE_CAPACITY = 3;
    private static final int OUTPUT_BUFFER_MS = 900;
    private static final int PLAYBACK_PREBUFFER_MS = 550;
    private static final int CHUNK_EDGE_FADE_MS = 12;
    // Supertonic M5: voz masculina, quente e voltada para histórias/audiobooks.
    private static final int NARRATOR_SPEAKER_ID = 9;
    private static final int NARRATOR_NUM_STEPS = 8;
    private static final float DEFAULT_SPEECH_SPEED = 1.0f;
    private static final float MIN_SPEECH_SPEED = 0.5f;
    private static final float MAX_SPEECH_SPEED = 2.0f;
    private static final long SYSTEM_TTS_READY_TIMEOUT_MS = 4000L;
    private final ExecutorService playbackExecutor = createBoundedExecutor();
    private final ExecutorService generationExecutor = createBoundedExecutor();
    private final ExecutorService systemSpeechExecutor = createBoundedExecutor();
    private final AtomicInteger playbackSession = new AtomicInteger();
    private final Object audioLock = new Object();
    private final Object ttsLock = new Object();
    private final Object systemTtsLock = new Object();
    private final ConcurrentHashMap<String, SystemUtterance> systemUtterances =
            new ConcurrentHashMap<>();
    private OfflineTts tts;
    private AudioTrack audioTrack;
    private TextToSpeech systemTts;
    private volatile boolean systemTtsReady;
    private volatile boolean systemTtsFailed;
    private volatile VoiceLanguage voiceLanguage = VoiceLanguage.PORTUGUESE;
    private volatile float speechSpeed = DEFAULT_SPEECH_SPEED;
    private volatile boolean closed;

    private static ExecutorService createBoundedExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public NeuralTtsService(Context context) {
        OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
        supertonic.setDurationPredictor(NARRATOR_MODEL_DIRECTORY
                + "/duration_predictor.int8.onnx");
        supertonic.setTextEncoder(NARRATOR_MODEL_DIRECTORY + "/text_encoder.int8.onnx");
        supertonic.setVectorEstimator(NARRATOR_MODEL_DIRECTORY
                + "/vector_estimator.int8.onnx");
        supertonic.setVocoder(NARRATOR_MODEL_DIRECTORY + "/vocoder.int8.onnx");
        supertonic.setTtsJson(NARRATOR_MODEL_DIRECTORY + "/tts.json");
        supertonic.setUnicodeIndexer(NARRATOR_MODEL_DIRECTORY + "/unicode_indexer.bin");
        supertonic.setVoiceStyle(NARRATOR_MODEL_DIRECTORY + "/voice.bin");

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setSupertonic(supertonic);
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int ttsThreads = Math.max(1, Math.min(MAX_TTS_THREADS,
                Math.max(1, availableProcessors - 1)));
        model.setNumThreads(ttsThreads);
        model.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(4);
        tts = new OfflineTts(context.getAssets(), config);
        initializeSystemTts(context.getApplicationContext());
    }

    private void initializeSystemTts(Context context) {
        systemTts = new TextToSpeech(context, status -> {
            synchronized (systemTtsLock) {
                systemTtsReady = status == TextToSpeech.SUCCESS;
                systemTtsFailed = !systemTtsReady;
                systemTtsLock.notifyAll();
            }
        });
        systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                SystemUtterance utterance = systemUtterances.get(utteranceId);
                if (utterance != null && utterance.startsSegment
                        && isSessionActive(utterance.session)) {
                    notifySegmentStarted(utterance.listener, utterance.session,
                            utterance.segmentIndex);
                }
            }

            @Override public void onDone(String utteranceId) {
                SystemUtterance utterance = systemUtterances.remove(utteranceId);
                if (utterance != null && utterance.last && isSessionActive(utterance.session)) {
                    notifyCompleted(utterance.listener, utterance.session);
                }
            }

            @Override public void onError(String utteranceId) {
                SystemUtterance utterance = systemUtterances.remove(utteranceId);
                if (utterance == null || !isSessionActive(utterance.session)) return;
                clearSystemUtterances(utterance.session);
                stopSystemTts();
                notifyError(utterance.listener, utterance.session,
                        "Não foi possível iniciar a voz de " + utterance.language.displayName
                                + " do celular.");
            }
        });
    }

    public void setVoiceLanguage(VoiceLanguage language) {
        if (language != null) voiceLanguage = language;
    }

    public void setSpeechSpeed(float speed) {
        if (!Float.isFinite(speed)) return;
        speechSpeed = Math.max(MIN_SPEECH_SPEED, Math.min(MAX_SPEECH_SPEED, speed));
    }

    public void speak(String text) {
        if (text == null) return;
        List<String> segments = new ArrayList<>();
        segments.add(text);
        speak(segments, null);
    }

    public void speak(List<String> segments, ProgressListener listener) {
        if (closed || segments == null || segments.isEmpty()) return;
        List<String> cleanSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null) continue;
            String clean = segment.trim();
            if (!clean.isEmpty()) cleanSegments.add(clean);
        }
        if (cleanSegments.isEmpty()) return;
        final int session = playbackSession.incrementAndGet();
        stopAudio();
        stopSystemTts();
        VoiceLanguage selectedLanguage = voiceLanguage;
        float selectedSpeechSpeed = speechSpeed;
        try {
            if (selectedLanguage.usesNeuralVoice) {
                playbackExecutor.execute(() -> play(session, cleanSegments, listener,
                        selectedSpeechSpeed));
            } else {
                systemSpeechExecutor.execute(() -> playWithSystemVoice(session, cleanSegments,
                        listener, selectedLanguage, selectedSpeechSpeed));
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Não foi possível iniciar a reprodução", error);
            notifyError(listener, session, "Não foi possível iniciar a voz selecionada.");
        }
    }

    public void stop() {
        playbackSession.incrementAndGet();
        stopAudio();
        stopSystemTts();
    }

    private void playWithSystemVoice(int session, List<String> segments, ProgressListener listener,
                                     VoiceLanguage language, float speechSpeed) {
        if (!waitForSystemTts(session, listener, language)) return;
        List<SystemUtterance> utterances = createSystemUtterances(session, segments, listener,
                language);
        if (utterances.isEmpty()) {
            notifyCompleted(listener, session);
            return;
        }
        synchronized (systemTtsLock) {
            if (!isSessionActive(session) || systemTts == null) return;
            Locale locale = availableSystemLocale(language);
            if (locale == null) {
                notifyError(listener, session, unavailableVoiceMessage(language));
                return;
            }
            int languageResult = systemTts.setLanguage(locale);
            if (languageResult == TextToSpeech.LANG_MISSING_DATA
                    || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                notifyError(listener, session, unavailableVoiceMessage(language));
                return;
            }
            systemTts.setSpeechRate(speechSpeed);
            systemTts.setPitch(1.0f);
            for (SystemUtterance utterance : utterances) {
                if (!isSessionActive(session)) {
                    clearSystemUtterances(session);
                    return;
                }
                systemUtterances.put(utterance.id, utterance);
                int result = systemTts.speak(utterance.text, TextToSpeech.QUEUE_ADD,
                        new Bundle(), utterance.id);
                if (result == TextToSpeech.ERROR) {
                    systemUtterances.remove(utterance.id);
                    clearSystemUtterances(session);
                    stopSystemTts();
                    notifyError(listener, session, "Não foi possível iniciar a voz de "
                            + language.displayName + " do celular.");
                    return;
                }
            }
        }
    }

    private boolean waitForSystemTts(int session, ProgressListener listener,
                                     VoiceLanguage language) {
        long deadline = SystemClock.uptimeMillis() + SYSTEM_TTS_READY_TIMEOUT_MS;
        synchronized (systemTtsLock) {
            while (isSessionActive(session) && !systemTtsReady && !systemTtsFailed
                    && SystemClock.uptimeMillis() < deadline) {
                try {
                    systemTtsLock.wait(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (isSessionActive(session) && systemTtsReady && systemTts != null) return true;
        }
        if (isSessionActive(session)) notifyError(listener, session, unavailableVoiceMessage(language));
        return false;
    }

    private Locale availableSystemLocale(VoiceLanguage language) {
        if (systemTts == null) return null;
        if (systemTts.isLanguageAvailable(language.locale) >= TextToSpeech.LANG_AVAILABLE) {
            return language.locale;
        }
        Locale genericLocale = new Locale(language.locale.getLanguage());
        return systemTts.isLanguageAvailable(genericLocale) >= TextToSpeech.LANG_AVAILABLE
                ? genericLocale : null;
    }

    private List<SystemUtterance> createSystemUtterances(int session, List<String> segments,
                                                           ProgressListener listener,
                                                           VoiceLanguage language) {
        List<SystemUtterance> utterances = new ArrayList<>();
        int utteranceIndex = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            boolean startsSegment = true;
            for (String part : splitText(segments.get(segmentIndex))) {
                String id = "folio-system-" + session + "-" + utteranceIndex++;
                utterances.add(new SystemUtterance(id, part, session, segmentIndex,
                        startsSegment, listener, language));
                startsSegment = false;
            }
        }
        if (!utterances.isEmpty()) utterances.get(utterances.size() - 1).last = true;
        return utterances;
    }

    private String unavailableVoiceMessage(VoiceLanguage language) {
        return "A voz de " + language.displayName + " não está instalada no celular. "
                + "Baixe-a nas configurações de Texto para fala e tente novamente.";
    }

    private void play(int session, List<String> segments, ProgressListener listener,
                      float speechSpeed) {
        AudioTrack track = null;
        try {
            if (!isSessionActive(session)) return;
            int sampleRate = sampleRate();
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferSize <= 0) throw new IllegalStateException("Buffer de áudio indisponível");
            int bufferSize = Math.max(minBufferSize * 4,
                    sampleRate * 2 * OUTPUT_BUFFER_MS / 1000);
            BlockingQueue<AudioChunk> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
            generationExecutor.execute(() -> generateAudio(session, segments, audioQueue,
                    speechSpeed));
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Não foi possível iniciar o áudio");
            }
            synchronized (audioLock) {
                if (!isSessionActive(session)) {
                    track.release();
                    return;
                }
                audioTrack = track;
            }
            track.setVolume(1.0f);
            List<AudioChunk> prebuffer = new ArrayList<>();
            int bufferedSamples = 0;
            boolean reachedEnd = false;
            int prebufferTargetSamples = sampleRate * PLAYBACK_PREBUFFER_MS / 1000;
            while (isSessionActive(session) && bufferedSamples < prebufferTargetSamples) {
                AudioChunk chunk = audioQueue.poll(250, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                if (chunk.end) {
                    reachedEnd = true;
                    break;
                }
                prebuffer.add(chunk);
                bufferedSamples += chunk.samples.length;
            }
            if (prebuffer.isEmpty() || !isSessionActive(session)) return;
            track.play();
            int playbackStartFrame = track.getPlaybackHeadPosition();
            int framesWritten = 0;
            for (AudioChunk chunk : prebuffer) {
                if (!isSessionActive(session)) return;
                notifySegmentStarted(listener, session, chunk);
                framesWritten += writeAudio(track, chunk.samples);
            }
            if (reachedEnd) {
                waitForPlaybackDrain(track, playbackStartFrame, framesWritten, session, bufferSize,
                        sampleRate);
                notifyCompleted(listener, session);
                return;
            }
            while (isSessionActive(session)) {
                AudioChunk chunk = audioQueue.poll(250, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                if (chunk.end) {
                    waitForPlaybackDrain(track, playbackStartFrame, framesWritten, session, bufferSize,
                            sampleRate);
                    notifyCompleted(listener, session);
                    break;
                }
                notifySegmentStarted(listener, session, chunk);
                framesWritten += writeAudio(track, chunk.samples);
            }
        } catch (Exception error) {
            Log.e(TAG, "Falha ao gerar ou reproduzir voz", error);
        } finally {
            releaseTrack(track);
        }
    }

    private void generateAudio(int session, List<String> segments,
                               BlockingQueue<AudioChunk> audioQueue, float speechSpeed) {
        try {
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                if (!isSessionActive(session)) return;
                boolean[] hasStartedSegment = {false};
                for (String part : splitText(segments.get(segmentIndex))) {
                    if (!isSessionActive(session)) return;
                    if (!generateIntoQueue(session, part, audioQueue, segmentIndex,
                            hasStartedSegment, speechSpeed)) return;
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Falha ao gerar trecho da voz", error);
        } finally {
            offerAudio(session, audioQueue, AudioChunk.end());
        }
    }

    private boolean offerAudio(int session, BlockingQueue<AudioChunk> audioQueue, AudioChunk audio) {
        while (isSessionActive(session)) {
            try {
                if (audioQueue.offer(audio, 250, TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isSessionActive(int session) {
        return !closed && playbackSession.get() == session;
    }

    private int sampleRate() {
        synchronized (ttsLock) {
            if (tts == null) throw new IllegalStateException("Voz local indisponível");
            return tts.sampleRate();
        }
    }

    private boolean generateIntoQueue(int session, String text, BlockingQueue<AudioChunk> audioQueue,
                                      int segmentIndex, boolean[] hasStartedSegment,
                                      float speechSpeed) {
        AudioChunk chunk;
        synchronized (ttsLock) {
            if (tts == null || !isSessionActive(session)) return false;
            GeneratedAudio generated = generateNarratorAudio(text, speechSpeed);
            if (!isSessionActive(session) || generated == null) return false;
            float[] samples = generated.getSamples();
            if (samples == null || samples.length == 0) return isSessionActive(session);
            short[] pcm = toPcm(samples, generated.getSampleRate());
            if (pcm.length == 0) return isSessionActive(session);
            boolean startsSegment = !hasStartedSegment[0];
            hasStartedSegment[0] = true;
            chunk = new AudioChunk(pcm, segmentIndex, startsSegment, false);
        }
        return offerAudio(session, audioQueue, chunk);
    }

    private GeneratedAudio generateNarratorAudio(String text, float speechSpeed) {
        GenerationConfig config = new GenerationConfig();
        config.setSid(NARRATOR_SPEAKER_ID);
        config.setNumSteps(NARRATOR_NUM_STEPS);
        config.setSpeed(speechSpeed);
        config.setExtra(Collections.singletonMap("lang", "pt"));
        return tts.generateWithConfig(text, config);
    }

    private void notifySegmentStarted(ProgressListener listener, int session, AudioChunk chunk) {
        if (chunk == null || !chunk.startsSegment) return;
        notifySegmentStarted(listener, session, chunk.segmentIndex);
    }

    private void notifySegmentStarted(ProgressListener listener, int session, int segmentIndex) {
        if (listener == null || !isSessionActive(session)) return;
        try {
            listener.onSegmentStarted(segmentIndex);
        } catch (RuntimeException error) {
            Log.w(TAG, "Falha ao atualizar o progresso da narração", error);
        }
    }

    private void notifyCompleted(ProgressListener listener, int session) {
        if (listener == null || !isSessionActive(session)) return;
        try {
            listener.onCompleted();
        } catch (RuntimeException error) {
            Log.w(TAG, "Falha ao finalizar o progresso da narração", error);
        }
    }

    private void notifyError(ProgressListener listener, int session, String message) {
        if (listener == null || !isSessionActive(session)) return;
        try {
            listener.onError(message);
        } catch (RuntimeException error) {
            Log.w(TAG, "Falha ao informar erro da narração", error);
        }
    }

    private int writeAudio(AudioTrack track, short[] samples) {
        int offset = 0;
        while (offset < samples.length) {
            int written = track.write(samples, offset, samples.length - offset,
                    AudioTrack.WRITE_BLOCKING);
            if (written <= 0) break;
            offset += written;
        }
        return offset;
    }

    private void waitForPlaybackDrain(AudioTrack track, int startFrame, int framesWritten,
                                      int session, int bufferSize, int sampleRate) {
        if (framesWritten <= 0) return;
        long bufferDuration = Math.max(450L, (long) bufferSize * 1000L / (sampleRate * 2L) + 180L);
        long deadline = SystemClock.uptimeMillis() + Math.min(1200L, bufferDuration);
        while (isSessionActive(session) && SystemClock.uptimeMillis() < deadline) {
            int framesPlayed = track.getPlaybackHeadPosition() - startFrame;
            if (framesPlayed >= framesWritten) return;
            try {
                Thread.sleep(16);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private short[] toPcm(float[] samples, int sampleRate) {
        short[] pcm = new short[samples.length];
        for (int index = 0; index < samples.length; index++) {
            float source = samples[index];
            if (!Float.isFinite(source)) source = 0.0f;
            float sample = Math.max(-1.0f, Math.min(1.0f, source));
            pcm[index] = (short) (sample * Short.MAX_VALUE);
        }
        applyEdgeFade(pcm, sampleRate);
        return pcm;
    }

    private void applyEdgeFade(short[] samples, int sampleRate) {
        if (samples.length < 2 || sampleRate <= 0) return;
        int fadeSamples = Math.min(samples.length / 2,
                Math.max(1, sampleRate * CHUNK_EDGE_FADE_MS / 1000));
        for (int index = 0; index < fadeSamples; index++) {
            float progress = (index + 1.0f) / (fadeSamples + 1.0f);
            float gain = (float) (0.5 - 0.5 * Math.cos(Math.PI * progress));
            samples[index] = (short) (samples[index] * gain);
            int tailIndex = samples.length - 1 - index;
            samples[tailIndex] = (short) (samples[tailIndex] * gain);
        }
    }

    private List<String> splitText(String text) {
        List<String> parts = new ArrayList<>();
        String normalized = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        boolean firstSegment = true;
        while (start < normalized.length()) {
            int maxCharacters = firstSegment ? FIRST_SEGMENT_CHARACTERS
                    : MAX_SEGMENT_CHARACTERS;
            int limit = Math.min(normalized.length(), start + maxCharacters);
            int splitAt = limit == normalized.length()
                    ? limit : findNaturalSplit(normalized, start, limit);
            if (splitAt <= start) splitAt = limit;
            String part = normalized.substring(start, splitAt).trim();
            if (!part.isEmpty()) parts.add(part);
            start = splitAt;
            while (start < normalized.length() && normalized.charAt(start) == ' ') start++;
            firstSegment = false;
        }
        return parts;
    }

    private int findNaturalSplit(String text, int start, int limit) {
        int sentenceSplit = findLastBoundary(text, start, limit, true);
        if (sentenceSplit > start) return sentenceSplit;
        int clauseSplit = findLastBoundary(text, start, limit, false);
        if (clauseSplit >= start + MIN_FALLBACK_SEGMENT_CHARACTERS) return clauseSplit;
        int wordSplit = text.lastIndexOf(" ", limit - 1);
        if (wordSplit >= start + MIN_FALLBACK_SEGMENT_CHARACTERS) return wordSplit;
        return limit;
    }

    private int findLastBoundary(String text, int start, int limit, boolean sentenceOnly) {
        for (int index = limit - 1; index >= start; index--) {
            char character = text.charAt(index);
            boolean isBoundary = sentenceOnly ? isSentenceBoundary(character)
                    : isClauseBoundary(character);
            if (!isBoundary) continue;
            int end = index + 1;
            while (end < text.length() && isClosingPunctuation(text.charAt(end))) end++;
            return end;
        }
        return -1;
    }

    private boolean isSentenceBoundary(char character) {
        return character == '.' || character == '!' || character == '?' || character == '…';
    }

    private boolean isClauseBoundary(char character) {
        return character == ',' || character == ';' || character == ':' || character == '—';
    }

    private boolean isClosingPunctuation(char character) {
        return character == '”' || character == '"' || character == ')' || character == ']';
    }

    private static final class AudioChunk {
        final short[] samples;
        final int segmentIndex;
        final boolean startsSegment;
        final boolean end;

        AudioChunk(short[] samples, int segmentIndex, boolean startsSegment, boolean end) {
            this.samples = samples;
            this.segmentIndex = segmentIndex;
            this.startsSegment = startsSegment;
            this.end = end;
        }

        static AudioChunk end() {
            return new AudioChunk(new short[0], -1, false, true);
        }
    }

    private static final class SystemUtterance {
        final String id;
        final String text;
        final int session;
        final int segmentIndex;
        final boolean startsSegment;
        final ProgressListener listener;
        final VoiceLanguage language;
        boolean last;

        SystemUtterance(String id, String text, int session, int segmentIndex,
                        boolean startsSegment, ProgressListener listener, VoiceLanguage language) {
            this.id = id;
            this.text = text;
            this.session = session;
            this.segmentIndex = segmentIndex;
            this.startsSegment = startsSegment;
            this.listener = listener;
            this.language = language;
        }
    }

    private void stopAudio() {
        AudioTrack track;
        synchronized (audioLock) {
            track = audioTrack;
            audioTrack = null;
        }
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    private void stopSystemTts() {
        systemUtterances.clear();
        synchronized (systemTtsLock) {
            if (systemTts == null) return;
            try {
                systemTts.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "Não foi possível parar a voz do sistema", error);
            }
        }
    }

    private void clearSystemUtterances(int session) {
        for (String id : systemUtterances.keySet()) {
            SystemUtterance utterance = systemUtterances.get(id);
            if (utterance != null && utterance.session == session) systemUtterances.remove(id);
        }
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) return;
        boolean release = false;
        synchronized (audioLock) {
            if (audioTrack == track) {
                audioTrack = null;
                release = true;
            }
        }
        if (release) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        stop();
        playbackExecutor.shutdownNow();
        generationExecutor.shutdownNow();
        systemSpeechExecutor.shutdownNow();
        synchronized (ttsLock) {
            if (tts != null) tts.release();
            tts = null;
        }
        TextToSpeech voice;
        synchronized (systemTtsLock) {
            voice = systemTts;
            systemTts = null;
            systemTtsReady = false;
            systemTtsLock.notifyAll();
        }
        if (voice != null) {
            try {
                voice.shutdown();
            } catch (RuntimeException error) {
                Log.w(TAG, "Não foi possível encerrar a voz do sistema", error);
            }
        }
    }
}
