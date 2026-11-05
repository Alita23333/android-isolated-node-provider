package dev.alita.providerprobe;

import android.content.*;
import android.os.*;
import java.io.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import dev.alita.androidagent.execution.IsolatedProviderProcess;

/** Experiment transport for the product facade. No product service/admission is enabled. */
final class BinderNodeEndpoint implements IsolatedProviderProcess.Endpoint {
    private final Context context;
    private final Bundle payload;
    private Consumer<Bundle> identity;
    private final ParcelFileDescriptor[] in, out, err;
    private final InputStream stdout, stderr;
    private final OutputStream stdin;
    private final ArrayList<ParcelFileDescriptor> libraries;
    private final AtomicBoolean stopping = new AtomicBoolean(), closed = new AtomicBoolean();
    private volatile IsolatedProviderProcess.Events events;
    private volatile Messenger remote;
    private volatile boolean bound;
    private volatile int status = 70;
    final CountDownLatch starting = new CountDownLatch(1), dead = new CountDownLatch(1);
    volatile Bundle startingIdentity;
    volatile int readyCount;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == 9) {
            startingIdentity = message.getData(); starting.countDown();
        } else if (message.what == 10) {
            readyCount++;
            if (message.getData().getInt("uid") == android.os.Process.myUid()) {
                events.failed(new IOException("owner UID inherited")); return true;
            }
            try { identity.accept(message.getData()); events.ready(); }
            catch (RuntimeException failure) { events.failed(new IOException("identity callback failed", failure)); }
        } else if (message.what == 11) {
            String result = message.getData().getString("result", "");
            if (result.matches("node_returned_[0-9]{1,3}")) {
                status = Integer.parseInt(result.substring("node_returned_".length()));
                terminate();
            } else events.failed(new IOException("native entry: " + result));
        }
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = new Messenger(binder);
            try {
                binder.linkToDeath(BinderNodeEndpoint.this::reportExit, 0);
                if (stopping.get()) { remote.send(Message.obtain(null, 2)); return; }
                Message start = Message.obtain(null, 1); start.replyTo = replies; start.setData(payload);
                remote.send(start);
                // Binder has duplicated these read-only libraries and child ends into the service.
                closeChildEnds();
            } catch (RemoteException | IOException failure) {
                events.failed(new IOException("isolated Binder startup failed", failure));
                if (!binder.isBinderAlive()) reportExit();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { reportExit(); }
        @Override public void onNullBinding(ComponentName name) {
            events.failed(new IOException("null isolated binding")); events.exited(status);
        }
    };
    private void reportExit() { try { events.exited(status); } finally { dead.countDown(); } }
    BinderNodeEndpoint(Context context, Bundle payload, ArrayList<ParcelFileDescriptor> libraries,
                      Consumer<Bundle> identity) throws IOException {
        this.context = context; this.payload = payload; this.libraries = libraries; this.identity = identity;
        in = ParcelFileDescriptor.createPipe(); out = ParcelFileDescriptor.createPipe(); err = ParcelFileDescriptor.createPipe();
        stdin = new ParcelFileDescriptor.AutoCloseOutputStream(in[1]);
        stdout = new ParcelFileDescriptor.AutoCloseInputStream(out[0]);
        stderr = new ParcelFileDescriptor.AutoCloseInputStream(err[0]);
        payload.putParcelable("input", in[0]); payload.putParcelable("output", out[1]); payload.putParcelable("error", err[1]);
    }
    public InputStream stdout() { return stdout; }
    public InputStream stderr() { return stderr; }
    public OutputStream stdin() { return stdin; }
    public synchronized void start(IsolatedProviderProcess.Events events) throws IOException {
        this.events = events;
        try {
            bound = context.bindService(new Intent(context, IsolatedNodeService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) { events.exited(70); throw new IOException("isolated bind rejected"); }
        } catch (RuntimeException error) { events.exited(70); throw new IOException("isolated bind failed", error); }
    }
    public void terminate() {
        if (!stopping.compareAndSet(false, true)) return;
        if (status == 70) status = 137;
        Messenger service = remote;
        if (service != null) try { service.send(Message.obtain(null, 2)); }
        catch (RemoteException ignored) { /* Death recipient owns acknowledgement. */ }
    }
    private void closeChildEnds() throws IOException {
        IOException failure = null;
        ArrayList<ParcelFileDescriptor> descriptors = new ArrayList<>(libraries);
        descriptors.add(in[0]); descriptors.add(out[1]); descriptors.add(err[1]);
        for (ParcelFileDescriptor descriptor : descriptors) try { descriptor.close(); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
    public synchronized void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { if (bound) { context.unbindService(connection); bound = false; } }
        finally { closeChildEnds(); }
    }
}
