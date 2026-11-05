package dev.alita.androidagent.execution;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Process facade for a host-owned isolated transport, not an admission or permission grant.
 * Endpoint must retain cancellation across late connection and report exit only after actual
 * process death. A stop request alone never completes waitFor(). No app-UID fallback exists.
 */
public final class IsolatedProviderProcess extends Process {
    public interface Events {
        void ready();
        void exited(int status);
        void failed(IOException failure);
    }
    public interface Endpoint extends Closeable {
        InputStream stdout();
        InputStream stderr();
        OutputStream stdin();
        /** Nonblocking registration; connection/ready/failure arrive through events. */
        void start(Events events) throws IOException;
        /** Idempotent, nonblocking; must also stop a service connecting after this request. */
        void terminate();
        /** Release binding/transport resources after confirmed death, or before start. */
        @Override void close() throws IOException;
    }

    private final Endpoint endpoint;
    private final CountDownLatch started = new CountDownLatch(1), dead = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean(), released = new AtomicBoolean();
    private volatile boolean ready;
    private volatile IOException failure;
    private volatile int exit;

    private IsolatedProviderProcess(Endpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint);
        Objects.requireNonNull(endpoint.stdout());
        Objects.requireNonNull(endpoint.stderr());
        Objects.requireNonNull(endpoint.stdin());
    }

    public static IsolatedProviderProcess launch(Endpoint endpoint, long startupTimeoutMs)
            throws IOException, InterruptedException {
        return launch(endpoint, startupTimeoutMs, () -> false);
    }

    /** Polls the existing host signal without retaining a callback after launch. */
    public static IsolatedProviderProcess launch(Endpoint endpoint, long startupTimeoutMs, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (startupTimeoutMs < 1 || startupTimeoutMs > 60_000) throw new IllegalArgumentException("startup deadline");
        Objects.requireNonNull(cancelled);
        IsolatedProviderProcess process = new IsolatedProviderProcess(endpoint);
        if (cancelled.getAsBoolean()) {
            try { endpoint.close(); } finally { process.closeStreams(); }
            throw new InterruptedIOException("isolated startup cancelled");
        }
        try {
            endpoint.start(new Events() {
                @Override public void ready() {
                    if (!process.stopping.get() && process.dead.getCount() != 0) process.ready = true;
                    process.started.countDown();
                }
                @Override public void exited(int status) { process.onExit(status); }
                @Override public void failed(IOException error) {
                    process.failure = Objects.requireNonNull(error);
                    process.started.countDown();
                    process.destroy();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs);
            for (;;) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("isolated startup cancelled");
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IOException("isolated startup deadline exceeded");
                if (process.started.await(Math.min(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)) break;
            }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("isolated startup cancelled");
            if (process.failure != null) throw process.failure;
            if (!process.ready || !process.isAlive()) throw new IOException("isolated process exited during startup");
            return process;
        } catch (IOException | InterruptedException | RuntimeException error) {
            process.destroy();
            process.closeStreams();
            throw error;
        }
    }

    private void closeStreams() {
        for (Closeable stream : new Closeable[]{getOutputStream(), getInputStream(), getErrorStream()}) {
            try { stream.close(); } catch (IOException ignored) { }
        }
    }

    private synchronized void onExit(int status) {
        if (dead.getCount() == 0) return;
        exit = status;
        // Release the binding only after death. Stream readers may still drain buffered output.
        if (released.compareAndSet(false, true)) {
            try { endpoint.close(); } catch (IOException | RuntimeException ignored) { }
        }
        dead.countDown();
        started.countDown();
    }

    @Override public InputStream getInputStream() { return endpoint.stdout(); }
    @Override public InputStream getErrorStream() { return endpoint.stderr(); }
    @Override public OutputStream getOutputStream() { return endpoint.stdin(); }
    @Override public boolean isAlive() { return dead.getCount() != 0; }
    @Override public int exitValue() {
        if (isAlive()) throw new IllegalThreadStateException("isolated process is still alive");
        return exit;
    }
    @Override public int waitFor() throws InterruptedException { dead.await(); return exit; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return dead.await(timeout, Objects.requireNonNull(unit));
    }
    @Override public void destroy() {
        if (isAlive() && stopping.compareAndSet(false, true)) endpoint.terminate();
    }
    @Override public Process destroyForcibly() { destroy(); return this; }
}
