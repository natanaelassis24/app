package br.com.folio;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility facade for Folio's own Supertonic neural narrator.
 *
 * <p>Despite the historical class name, this class does not create or call Android's
 * {@code TextToSpeech} engine. All speech is generated locally by {@link NeuralTtsService}.
 * Keeping this small facade lets the existing language selector continue to work while the
 * app uses the original neural TTS again.</p>
 */
public final class SystemTtsService {
    public enum VoiceLanguage {
        PORTUGUESE(NeuralTtsService.VoiceLanguage.PORTUGUESE),
        ENGLISH(NeuralTtsService.VoiceLanguage.ENGLISH);

        private final NeuralTtsService.VoiceLanguage neuralLanguage;

        VoiceLanguage(NeuralTtsService.VoiceLanguage neuralLanguage) {
            this.neuralLanguage = neuralLanguage;
        }

        public static VoiceLanguage fromIndex(int index) {
            VoiceLanguage[] languages = values();
            return index >= 0 && index < languages.length ? languages[index] : PORTUGUESE;
        }

        public String getDisplayName() {
            return neuralLanguage.getDisplayName();
        }

        public Locale getLocale() {
            return neuralLanguage.getLocale();
        }

        public String getPreferenceKey() {
            return neuralLanguage.getPreferenceKey();
        }

        NeuralTtsService.VoiceLanguage asNeuralLanguage() {
            return neuralLanguage;
        }
    }

    public static final class VoiceOption {
        private final String name;
        private final String label;

        VoiceOption(String name, String label) {
            this.name = name;
            this.label = label;
        }

        public String getName() {
            return name;
        }

        public String getLabel() {
            return label;
        }
    }

    public interface ProgressListener {
        void onSegmentStarted(int segmentIndex);
        void onCompleted();
        void onError(String message);
    }

    public interface InitializationListener {
        void onInitialized(boolean ready);
    }

    private static final String NEURAL_NARRATOR_NAME = "supertonic-m5";
    private static final VoiceOption PORTUGUESE_NARRATOR = new VoiceOption(NEURAL_NARRATOR_NAME,
            "Narrador em português M5 (Supertonic)");
    private static final VoiceOption ENGLISH_NARRATOR = new VoiceOption(NEURAL_NARRATOR_NAME,
            "Narrador em inglês M5 (Supertonic)");
    private final NeuralTtsService neuralTts;
    private volatile boolean closed;

    public SystemTtsService(File modelDirectory) {
        neuralTts = new NeuralTtsService(modelDirectory);
    }

    public void whenInitialized(InitializationListener listener) {
        if (listener != null) listener.onInitialized(isReady());
    }

    public boolean isReady() {
        return !closed;
    }

    public void setVoiceLanguage(VoiceLanguage language) {
        if (language != null && !closed) neuralTts.setVoiceLanguage(language.asNeuralLanguage());
    }

    /** The restored Supertonic narrator is M5; the saved UI choice is kept for compatibility. */
    public void setVoiceName(String name) {
        // There is one curated neural narrator in the restored Folio TTS.
    }

    public void setSpeechSpeed(float speed) {
        if (!closed) neuralTts.setSpeechSpeed(speed);
    }

    public boolean hasInstalledVoice(VoiceLanguage language) {
        return isReady();
    }

    public boolean hasInstalledVoice(VoiceLanguage language, String name) {
        return isReady();
    }

    public List<VoiceOption> getInstalledVoices(VoiceLanguage language) {
        VoiceOption narrator = language == VoiceLanguage.ENGLISH
                ? ENGLISH_NARRATOR : PORTUGUESE_NARRATOR;
        return isReady() ? Collections.singletonList(narrator)
                : Collections.emptyList();
    }

    public void speak(String text) {
        if (!closed) neuralTts.speak(text);
    }

    public void speak(List<String> segments, ProgressListener listener) {
        if (closed) return;
        if (listener == null) {
            neuralTts.speak(segments, null);
            return;
        }
        neuralTts.speak(segments, new NeuralTtsService.ProgressListener() {
            @Override public void onSegmentStarted(int segmentIndex) {
                listener.onSegmentStarted(segmentIndex);
            }

            @Override public void onCompleted() {
                listener.onCompleted();
            }

            @Override public void onError(String message) {
                listener.onError(message);
            }
        });
    }

    public void stop() {
        if (!closed) neuralTts.stop();
    }

    public String noInstalledVoiceMessage(VoiceLanguage language) {
        return "A voz neural local do Folio não pôde ser iniciada.";
    }

    public String voiceSelectionRequiredMessage(VoiceLanguage language) {
        return "A voz neural local do Folio está sendo preparada.";
    }

    public void close() {
        if (closed) return;
        closed = true;
        neuralTts.close();
    }
}
