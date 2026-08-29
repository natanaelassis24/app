package br.com.folio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Downloads and verifies Folio's optional Supertonic voice data.
 *
 * <p>The Sherpa runtime stays inside the APK. The public Supertonic M5 weights are downloaded
 * into private app storage only after the person asks to use a voice. The model itself is
 * multilingual, so one verified package serves Portuguese and English without duplicating the
 * same large ONNX files.</p>
 */
public final class VoiceModelManager {
    private static final String TAG = "FolioVoiceModel";
    public static final String MODEL_NAME = "Voz neural Supertonic M5";
    public static final long MODEL_DATA_SIZE_BYTES = 145_295_768L;
    public static final long REQUIRED_FREE_SPACE_BYTES = MODEL_DATA_SIZE_BYTES
            + 256L * 1024L * 1024L;

    private static final String PACKAGE_VERSION = "supertonic-3-int8-2026-05-11";
    private static final String STORAGE_DIRECTORY = "tts-voices";
    private static final String ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/"
            + "download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2";
    private static final String VERIFICATION_FILE = ".folio-verified";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_METADATA_ENTRY_BYTES = 1024L * 1024L;

    private static final ModelFile[] REQUIRED_FILES = {
            new ModelFile("duration_predictor.int8.onnx", 3_700_147L,
                    "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
            new ModelFile("text_encoder.int8.onnx", 36_416_150L,
                    "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
            new ModelFile("vector_estimator.int8.onnx", 78_400_833L,
                    "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
            new ModelFile("vocoder.int8.onnx", 25_991_073L,
                    "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
            new ModelFile("unicode_indexer.bin", 262_144L,
                    "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
            new ModelFile("tts.json", 8_253L,
                    "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
            new ModelFile("voice.bin", 517_168L,
                    "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8")
    };

    private static volatile VoiceModelManager instance;

    public enum State {
        NOT_INSTALLED,
        DOWNLOADING,
        VERIFYING,
        READY,
        FAILED
    }

    public interface Listener {
        void onVoiceModelStateChanged(Snapshot snapshot);
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
            if (totalBytes <= 0L) return 0;
            return (int) Math.max(0L, Math.min(100L, downloadedBytes * 100L / totalBytes));
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object lock = new Object();

    private State state;
    private long downloadedBytes;
    private long totalBytes;
    private String detail;
    private boolean cancelRequested;
    private HttpsURLConnection activeConnection;

    public static VoiceModelManager getInstance(Context context) {
        VoiceModelManager current = instance;
        if (current != null) return current;
        synchronized (VoiceModelManager.class) {
            current = instance;
            if (current == null) {
                current = new VoiceModelManager(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    private VoiceModelManager(Context context) {
        appContext = context;
        totalBytes = MODEL_DATA_SIZE_BYTES;
        if (hasReadyPackage()) {
            state = State.READY;
            downloadedBytes = MODEL_DATA_SIZE_BYTES;
            detail = "Voz local pronta para usar";
        } else {
            state = State.NOT_INSTALLED;
            detail = "A voz local ainda não foi baixada";
        }
    }

    public File getModelDirectory() {
        return new File(packageRoot(), PACKAGE_VERSION);
    }

    public boolean isModelReady() {
        synchronized (lock) {
            return state == State.READY && hasReadyPackage();
        }
    }

    /** Revalidates every model file before it is handed to the native TTS runtime. */
    public boolean verifyModelForUse() {
        File directory = getModelDirectory();
        try {
            verifyDirectory(directory, true);
            return true;
        } catch (IOException | NoSuchAlgorithmException error) {
            Snapshot snapshot;
            synchronized (lock) {
                if (state == State.DOWNLOADING || state == State.VERIFYING) return false;
                deleteRecursively(directory);
                state = State.NOT_INSTALLED;
                downloadedBytes = 0L;
                totalBytes = MODEL_DATA_SIZE_BYTES;
                detail = "A voz local falhou na conferência e precisa ser baixada novamente";
                snapshot = snapshotLocked();
            }
            dispatch(snapshot);
            return false;
        }
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            if (state == State.READY && !hasReadyPackage()) {
                state = State.NOT_INSTALLED;
                downloadedBytes = 0L;
                totalBytes = MODEL_DATA_SIZE_BYTES;
                detail = "A voz local precisa ser baixada novamente";
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
            if (state == State.DOWNLOADING || state == State.VERIFYING) return;
            if (hasReadyPackage()) {
                state = State.READY;
                downloadedBytes = MODEL_DATA_SIZE_BYTES;
                totalBytes = MODEL_DATA_SIZE_BYTES;
                detail = "Voz local pronta para usar";
                snapshot = snapshotLocked();
            } else {
                cancelRequested = false;
                downloadedBytes = 0L;
                totalBytes = MODEL_DATA_SIZE_BYTES;
                state = State.DOWNLOADING;
                detail = "Preparando o download da voz local...";
                downloadExecutor.execute(this::downloadPackage);
                snapshot = snapshotLocked();
            }
        }
        dispatch(snapshot);
    }

    public void cancelDownload() {
        Snapshot snapshot;
        HttpsURLConnection connection;
        synchronized (lock) {
            if (state != State.DOWNLOADING && state != State.VERIFYING) return;
            cancelRequested = true;
            detail = "Cancelando download da voz...";
            snapshot = snapshotLocked();
            connection = activeConnection;
        }
        dispatch(snapshot);
        if (connection != null) connection.disconnect();
    }

    private void downloadPackage() {
        File archive = archivePartFile();
        File temporaryDirectory = temporaryDirectory();
        File targetDirectory = getModelDirectory();
        HttpsURLConnection connection = null;
        boolean targetWasReplaced = false;
        boolean completed = false;
        try {
            File root = packageRoot();
            if (!root.exists() && !root.mkdirs() && !root.isDirectory()) {
                throw new IOException("Não foi possível criar a pasta da voz local");
            }
            if (!deleteFile(archive)) {
                throw new IOException("Não foi possível limpar um download anterior");
            }
            if (!deleteRecursively(temporaryDirectory)) {
                throw new IOException("Não foi possível preparar o download da voz");
            }
            // A process can be terminated after moving the extracted package but
            // before its verification marker is written. It is not usable then,
            // so reclaim that fixed, incomplete destination before measuring space.
            if (targetDirectory.exists() && !hasReadyPackage()
                    && !deleteRecursively(targetDirectory)) {
                throw new IOException("Não foi possível limpar uma instalação incompleta da voz");
            }
            // Remove temporary files before measuring free space. A canceled or
            // interrupted installation can leave up to one archive plus the
            // extracted model behind, and those bytes are safe to reclaim here.
            if (!hasEnoughStorage()) {
                throw new IOException("Libere pelo menos "
                        + formatBytes(REQUIRED_FREE_SPACE_BYTES)
                        + " para baixar a voz local");
            }

            connection = (HttpsURLConnection) new URL(ARCHIVE_URL).openConnection();
            synchronized (lock) {
                activeConnection = connection;
            }
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Folio-Android/2.00");
            throwIfCancelled();
            int responseCode = connection.getResponseCode();
            if (responseCode < HttpsURLConnection.HTTP_OK
                    || responseCode >= HttpsURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("O servidor respondeu com erro " + responseCode);
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("O download da voz precisa usar HTTPS");
            }
            long responseLength = connection.getContentLengthLong();
            if (responseLength > MAX_ARCHIVE_BYTES) {
                throw new IOException("O arquivo da voz é maior do que o esperado");
            }
            long progressTotal = responseLength > 0L ? responseLength : MODEL_DATA_SIZE_BYTES;
            publish(State.DOWNLOADING, 0L, progressTotal, "Baixando voz local...");

            long bytesRead = 0L;
            long lastProgressAt = 0L;
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    throwIfCancelled();
                    if (bytesRead + count > MAX_ARCHIVE_BYTES) {
                        throw new IOException("O arquivo da voz é maior do que o esperado");
                    }
                    output.write(buffer, 0, count);
                    bytesRead += count;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        publish(State.DOWNLOADING, bytesRead, progressTotal,
                                "Baixando voz local...");
                        lastProgressAt = now;
                    }
                }
                output.flush();
                output.getFD().sync();
            }
            throwIfCancelled();
            if (bytesRead == 0L) throw new IOException("O download da voz veio vazio");
            if (responseLength > 0L && bytesRead != responseLength) {
                throw new IOException("O download da voz terminou incompleto");
            }

            publish(State.VERIFYING, MODEL_DATA_SIZE_BYTES, MODEL_DATA_SIZE_BYTES,
                    "Preparando e conferindo a voz local...");
            extractRequiredFiles(archive, temporaryDirectory);
            throwIfCancelled();
            verifyDirectory(temporaryDirectory, true);
            throwIfCancelled();

            if (!deleteRecursively(targetDirectory)) {
                throw new IOException("Não foi possível atualizar a voz local");
            }
            throwIfCancelled();
            if (!temporaryDirectory.renameTo(targetDirectory)) {
                throw new IOException("Não foi possível finalizar o download da voz");
            }
            targetWasReplaced = true;
            throwIfCancelled();
            writeVerificationFile(targetDirectory);
            throwIfCancelled();
            completed = true;
            deleteFile(archive);
            publish(State.READY, MODEL_DATA_SIZE_BYTES, MODEL_DATA_SIZE_BYTES,
                    "Voz local pronta para usar offline");
        } catch (DownloadCanceledException ignored) {
            deleteFile(archive);
            deleteRecursively(temporaryDirectory);
            if (targetWasReplaced && !completed) deleteRecursively(targetDirectory);
            publish(State.NOT_INSTALLED, 0L, MODEL_DATA_SIZE_BYTES, "Download da voz cancelado");
        } catch (IOException | NoSuchAlgorithmException error) {
            Log.w(TAG, "Falha ao instalar o pacote de voz", error);
            failDownload(archive, temporaryDirectory, targetDirectory, targetWasReplaced, completed,
                    "Não foi possível baixar a voz: " + safeMessage(error));
        } catch (RuntimeException error) {
            Log.w(TAG, "Falha inesperada ao instalar o pacote de voz", error);
            failDownload(archive, temporaryDirectory, targetDirectory, targetWasReplaced, completed,
                    "Não foi possível preparar a voz: " + safeMessage(error));
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Memória insuficiente ao instalar o pacote de voz", error);
            failDownload(archive, temporaryDirectory, targetDirectory, targetWasReplaced, completed,
                    "Memória insuficiente para instalar a voz local");
        } finally {
            if (connection != null) connection.disconnect();
            synchronized (lock) {
                if (activeConnection == connection) activeConnection = null;
                cancelRequested = false;
            }
        }
    }

    private void failDownload(File archive, File temporaryDirectory, File targetDirectory,
                              boolean targetWasReplaced, boolean completed, String failureDetail) {
        deleteFile(archive);
        deleteRecursively(temporaryDirectory);
        if (targetWasReplaced && !completed) deleteRecursively(targetDirectory);
        if (isCancellationRequested()) {
            publish(State.NOT_INSTALLED, 0L, MODEL_DATA_SIZE_BYTES, "Download da voz cancelado");
        } else {
            publish(State.FAILED, 0L, MODEL_DATA_SIZE_BYTES, failureDetail);
        }
    }

    private void extractRequiredFiles(File archive, File destination)
            throws IOException, NoSuchAlgorithmException, DownloadCanceledException {
        if (!destination.mkdirs() && !destination.isDirectory()) {
            throw new IOException("Não foi possível preparar os arquivos da voz");
        }
        boolean[] extracted = new boolean[REQUIRED_FILES.length];
        try (InputStream archiveInput = new BufferedInputStream(new FileInputStream(archive));
             BZip2CompressorInputStream bzipInput = new BZip2CompressorInputStream(archiveInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(bzipInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                throwIfCancelled();
                if (entry.isDirectory()) {
                    if (entry.getSize() != 0L) {
                        throw new IOException("O pacote da voz contém uma pasta inválida");
                    }
                    continue;
                }
                int index = requiredFileIndex(entry.getName());
                if (index < 0) {
                    if (!isAllowedMetadataEntry(entry)) {
                        throw new IOException("O pacote da voz contém conteúdo não esperado");
                    }
                    continue;
                }
                if (extracted[index]) {
                    throw new IOException("O pacote da voz contém arquivos repetidos");
                }
                ModelFile required = REQUIRED_FILES[index];
                if (entry.getSize() != required.size) {
                    throw new IOException("O pacote da voz não tem o tamanho esperado");
                }
                File outputFile = new File(destination, required.name);
                copyEntry(tarInput, outputFile, required.size);
                extracted[index] = true;
            }
        }
        for (int index = 0; index < extracted.length; index++) {
            if (!extracted[index]) {
                throw new IOException("O pacote da voz está incompleto");
            }
        }
    }

    private void copyEntry(TarArchiveInputStream input, File outputFile, long expectedSize)
            throws IOException, DownloadCanceledException {
        long copied = 0L;
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (copied < expectedSize) {
                throwIfCancelled();
                int maxCount = (int) Math.min(buffer.length, expectedSize - copied);
                int count = input.read(buffer, 0, maxCount);
                if (count == -1) throw new IOException("O pacote da voz terminou incompleto");
                output.write(buffer, 0, count);
                copied += count;
            }
            output.flush();
            output.getFD().sync();
        }
        if (copied != expectedSize) throw new IOException("O arquivo da voz está incompleto");
    }

    private void verifyDirectory(File directory, boolean verifyDigest)
            throws IOException, NoSuchAlgorithmException {
        if (!directory.isDirectory()) throw new IOException("A pasta da voz não existe");
        for (ModelFile file : REQUIRED_FILES) {
            File target = new File(directory, file.name);
            if (!target.isFile() || target.length() != file.size) {
                throw new IOException("Um arquivo da voz está ausente ou inválido");
            }
            if (verifyDigest && !file.sha256.equals(calculateSha256(target))) {
                throw new IOException("A conferência de segurança da voz falhou");
            }
        }
    }

    private String calculateSha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return bytesToHex(digest.digest());
    }

    private boolean hasReadyPackage() {
        File directory = getModelDirectory();
        File marker = new File(directory, VERIFICATION_FILE);
        if (!marker.isFile() || marker.length() == 0L) return false;
        try {
            verifyDirectory(directory, false);
            return true;
        } catch (IOException | NoSuchAlgorithmException error) {
            return false;
        }
    }

    private void writeVerificationFile(File directory) throws IOException {
        File marker = new File(directory, VERIFICATION_FILE);
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write(PACKAGE_VERSION.getBytes("US-ASCII"));
            output.flush();
            output.getFD().sync();
        }
    }

    private int requiredFileIndex(String entryName) {
        String name = entryFileName(entryName);
        if (name.isEmpty()) return -1;
        for (int index = 0; index < REQUIRED_FILES.length; index++) {
            if (REQUIRED_FILES[index].name.equals(name)) return index;
        }
        return -1;
    }

    private static boolean isAllowedMetadataEntry(TarArchiveEntry entry) {
        if (entry.getSize() < 0L || entry.getSize() > MAX_METADATA_ENTRY_BYTES) return false;
        String name = entryFileName(entry.getName());
        return "LICENSE".equals(name) || "README.md".equals(name);
    }

    private static String entryFileName(String entryName) {
        if (entryName == null) return "";
        String normalized = entryName.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
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
        // packageRoot() is created only when the first voice is downloaded. Asking a
        // non-existent child directory for its usable space returns zero on some
        // Android versions, which would incorrectly reject every first download.
        // The app's no-backup directory always represents the same private volume.
        File storageDirectory = appContext.getNoBackupFilesDir();
        return storageDirectory != null
                && storageDirectory.getUsableSpace() >= REQUIRED_FREE_SPACE_BYTES;
    }

    private File packageRoot() {
        return new File(appContext.getNoBackupFilesDir(), STORAGE_DIRECTORY);
    }

    private File archivePartFile() {
        return new File(packageRoot(), PACKAGE_VERSION + ".tar.bz2.part");
    }

    private File temporaryDirectory() {
        return new File(packageRoot(), PACKAGE_VERSION + ".installing");
    }

    private static boolean deleteFile(File file) {
        return !file.exists() || (file.isFile() && file.delete());
    }

    private static boolean deleteRecursively(File file) {
        if (!file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
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
            for (Listener listener : listeners) listener.onVoiceModelStateChanged(snapshot);
        });
    }

    private void dispatch(Listener listener, Snapshot snapshot) {
        mainHandler.post(() -> listener.onVoiceModelStateChanged(snapshot));
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) return bytes + " B";
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0d * 1024.0d));
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

    private static final class ModelFile {
        final String name;
        final long size;
        final String sha256;

        ModelFile(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class DownloadCanceledException extends IOException {
    }
}
