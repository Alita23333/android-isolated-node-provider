package dev.alita.androidagent.execution.isolation;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import dev.alita.androidagent.AgentApplication;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Internal execution backend, never a model-facing admission or permission decision. */
public final class IsolatedNodeService extends Service {
    static final int START = 1, STOP = 2, READY = 10, DONE = 11;
    static final int MAX_SCRIPT_BYTES = 262144;
    private boolean started;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static native String runNode(String[] names, int[] fds, int input, int output,
                                        int error, byte[] script, int cpuSeconds);
    private static void die() { android.os.Process.killProcess(android.os.Process.myPid()); }

    @Override public void onCreate() {
        super.onCreate();
        if (!android.os.Process.isIsolated() || !(getApplication() instanceof AgentApplication)
                || ((AgentApplication) getApplication()).getHasInitializedGraph()) die();
    }
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == STOP) { die(); return true; }
        if (message.what != START || started) { die(); return true; }
        started = true;
        final Bundle data = message.getData();
        final Messenger reply = message.replyTo;
        try {
            if (reply == null) throw new IllegalArgumentException("host reply channel required");
            reply.getBinder().linkToDeath(IsolatedNodeService::die, 0);
            byte[] script = data.getByteArray("scriptUtf8");
            String[] names = data.getStringArray("names");
            ArrayList<ParcelFileDescriptor> libraries = data.getParcelableArrayList("libraries");
            int wallMs = data.getInt("wallTimeoutMs");
            if (script == null || script.length == 0 || script.length > MAX_SCRIPT_BYTES
                    || names == null || names.length != 10 || libraries == null || libraries.size() != 10
                    || wallMs < 100 || wallMs > 60000) throw new IllegalArgumentException("invalid isolated launch limits");
            for (byte value : script) if (value == 0) throw new IllegalArgumentException("script contains NUL");
            for (String name : names) if (name == null || !name.matches("lib[A-Za-z0-9_+.-]+"))
                throw new IllegalArgumentException("invalid library name");
            final ParcelFileDescriptor input = data.getParcelable("input"), output = data.getParcelable("output"), error = data.getParcelable("error");
            if (input == null || output == null || error == null) throw new IllegalArgumentException("stdio required");
            handler.postDelayed(IsolatedNodeService::die, wallMs);
            new Thread(() -> {
                try {
                    System.loadLibrary("isolatednode");
                    int[] fds = new int[libraries.size()];
                    for (int i = 0; i < fds.length; i++) fds[i] = libraries.get(i).getFd();
                    Message ready = Message.obtain(null, READY); Bundle identity = new Bundle();
                    identity.putInt("uid", android.os.Process.myUid()); identity.putInt("pid", android.os.Process.myPid());
                    identity.putBoolean("isolated", android.os.Process.isIsolated());
                    identity.putBoolean("graphInitialized", ((AgentApplication) getApplication()).getHasInitializedGraph());
                    ready.setData(identity); reply.send(ready);
                    String result = runNode(names, fds, input.getFd(), output.getFd(), error.getFd(), script, (wallMs + 999) / 1000);
                    android.util.Log.i("IsolatedNodeRuntime", result);
                    Message done = Message.obtain(null, DONE); Bundle response = new Bundle(); response.putString("result", result); done.setData(response); reply.send(done);
                } catch (Throwable failure) {
                    android.util.Log.e("IsolatedNodeRuntime", "native entry failed", failure);
                    try { Message done = Message.obtain(null, DONE); Bundle response = new Bundle();
                        response.putString("result", "isolated native entry failed"); done.setData(response); reply.send(done); }
                    catch (RemoteException ignored) { die(); }
                }
                // Never keep a returned/failed Node VM available for a second invocation.
                handler.postDelayed(IsolatedNodeService::die, 1000);
            }, "isolated-node").start();
        } catch (Exception invalid) { die(); }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public void onDestroy() { die(); }
}
