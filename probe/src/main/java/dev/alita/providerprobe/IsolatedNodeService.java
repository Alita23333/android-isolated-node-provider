package dev.alita.providerprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.util.ArrayList;

/** Test application only. No production Agent/Gateway binding or credential/config access. */
public class IsolatedNodeService extends Service {
    private static native String runNode(String[] names, int[] fds, int input, int output, int error, String script);
    private boolean started;
    private final Messenger binder = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == 2) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        if (message.what != 1 || started) return false;
        started = true;
        final Bundle data = message.getData();
        final Messenger reply = message.replyTo;
        try {
            Message starting = Message.obtain(null, 9); Bundle identity = new Bundle();
            identity.putInt("uid", android.os.Process.myUid()); identity.putInt("pid", android.os.Process.myPid());
            identity.putLong("elapsedRealtimeMs", SystemClock.elapsedRealtime());
            identity.putBoolean("isIsolated", android.os.Process.isIsolated()); starting.setData(identity); reply.send(starting);
        } catch (RemoteException failure) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
        Runnable begin = () -> new Thread(() -> {
            try {
                Message identity = Message.obtain(null, 10);
                Bundle b = new Bundle(); b.putInt("uid", android.os.Process.myUid()); b.putInt("pid", android.os.Process.myPid());
                identity.setData(b); reply.send(identity);
                System.loadLibrary("nodeprobe");
                ArrayList<ParcelFileDescriptor> libraries = data.getParcelableArrayList("libraries", ParcelFileDescriptor.class);
                int[] fds = new int[libraries.size()];
                for (int i = 0; i < fds.length; i++) fds[i] = libraries.get(i).getFd();
                ParcelFileDescriptor input = data.getParcelable("input", ParcelFileDescriptor.class);
                ParcelFileDescriptor output = data.getParcelable("output", ParcelFileDescriptor.class);
                ParcelFileDescriptor error = data.getParcelable("error", ParcelFileDescriptor.class);
                String result = runNode(data.getStringArray("names"), fds, input.getFd(), output.getFd(), error.getFd(), data.getString("script"));
                Message done = Message.obtain(null, 11); Bundle r = new Bundle(); r.putString("result", result); done.setData(r); reply.send(done);
            } catch (Throwable failure) {
                try { Message failed = Message.obtain(null, 11); Bundle r = new Bundle();
                    r.putString("result", failure.getClass().getName() + ": " + failure.getMessage()); failed.setData(r); reply.send(failed);
                } catch (RemoteException ignored) { }
            }
        }, "isolated-node-entry").start();
        new Handler(Looper.getMainLooper()).postDelayed(begin, Math.max(0, Math.min(5000, data.getLong("startupDelayMs", 0))));
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return binder.getBinder(); }
    @Override public void onDestroy() { android.os.Process.killProcess(android.os.Process.myPid()); }
}
