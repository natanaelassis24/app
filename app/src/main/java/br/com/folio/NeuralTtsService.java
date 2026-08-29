package br.com.folio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

public final class NeuralTtsService {
    public enum VoiceLanguage {
        PORTUGUESE("português", "pt", new Locale("pt", "BR")),
        ENGLISH("inglês", "en", Locale.US);

        final String displayName;
        final String modelCode;
        final Locale locale;

        VoiceLanguage(String displayName, String modelCode, Locale locale) {
            this.displayName = displayName;
            this.modelCode = modelCode;
            this.locale = locale;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getModelCode() {
            return modelCode;
        }

        public Locale getLocale() {
            return locale;
        }

        public String getPreferenceKey() {
            return locale.toLanguageTag();
        }

        public static VoiceLanguage fromIndex(int index) {
            VoiceLanguage[] languages = values();
            return index >= 0 && index < languages.length ? languages[index] : PORTUGUESE;
        }
    }

    public interface ProgressListener {
        void onSegmentStarted(int segmentIndex);
        void onCompleted();
        void onError(String message);
    }

    private static final String TAG = "FolioNeuralTts";
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
    private final ExecutorService playbackExecutor = createBoundedExecutor();
    private final ExecutorService generationExecutor = createBoundedExecutor();
    private final AtomicInteger playbackSession = new AtomicInteger();
    private final Object audioLock = new Object();
    private final Object ttsLock = new Object();
    private OfflineTts tts;
    private AudioTrack audioTrack;
    private volatile VoiceLanguage voiceLanguage = VoiceLanguage.PORTUGUESE;
    private volatile float speechSpeed = DEFAULT_SPEECH_SPEED;
    private volatile boolean closed;

    private static ExecutorService createBoundedExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public NeuralTtsService(File modelDirectory) {
        if (modelDirectory == null || !modelDirectory.isDirectory()) {
            throw new IllegalArgumentException("A voz local não está instalada");
        }
        OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
        supertonic.setDurationPredictor(modelPath(modelDirectory, "duration_predictor.int8.onnx"));
        supertonic.setTextEncoder(modelPath(modelDirectory, "text_encoder.int8.onnx"));
        supertonic.setVectorEstimator(modelPath(modelDirectory, "vector_estimator.int8.onnx"));
        supertonic.setVocoder(modelPath(modelDirectory, "vocoder.int8.onnx"));
        supertonic.setTtsJson(modelPath(modelDirectory, "tts.json"));
        supertonic.setUnicodeIndexer(modelPath(modelDirectory, "unicode_indexer.bin"));
        supertonic.setVoiceStyle(modelPath(modelDirectory, "voice.bin"));

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
        // A null AssetManager makes Sherpa load the validated model files from private storage.
        tts = new OfflineTts(null, config);
    }

    private static String modelPath(File directory, String fileName) {
        File file = new File(directory, fileName);
        if (!file.isFile()) throw new IllegalArgumentException("Arquivo de voz ausente: " + fileName);
        return file.getAbsolutePath();
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
        float selectedSpeechSpeed = speechSpeed;
        VoiceLanguage selectedVoiceLanguage = voiceLanguage;
        try {
            playbackExecutor.execute(() -> play(session, cleanSegments, listener,
                    selectedSpeechSpeed, selectedVoiceLanguage));
        } catch (RuntimeException error) {
            Log.e(TAG, "Não foi possível iniciar a reprodução", error);
            notifyError(listener, session, "Não foi possível iniciar a voz selecionada.");
        }
    }

    public void stop() {
        playbackSession.incrementAndGet();
        stopAudio();
    }

    private void play(int session, List<String> segments, ProgressListener listener,
                      float speechSpeed, VoiceLanguage voiceLanguage) {
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
            generationExecutor.execute(() -> generateAudio(session, segments, audioQueue,
                    speechSpeed, voiceLanguage));
            track.setVolume(1.0f);
            List<AudioChunk> prebuffer = new ArrayList<>();
            int bufferedSamples = 0;
            boolean reachedEnd = false;
            int prebufferTargetSamples = sampleRate * PLAYBACK_PREBUFFER_MS / 1000;
            while (isSessionActive(session) && bufferedSamples < prebufferTargetSamples) {
                AudioChunk chunk = audioQueue.poll(250, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                if (chunk.errorMessage != null) {
                    notifyError(listener, session, chunk.errorMessage);
                    return;
                }
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
                if (chunk.errorMessage != null) {
                    notifyError(listener, session, chunk.errorMessage);
                    return;
                }
                if (chunk.end) {
                    waitForPlaybackDrain(track, playbackStartFrame, framesWritten, session, bufferSize,
                            sampleRate);
                    notifyCompleted(listener, session);
                    break;
                }
                notifySegmentStarted(listener, session, chunk);
                framesWritten += writeAudio(track, chunk.samples);
            }
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Memória insuficiente para gerar ou reproduzir voz", error);
            notifyError(listener, session, "Memória insuficiente para gerar a voz local.");
            cancelPlaybackSession(session);
        } catch (Exception | LinkageError error) {
            Log.e(TAG, "Falha ao gerar ou reproduzir voz", error);
            notifyError(listener, session, "Não foi possível gerar a voz local.");
            cancelPlaybackSession(session);
        } finally {
            releaseTrack(track);
        }
    }

    private void generateAudio(int session, List<String> segments,
                               BlockingQueue<AudioChunk> audioQueue, float speechSpeed,
                               VoiceLanguage voiceLanguage) {
        try {
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                if (!isSessionActive(session)) return;
                boolean[] hasStartedSegment = {false};
                for (String part : splitText(segments.get(segmentIndex))) {
                    if (!isSessionActive(session)) return;
                    if (!generateIntoQueue(session, part, audioQueue, segmentIndex,
                            hasStartedSegment, speechSpeed, voiceLanguage)) return;
                }
            }
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Memória insuficiente ao gerar trecho da voz", error);
            offerAudio(session, audioQueue, AudioChunk.failure(
                    "Memória insuficiente para gerar a voz local."));
        } catch (Exception | LinkageError error) {
            Log.e(TAG, "Falha ao gerar trecho da voz", error);
            offerAudio(session, audioQueue, AudioChunk.failure(
                    "Não foi possível gerar a voz local."));
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

    private void cancelPlaybackSession(int session) {
        if (playbackSession.compareAndSet(session, session + 1)) stopAudio();
    }

    private int sampleRate() {
        synchronized (ttsLock) {
            if (tts == null) throw new IllegalStateException("Voz local indisponível");
            return tts.sampleRate();
        }
    }

    private boolean generateIntoQueue(int session, String text, BlockingQueue<AudioChunk> audioQueue,
                                      int segmentIndex, boolean[] hasStartedSegment,
                                      float speechSpeed, VoiceLanguage voiceLanguage) {
        AudioChunk chunk;
        synchronized (ttsLock) {
            if (tts == null || !isSessionActive(session)) return false;
            GeneratedAudio generated = generateNarratorAudio(text, speechSpeed, voiceLanguage);
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

    private GeneratedAudio generateNarratorAudio(String text, float speechSpeed,
                                                  VoiceLanguage voiceLanguage) {
        GenerationConfig config = new GenerationConfig();
        config.setSid(NARRATOR_SPEAKER_ID);
        config.setNumSteps(NARRATOR_NUM_STEPS);
        config.setSpeed(speechSpeed);
        config.setExtra(Collections.singletonMap("lang", voiceLanguage.getModelCode()));
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
        final String errorMessage;

        AudioChunk(short[] samples, int segmentIndex, boolean startsSegment, boolean end) {
            this(samples, segmentIndex, startsSegment, end, null);
        }

        AudioChunk(short[] samples, int segmentIndex, boolean startsSegment, boolean end,
                   String errorMessage) {
            this.samples = samples;
            this.segmentIndex = segmentIndex;
            this.startsSegment = startsSegment;
            this.end = end;
            this.errorMessage = errorMessage;
        }

        static AudioChunk end() {
            return new AudioChunk(new short[0], -1, false, true);
        }

        static AudioChunk failure(String message) {
            return new AudioChunk(new short[0], -1, false, false, message);
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
        synchronized (ttsLock) {
            if (tts != null) tts.release();
            tts = null;
        }
    }
}
