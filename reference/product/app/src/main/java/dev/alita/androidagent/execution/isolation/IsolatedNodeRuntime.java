package dev.alita.androidagent.execution.isolation;

import android.content.Context;
import android.os.*;
import dev.alita.androidagent.execution.IsolatedProviderProcess;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Validated execution facility only. Callers still require host admission and Gateway authority. */
public final class IsolatedNodeRuntime {
    private static final Semaphore INSTANCE = new Semaphore(1);
    private final Context context;
    public IsolatedNodeRuntime(Context context) { this.context = context.getApplicationContext(); }
    public java.lang.Process launch(byte[] script, String scriptSha256, BooleanSupplier cancelled,
                                    Consumer<Bundle> identity, int wallTimeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("launch requires a worker thread");
        byte[] source = script.clone();
        if (source.length < 1 || source.length > IsolatedNodeService.MAX_SCRIPT_BYTES || !hash(source).equals(scriptSha256))
            throw new IOException("provider script pin rejected");
        for (byte value : source) if (value == 0) throw new IOException("provider script contains NUL");
        if (wallTimeoutMs < 100 || wallTimeoutMs > 60000) throw new IOException("wall timeout rejected");
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("launch cancelled");
        if (!INSTANCE.tryAcquire()) throw new IOException("isolated provider instance busy");
        ArrayList<ParcelFileDescriptor> libraries = new ArrayList<>();
        IsolatedNodeEndpoint endpoint = null;
        boolean leaseTransferred = false;
        try {
            JSONObject manifest;
            try (InputStream stream = context.getAssets().open("isolated-provider-runtime.json")) {
                byte[] bytes = boundedRead(stream, 65536);
                manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
            if (!"24.18.0".equals(manifest.getString("node")) || manifest.getInt("version") != 1)
                throw new IOException("isolated runtime version rejected");
            Map<String, String> original = new HashMap<>();
            byte[] originalBytes;
            try (InputStream stream = context.getAssets().open("zcode-runtime/payload-SHA256SUMS.txt")) { originalBytes = boundedRead(stream, 65536); }
            if (!hash(originalBytes).equals(manifest.getString("payloadManifestSha256"))) throw new IOException("runtime manifest pin changed");
            for (String row : new String(originalBytes, StandardCharsets.UTF_8).split("\n")) {
                if (!row.isBlank()) { String[] fields = row.split("  ", 2); if (fields.length != 2) throw new IOException("runtime hash format"); original.put(fields[1].trim(), fields[0]); }
            }
            JSONArray entries = manifest.getJSONArray("libraries");
            if (entries.length() != 10) throw new IOException("runtime library count");
            String[] names = new String[10];
            for (int i = 0; i < 10; i++) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("launch cancelled");
                JSONObject entry = entries.getJSONObject(i); names[i] = entry.getString("name");
                String expected = entry.getString("sha256");
                if (!expected.equals(original.get(entry.getString("source")))) throw new IOException("runtime source pin mismatch");
                libraries.add(openVerified(entry.getString("packagedName"), expected));
            }
            try (ParcelFileDescriptor shim = openVerified("libisolatednode.so", manifest.getString("shimSha256"))) { }
            Bundle request = new Bundle(); request.putStringArray("names", names);
            request.putByteArray("scriptUtf8", source); request.putInt("wallTimeoutMs", wallTimeoutMs);
            endpoint = new IsolatedNodeEndpoint(context, request, libraries, identity, INSTANCE::release);
            leaseTransferred = true;
            return IsolatedProviderProcess.launch(endpoint, 10000, cancelled);
        } finally {
            for (ParcelFileDescriptor fd : libraries) try { fd.close(); } catch (IOException ignored) { }
            if (!leaseTransferred) INSTANCE.release();
        }
    }
    private ParcelFileDescriptor openVerified(String name, String expected) throws Exception {
        if (!name.matches("lib[A-Za-z0-9_+.-]+\\.so") || !expected.matches("[a-f0-9]{64}")) throw new IOException("native pin format");
        File file = new File(context.getApplicationInfo().nativeLibraryDir, name);
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            // Hash a duplicate descriptor, then pass the exact already-open original to Binder.
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                if (!hex(digest.digest()).equals(expected)) throw new IOException("native runtime integrity failed");
            }
            return fd;
        } catch (Exception error) { fd.close(); throw error; }
    }
    public static String hash(byte[] value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static byte[] boundedRead(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("runtime manifest limit"); output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static String hex(byte[] value) { StringBuilder result = new StringBuilder(); for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item)); return result.toString(); }
}
