package br.com.folio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalLlmService {
    public interface Callback { void onComplete(String result); void onError(Exception error); }

    private static final Object MODEL_COPY_LOCK = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lifecycleLock = new Object();
    private final Object inferenceUseLock = new Object();
    private LlmInference inference;
    private boolean closed;
    private boolean requestInFlight;

    public LocalLlmService(Context context) {
        final String modelFile;
        final File model;
        try {
            modelFile = findModel(context);
            if (modelFile == null) throw new IllegalStateException("Modelo local ausente");
            model = new File(context.getFilesDir(), modelFile);
            copyModelFromAssets(context, modelFile, model);
        } catch (IOException error) {
            throw new IllegalStateException("Não foi possível preparar o modelo local", error);
        }

        LlmInferenceOptions options = LlmInferenceOptions.builder()
            .setModelPath(model.getAbsolutePath())
            .setMaxTokens(1280)
            .build();
        inference = LlmInference.createFromOptions(context, options);
    }

    private String findModel(Context context) throws IOException {
        String[] assets = context.getAssets().list("");
        if (assets == null) throw new IOException("Não foi possível listar os assets do aplicativo");
        for (String file : assets) {
            if (file.endsWith(".task")) return file;
        }
        return null;
    }

    private void copyModelFromAssets(Context context, String modelFile, File destination) throws IOException {
        synchronized (MODEL_COPY_LOCK) {
            if (destination.isFile() && destination.length() > 0) return;
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Não foi possível remover uma cópia inválida do modelo: " + destination);
            }

            File parent = destination.getParentFile();
            if (parent == null) throw new IOException("Diretório de destino do modelo inválido");
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Não foi possível criar o diretório do modelo: " + parent);
            }

            File temporary = File.createTempFile(destination.getName(), ".tmp", parent);
            boolean committed = false;
            try {
                try (InputStream input = context.getAssets().open(modelFile);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                    output.getFD().sync();
                }

                if (!temporary.renameTo(destination)) {
                    throw new IOException("Não foi possível finalizar a cópia do modelo local");
                }
                committed = true;
            } finally {
                if (!committed && temporary.exists() && !temporary.delete()) {
                    temporary.deleteOnExit();
                }
            }
        }
    }

    public boolean identify(String text, Callback callback) {
        return ask("Identifique esta obra. Responda em português com título, autor e uma descrição curta:\n" + text, callback);
    }

    public boolean translate(String text, String language, Callback callback) {
        return ask("Traduza o texto abaixo para " + language + ". Preserve nomes próprios e o sentido. Retorne apenas a tradução:\n" + text, callback);
    }

    public boolean summarize(String text, Callback callback) {
        return ask("Conte a história completa da novela usando somente o texto fornecido. Narre os acontecimentos em ordem, com começo, desenvolvimento e desfecho. Não faça um resumo curto, não fale sobre menus ou sobre a página e não invente fatos. Responda no idioma solicitado. Use parágrafos curtos e frases naturais para leitura em voz alta. Não use listas, títulos, markdown, emojis, URLs ou abreviações difíceis de pronunciar.\n" + text, callback);
    }

    public boolean isBusy() {
        synchronized (lifecycleLock) {
            return requestInFlight;
        }
    }

    private boolean ask(String prompt, Callback callback) {
        synchronized (lifecycleLock) {
            if (closed || inference == null || requestInFlight) return false;
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
        LlmInference currentInference;
        synchronized (lifecycleLock) {
            if (closed || inference == null) {
                finishRequest();
                return;
            }
            currentInference = inference;
        }
        String result = null;
        Exception failure = null;
        try {
            synchronized (inferenceUseLock) {
                synchronized (lifecycleLock) {
                    if (closed || inference != currentInference) return;
                }
                result = currentInference.generateResponse(prompt);
            }
        } catch (Exception error) {
            failure = error;
        } finally {
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
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            requestInFlight = false;
            executor.shutdownNow();
        }
        synchronized (inferenceUseLock) {
            LlmInference currentInference;
            synchronized (lifecycleLock) {
                currentInference = inference;
                inference = null;
            }
            try {
                if (currentInference != null) currentInference.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
