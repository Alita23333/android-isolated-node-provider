package dev.alita.androidagent.execution.isolation;

import android.content.*;
import android.os.*;
import dev.alita.androidagent.execution.IsolatedProviderProcess;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Host-only Binder/pipe adapter. There is no permission decision, tool registration or fallback. */
public final class IsolatedNodeEndpoint implements IsolatedProviderProcess.Endpoint {
    interface Binding {
        boolean bind(ServiceConnection connection);
        void unbind(ServiceConnection connection);
    }
    interface Pipes { ParcelFileDescriptor[] create() throws IOException; }
    private final Object lock = new Object();
    private final Binding binding;
    private final Bundle payload;
    private Consumer<Bundle> identity;
    private final Runnable release;
    private final ArrayList<Closeable> childEnds = new ArrayList<>();
    private InputStream stdout, stderr;
    private OutputStream stdin;
    private IsolatedProviderProcess.Events events;
    private Messenger remote;
    private IBinder remoteBinder;
    private IBinder.DeathRecipient deathRecipient;
    private boolean bound, closed, stopping, dispatched, started;
    private int status = 70;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectionDeadline = () -> failBeforeDispatch("isolated connection deadline");
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        synchronized (lock) {
            if (closed || stopping) return true;
            if (message.what == IsolatedNodeService.READY) {
                Bundle data = message.getData();
                if (!data.getBoolean("isolated") || data.getBoolean("graphInitialized")
                        || data.getInt("uid") == android.os.Process.myUid()) {
                    events.failed(new IOException("isolated service identity rejected")); return true;
                }
                try { identity.accept(new Bundle(data)); events.ready(); }
                catch (RuntimeException invalid) { events.failed(new IOException("identity observer failed", invalid)); }
            } else if (message.what == IsolatedNodeService.DONE) {
                String result = message.getData().getString("result", "");
                if (result.matches("node_returned_[0-9]{1,3}")) status = Integer.parseInt(result.substring(14));
                else events.failed(new IOException("isolated native runtime failed"));
                terminate();
            }
        }
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                if (closed) return; // Late connection cannot dispatch a released payload.
                remoteBinder = binder; remote = new Messenger(binder);
                deathRecipient = () -> finish(status);
                try {
                    binder.linkToDeath(deathRecipient, 0);
                    handler.removeCallbacks(connectionDeadline);
                    if (stopping) { remote.send(Message.obtain(null, IsolatedNodeService.STOP)); return; }
                    Message request = Message.obtain(null, IsolatedNodeService.START);
                    request.replyTo = replies; request.setData(payload);
                    dispatched = true; remote.send(request);
                    closeChildEnds();
                } catch (IOException | RemoteException failure) {
                    events.failed(new IOException("isolated Binder dispatch failed", failure));
                    if (!binder.isBinderAlive()) finish(status);
                }
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(status); }
        @Override public void onNullBinding(ComponentName name) { failBeforeDispatch("null isolated binding"); }
        @Override public void onBindingDied(ComponentName name) {
            synchronized (lock) {
                if (!dispatched) failBeforeDispatch("isolated binding died before dispatch");
                else if (remoteBinder != null && !remoteBinder.isBinderAlive()) finish(status);
                else terminate();
            }
        }
    };

    public IsolatedNodeEndpoint(Context context, Bundle payload, ArrayList<ParcelFileDescriptor> libraries,
                                Consumer<Bundle> identity, Runnable release) throws IOException {
        this(payload, libraries, identity, release, new Binding() {
            public boolean bind(ServiceConnection connection) {
                return context.bindService(new Intent(context, IsolatedNodeService.class), connection, Context.BIND_AUTO_CREATE);
            }
            public void unbind(ServiceConnection connection) { context.unbindService(connection); }
        }, ParcelFileDescriptor::createPipe);
    }
    IsolatedNodeEndpoint(Bundle source, ArrayList<ParcelFileDescriptor> libraries, Consumer<Bundle> identity,
                         Runnable release, Binding binding, Pipes pipes) throws IOException {
        this.binding = Objects.requireNonNull(binding); this.identity = Objects.requireNonNull(identity);
        this.release = Objects.requireNonNull(release); payload = new Bundle(source);
        ArrayList<Closeable> allocated = new ArrayList<>();
        try {
            ArrayList<ParcelFileDescriptor> owned = new ArrayList<>();
            for (ParcelFileDescriptor library : libraries) {
                ParcelFileDescriptor copy = ParcelFileDescriptor.dup(library.getFileDescriptor());
                allocated.add(copy); childEnds.add(copy); owned.add(copy);
            }
            payload.putParcelableArrayList("libraries", owned);
            ParcelFileDescriptor[] input = pipes.create(); Collections.addAll(allocated, input);
            ParcelFileDescriptor[] output = pipes.create(); Collections.addAll(allocated, output);
            ParcelFileDescriptor[] error = pipes.create(); Collections.addAll(allocated, error);
            childEnds.add(input[0]); childEnds.add(output[1]); childEnds.add(error[1]);
            stdin = new ParcelFileDescriptor.AutoCloseOutputStream(input[1]);
            stdout = new ParcelFileDescriptor.AutoCloseInputStream(output[0]); stderr = new ParcelFileDescriptor.AutoCloseInputStream(error[0]);
            payload.putParcelable("input", input[0]); payload.putParcelable("output", output[1]); payload.putParcelable("error", error[1]);
        } catch (IOException | RuntimeException failure) {
            for (Closeable fd : allocated) try { fd.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }
    @Override public InputStream stdout() { return stdout; }
    @Override public InputStream stderr() { return stderr; }
    @Override public OutputStream stdin() { return stdin; }
    @Override public void start(IsolatedProviderProcess.Events events) throws IOException {
        synchronized (lock) {
            if (started || closed) throw new IOException("endpoint already used");
            started = true; this.events = events;
            try {
                bound = binding.bind(connection);
                if (!bound) { finish(70); throw new IOException("isolated bind rejected"); }
                handler.postDelayed(connectionDeadline, 10000);
            } catch (RuntimeException failure) {
                finish(70); throw new IOException("isolated bind failed", failure);
            }
        }
    }
    private void failBeforeDispatch(String reason) {
        synchronized (lock) {
            if (closed || dispatched) return;
            events.failed(new IOException(reason)); finish(status);
        }
    }
    @Override public void terminate() {
        synchronized (lock) {
            if (closed || stopping) return;
            stopping = true;
            if (status == 70) status = 137;
            if (!dispatched) {
                // No START was sent. Close the gate/unbind; late callbacks can never execute code.
                finish(status); return;
            }
            try { remote.send(Message.obtain(null, IsolatedNodeService.STOP)); }
            catch (RemoteException ignored) { if (remoteBinder != null && !remoteBinder.isBinderAlive()) finish(status); }
        }
    }
    private void finish(int exit) {
        synchronized (lock) {
            if (closed) return;
            status = exit;
            try { close(); } catch (IOException ignored) { }
            if (events != null) events.exited(exit);
        }
    }
    private void closeChildEnds() throws IOException {
        IOException failure = null;
        for (Closeable fd : childEnds) try { fd.close(); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        childEnds.clear();
        if (failure != null) throw failure;
    }
    @Override public void close() throws IOException {
        synchronized (lock) {
            if (closed) return;
            closed = true; handler.removeCallbacks(connectionDeadline);
            if (remoteBinder != null && deathRecipient != null) remoteBinder.unlinkToDeath(deathRecipient, 0);
            try { if (bound) { bound = false; binding.unbind(connection); } }
            catch (RuntimeException ignored) { /* Still release every descriptor and instance lease. */ }
            finally { try { closeChildEnds(); } finally { release.run(); } }
        }
    }
}
