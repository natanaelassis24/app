package br.com.folio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs the verified optional LiteRT-LM model outside the UI thread. */
public final class LocalLlmService {
    public interface Callback {
        void onComplete(String result);
        void onError(Exception error);
    }

    private static final int MAX_CONTEXT_TOKENS = 1280;
    private static final int MIN_CPU_THREADS = 1;
    private static final int MAX_CPU_THREADS = 4;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lifecycleLock = new Object();
    private final Object inferenceUseLock = new Object();
    private Engine engine;
    private Conversation activeConversation;
    private boolean closed;
    private boolean requestInFlight;

    public LocalLlmService(Context context) {
        Context appContext = context.getApplicationContext();
        LocalModelManager modelManager = LocalModelManager.getInstance(appContext);
        if (!modelManager.isRuntimeSupported()) {
            throw new IllegalStateException("A IA local exige um aparelho Android de 64 bits");
        }
        if (!modelManager.isModelReady() || !modelManager.verifyModelForUse()) {
            throw new IllegalStateException("Modelo local ausente ou não verificado");
        }
        File model = modelManager.getModelFile();
        int threads = preferredThreadCount();
        EngineConfig options = new EngineConfig(
                model.getAbsolutePath(),
                new Backend.CPU(Integer.valueOf(threads)),
                null,
                null,
                Integer.valueOf(MAX_CONTEXT_TOKENS),
                null,
                appContext.getCacheDir().getAbsolutePath());
        Engine loaded = new Engine(options);
        try {
            loaded.initialize();
            engine = loaded;
        } catch (RuntimeException | LinkageError error) {
            try {
                if (loaded.isInitialized()) loaded.close();
            } catch (RuntimeException ignored) {
            }
            throw error;
        }
    }

    private int preferredThreadCount() {
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(MIN_CPU_THREADS, Math.min(MAX_CPU_THREADS, Math.max(1, available - 1)));
    }

    public boolean identify(String text, Callback callback) {
        return ask("Identifique esta obra. Responda em português com título, autor e uma "
                + "descrição curta:\n" + text, callback);
    }

    public boolean translate(String text, String language, Callback callback) {
        return ask("Traduza o texto abaixo para " + language + ". Preserve nomes próprios e "
                + "o sentido. Retorne apenas a tradução:\n" + text, callback);
    }

    public boolean summarize(String text, Callback callback) {
        return ask("Conte a história completa da novela usando somente o texto fornecido. "
                + "Narre os acontecimentos em ordem, com começo, desenvolvimento e desfecho. "
                + "Não faça um resumo curto, não fale sobre menus ou sobre a página e não "
                + "invente fatos. Responda no idioma solicitado. Use parágrafos curtos e "
                + "frases naturais para leitura em voz alta. Não use listas, títulos, markdown, "
                + "emojis, URLs ou abreviações difíceis de pronunciar.\n" + text, callback);
    }

    public boolean isBusy() {
        synchronized (lifecycleLock) {
            return requestInFlight;
        }
    }

    private boolean ask(String prompt, Callback callback) {
        if (callback == null) return false;
        synchronized (lifecycleLock) {
            if (closed || engine == null || requestInFlight) return false;
            requestInFlight = true;
        }
        try {
            executor.execute(() -> generate(prompt, callback));
            return true;
        } catch (RuntimeException error) {
            finishRequest();
            return false;
        }
    }

    private void generate(String prompt, Callback callback) {
        Engine currentEngine;
        Conversation conversation = null;
        synchronized (lifecycleLock) {
            if (closed || engine == null) {
                finishRequest();
                return;
            }
            currentEngine = engine;
        }
        String result = null;
        Exception failure = null;
        try {
            synchronized (inferenceUseLock) {
                synchronized (lifecycleLock) {
                    if (closed || engine != currentEngine) return;
                }
                conversation = currentEngine.createConversation(new ConversationConfig());
                synchronized (lifecycleLock) {
                    if (closed || engine != currentEngine) return;
                    activeConversation = conversation;
                }
                result = conversation.sendMessage(prompt, Collections.emptyMap()).toString();
            }
        } catch (Exception error) {
            failure = error;
        } finally {
            if (conversation != null) {
                synchronized (lifecycleLock) {
                    if (activeConversation == conversation) activeConversation = null;
                }
                try {
                    conversation.close();
                } catch (RuntimeException ignored) {
                }
            }
            finishRequest();
        }

        if (failure == null) {
            String response = result;
            main.post(() -> deliver(() -> callback.onComplete(response)));
        } else {
            Exception error = failure;
            main.post(() -> deliver(() -> callback.onError(error)));
        }
    }

    private void finishRequest() {
        synchronized (lifecycleLock) {
            requestInFlight = false;
        }
    }

    private void deliver(Runnable callback) {
        boolean canDeliver;
        synchronized (lifecycleLock) {
            canDeliver = !closed;
        }
        if (canDeliver) callback.run();
    }

    public void close() {
        Conversation conversationToCancel;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            requestInFlight = false;
            conversationToCancel = activeConversation;
            executor.shutdownNow();
        }
        if (conversationToCancel != null) {
            try {
                conversationToCancel.cancelProcess();
            } catch (RuntimeException ignored) {
            }
        }
        synchronized (inferenceUseLock) {
            Engine currentEngine;
            synchronized (lifecycleLock) {
                currentEngine = engine;
                engine = null;
            }
            try {
                if (currentEngine != null && currentEngine.isInitialized()) currentEngine.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
