package br.com.folio;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/** Manages the optional, private on-device language model. */
public final class LocalModelManager {
    public static final String MODEL_NAME = "Qwen3 0.6B — IA local";
    public static final String MODEL_FILE_NAME = "qwen3-0.6b-int4.litertlm";
    public static final long MODEL_SIZE_BYTES = 344_437_808L;
    public static final long REQUIRED_FREE_SPACE_BYTES = MODEL_SIZE_BYTES + 128L * 1024L * 1024L;

    private static final String MODEL_SHA256 =
            "e3e290109da4388d65a17510a0c66af91c8039f52d2c465868dbc43c09a776cf";
    private static final String MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/"
                    + "8414150f2e9dcc82449bcc9c5abc404b399a4d06/"
                    + "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true";
    private static final String MODELS_DIRECTORY = "models";
    private static final String VERIFIED_FILE_SUFFIX = ".sha256";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250L;

    private static volatile LocalModelManager instance;

    public enum State {
        NOT_INSTALLED,
        DOWNLOADING,
        VERIFYING,
        READY,
        FAILED
    }

    public interface Listener {
        void onModelStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final State state;
        public final long downloadedBytes;
        public final long totalBytes;
        public final String detail;

        private Snapshot(State state, long downloadedBytes, long totalBytes, String detail) {
            this.state = state;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.detail = detail;
        }

        public int progressPercent() {
            if (totalBytes <= 0) return 0;
            return (int) Math.max(0, Math.min(100, (downloadedBytes * 100L) / totalBytes));
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object lock = new Object();

    private State state;
    private long downloadedBytes;
    private long totalBytes = MODEL_SIZE_BYTES;
    private String detail;
    private boolean cancelRequested;
    private HttpsURLConnection activeConnection;

    public static LocalModelManager getInstance(Context context) {
        LocalModelManager current = instance;
        if (current != null) return current;
        synchronized (LocalModelManager.class) {
            current = instance;
            if (current == null) {
                current = new LocalModelManager(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    private LocalModelManager(Context context) {
        appContext = context;
        if (hasVerifiedModel()) {
            state = State.READY;
            downloadedBytes = MODEL_SIZE_BYTES;
            detail = "IA local pronta para usar";
        } else {
            state = State.NOT_INSTALLED;
            detail = "IA local ainda não foi baixada";
        }
    }

    public boolean isRuntimeSupported() {
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public File getModelFile() {
        return new File(modelsDirectory(), MODEL_FILE_NAME);
    }

    public boolean isModelReady() {
        synchronized (lock) {
            return state == State.READY && hasVerifiedModel();
        }
    }

    /** Rechecks the complete model digest before passing it to the native runtime. */
    public boolean verifyModelForUse() {
        synchronized (lock) {
            if (state != State.READY || !hasVerifiedModel()) return false;
        }
        if (matchesExpectedModelDigest(getModelFile())) return true;

        Snapshot snapshot;
        synchronized (lock) {
            deleteIfExists(getModelFile());
            deleteIfExists(verificationFile());
            state = State.NOT_INSTALLED;
            downloadedBytes = 0;
            totalBytes = MODEL_SIZE_BYTES;
            detail = "O arquivo da IA falhou na conferência e foi removido";
            snapshot = snapshotLocked();
        }
        dispatch(snapshot);
        return false;
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            if (state == State.READY && !hasVerifiedModel()) {
                state = State.NOT_INSTALLED;
                downloadedBytes = 0;
                detail = "O arquivo da IA precisa ser baixado novamente";
            }
            return snapshotLocked();
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        dispatch(listener, getSnapshot());
    }

    public void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public void startDownload() {
        Snapshot snapshot;
        synchronized (lock) {
            if (!isRuntimeSupported()) {
                state = State.FAILED;
                detail = "A IA local precisa de um aparelho Android de 64 bits";
                snapshot = snapshotLocked();
            } else if (state == State.DOWNLOADING || state == State.VERIFYING) {
                return;
            } else if (hasVerifiedModel()) {
                state = State.READY;
                downloadedBytes = MODEL_SIZE_BYTES;
                totalBytes = MODEL_SIZE_BYTES;
                detail = "IA local pronta para usar";
                snapshot = snapshotLocked();
            } else if (!hasEnoughStorage()) {
                state = State.FAILED;
                detail = "Libere pelo menos " + formatBytes(REQUIRED_FREE_SPACE_BYTES)
                        + " de espaço para baixar a IA";
                snapshot = snapshotLocked();
            } else {
                cancelRequested = false;
                downloadedBytes = 0;
                totalBytes = MODEL_SIZE_BYTES;
                state = State.DOWNLOADING;
                detail = "Preparando download seguro...";
                downloadExecutor.submit(this::downloadModel);
                snapshot = snapshotLocked();
            }
        }
        dispatch(snapshot);
    }

    public void cancelDownload() {
        Snapshot snapshot;
        HttpsURLConnection connection;
        synchronized (lock) {
            if (state != State.DOWNLOADING) return;
            cancelRequested = true;
            detail = "Cancelando download...";
            snapshot = snapshotLocked();
            connection = activeConnection;
        }
        dispatch(snapshot);
        if (connection != null) connection.disconnect();
    }

    public boolean deleteModel() {
        Snapshot snapshot;
        boolean removed;
        synchronized (lock) {
            if (state == State.DOWNLOADING || state == State.VERIFYING) return false;
            removed = deleteIfExists(getModelFile());
            removed |= deleteIfExists(verificationFile());
            removed |= deleteIfExists(partFile());
            state = State.NOT_INSTALLED;
            downloadedBytes = 0;
            totalBytes = MODEL_SIZE_BYTES;
            detail = "IA local removida deste aparelho";
            snapshot = snapshotLocked();
        }
        dispatch(snapshot);
        return removed;
    }

    private void downloadModel() {
        File part = partFile();
        HttpsURLConnection connection = null;
        boolean completed = false;
        try {
            File directory = modelsDirectory();
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Não foi possível criar a pasta da IA local");
            }
            if (part.exists() && !part.delete()) {
                throw new IOException("Não foi possível limpar um download incompleto");
            }
            File model = getModelFile();
            if (model.exists() && !model.delete()) {
                throw new IOException("Não foi possível substituir um modelo inválido");
            }
            deleteIfExists(verificationFile());

            connection = (HttpsURLConnection) new URL(MODEL_DOWNLOAD_URL).openConnection();
            synchronized (lock) {
                activeConnection = connection;
            }
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Folio-Android/1.9");
            throwIfCancelled();
            int responseCode = connection.getResponseCode();
            if (responseCode < HttpsURLConnection.HTTP_OK
                    || responseCode >= HttpsURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("O servidor respondeu com erro " + responseCode);
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("O download da IA precisa usar HTTPS");
            }
            long responseLength = connection.getContentLengthLong();
            if (responseLength > 0 && responseLength != MODEL_SIZE_BYTES) {
                throw new IOException("O arquivo disponível não é a versão esperada da IA");
            }

            publish(State.DOWNLOADING, 0, MODEL_SIZE_BYTES, "Baixando IA local...");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytesRead = 0;
            long lastProgressAt = 0;
            byte[] header = new byte[8];
            int headerBytes = 0;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    throwIfCancelled();
                    if (bytesRead + count > MODEL_SIZE_BYTES) {
                        throw new IOException("O arquivo baixado é maior do que o esperado");
                    }
                    if (headerBytes < header.length) {
                        int copied = Math.min(count, header.length - headerBytes);
                        System.arraycopy(buffer, 0, header, headerBytes, copied);
                        headerBytes += copied;
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    bytesRead += count;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        publish(State.DOWNLOADING, bytesRead, MODEL_SIZE_BYTES,
                                "Baixando IA local...");
                        lastProgressAt = now;
                    }
                }
                output.flush();
                output.getFD().sync();
            }
            throwIfCancelled();
            if (bytesRead != MODEL_SIZE_BYTES) {
                throw new IOException("O download terminou incompleto");
            }
            if (headerBytes != header.length || !hasLiteRtLmHeader(header)) {
                throw new IOException("O arquivo baixado não é um modelo LiteRT-LM válido");
            }

            publish(State.VERIFYING, bytesRead, MODEL_SIZE_BYTES, "Verificando o arquivo...");
            throwIfCancelled();
            if (!MODEL_SHA256.equals(bytesToHex(digest.digest()))) {
                throw new IOException("A verificação de segurança do arquivo falhou");
            }
            throwIfCancelled();
            if (!part.renameTo(model)) {
                throw new IOException("Não foi possível finalizar o download da IA");
            }
            writeVerificationFile();
            completed = true;
            publish(State.READY, MODEL_SIZE_BYTES, MODEL_SIZE_BYTES, "IA local pronta para usar");
        } catch (DownloadCanceledException ignored) {
            deleteIfExists(part);
            if (!completed) {
                deleteIfExists(getModelFile());
                deleteIfExists(verificationFile());
            }
            publish(State.NOT_INSTALLED, 0, MODEL_SIZE_BYTES, "Download cancelado");
        } catch (IOException | NoSuchAlgorithmException error) {
            deleteIfExists(part);
            if (!completed) {
                deleteIfExists(getModelFile());
                deleteIfExists(verificationFile());
            }
            if (isCancellationRequested()) {
                publish(State.NOT_INSTALLED, 0, MODEL_SIZE_BYTES, "Download cancelado");
            } else {
                publish(State.FAILED, 0, MODEL_SIZE_BYTES,
                        "Não foi possível baixar a IA: " + safeMessage(error));
            }
        } finally {
            if (connection != null) connection.disconnect();
            synchronized (lock) {
                if (activeConnection == connection) activeConnection = null;
                cancelRequested = false;
                if (!completed && state == State.VERIFYING && !hasVerifiedModel()) {
                    state = State.FAILED;
                }
            }
        }
    }

    private void throwIfCancelled() throws DownloadCanceledException {
        synchronized (lock) {
            if (cancelRequested) throw new DownloadCanceledException();
        }
    }

    private boolean isCancellationRequested() {
        synchronized (lock) {
            return cancelRequested;
        }
    }

    private boolean hasEnoughStorage() {
        return appContext.getFilesDir().getUsableSpace() >= REQUIRED_FREE_SPACE_BYTES;
    }

    private boolean hasVerifiedModel() {
        File model = getModelFile();
        File verified = verificationFile();
        if (!model.isFile() || model.length() != MODEL_SIZE_BYTES || !verified.isFile()) return false;
        try (FileInputStream input = new FileInputStream(verified)) {
            byte[] bytes = new byte[MODEL_SHA256.length()];
            int count = input.read(bytes);
            return count == MODEL_SHA256.length()
                    && MODEL_SHA256.equals(new String(bytes, "US-ASCII"));
        } catch (IOException error) {
            return false;
        }
    }

    private boolean matchesExpectedModelDigest(File model) {
        if (!model.isFile() || model.length() != MODEL_SIZE_BYTES) return false;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(model))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            long bytesRead = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
                bytesRead += count;
            }
            return bytesRead == MODEL_SIZE_BYTES
                    && MODEL_SHA256.equals(bytesToHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException error) {
            return false;
        }
    }

    private void writeVerificationFile() throws IOException {
        File verification = verificationFile();
        try (FileOutputStream output = new FileOutputStream(verification, false)) {
            output.write(MODEL_SHA256.getBytes("US-ASCII"));
            output.flush();
            output.getFD().sync();
        }
    }

    private File modelsDirectory() {
        return new File(appContext.getFilesDir(), MODELS_DIRECTORY);
    }

    private File verificationFile() {
        return new File(modelsDirectory(), MODEL_FILE_NAME + VERIFIED_FILE_SUFFIX);
    }

    private File partFile() {
        return new File(modelsDirectory(), MODEL_FILE_NAME + ".part");
    }

    private boolean deleteIfExists(File file) {
        return !file.exists() || file.delete();
    }

    private void publish(State newState, long downloaded, long total, String newDetail) {
        Snapshot snapshot;
        synchronized (lock) {
            state = newState;
            downloadedBytes = downloaded;
            totalBytes = total;
            detail = newDetail;
            snapshot = snapshotLocked();
        }
        dispatch(snapshot);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(state, downloadedBytes, totalBytes, detail);
    }

    private void dispatch(Snapshot snapshot) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onModelStateChanged(snapshot);
        });
    }

    private void dispatch(Listener listener, Snapshot snapshot) {
        mainHandler.post(() -> listener.onModelStateChanged(snapshot));
    }

    private static boolean hasLiteRtLmHeader(byte[] header) {
        byte[] expected = {'L', 'I', 'T', 'E', 'R', 'T', 'L', 'M'};
        if (header.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (header[index] != expected[index]) return false;
        }
        return true;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "erro desconhecido" : message;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return bytes + " B";
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0d * 1024.0d));
    }

    private static final class DownloadCanceledException extends IOException {
    }
}
