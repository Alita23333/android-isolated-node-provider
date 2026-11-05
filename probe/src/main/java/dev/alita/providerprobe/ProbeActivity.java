package dev.alita.providerprobe;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.widget.TextView;
import org.json.*;
import dev.alita.androidagent.execution.IsolatedProviderProcess;
import java.io.*;
import java.lang.Process;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Standalone experiment. It never binds the product or reads actual provider credentials. */
public class ProbeActivity extends Activity {
    private final JSONObject result = new JSONObject();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private TextView label;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); label = new TextView(this); label.setText("Provider isolation probe"); setContentView(label);
        io.execute(this::probe);
    }
    private synchronized void record(String verdict, String stage) {
        try {
            synchronized(result) {
                result.put("verdict", verdict); result.put("stage", stage);
                File output = new File(getFilesDir(), "result.json");
                try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8)); stream.getFD().sync(); }
            }
            runOnUiThread(() -> label.setText(verdict + ": " + stage));
        } catch (Exception e) { android.util.Log.e("IsolationProbe", "Result write failed", e); }
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT,"%02x",b)); return text.toString();
    }
    private JSONObject reply(InputStream stream, long id) throws Exception {
        Future<JSONObject> future = io.submit(() -> {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (;;) { int next=stream.read(); if(next<0)throw new EOFException("isolated stdout closed"); if(next==10)break;
                if(line.size()>=65536)throw new IOException("protocol output exceeds limit"); line.write(next); }
            JSONObject value = new JSONObject(line.toString(StandardCharsets.UTF_8));
            if (!"2.0".equals(value.getString("jsonrpc")) || value.getLong("id")!=id || !value.has("result") || value.has("error")) throw new IOException("invalid RPC envelope");
            return value.getJSONObject("result");
        });
        return future.get(20, TimeUnit.SECONDS);
    }
    private void send(OutputStream stream, long id, String method, JSONObject params) throws Exception {
        byte[] bytes = new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params).toString().getBytes(StandardCharsets.UTF_8);
        stream.write(bytes);stream.write(10);stream.flush();
    }
    private void require(boolean valid, String reason) { if(!valid)throw new IllegalStateException(reason); }
    private void probe() {
        ArrayList<ParcelFileDescriptor> libraries = new ArrayList<>();
        Process process=null;
        ServerSocket server=null;
        try {
            synchronized(result) { result.put("runId",getIntent().getStringExtra("runId")); result.put("ownerUid",android.os.Process.myUid()); result.put("android",Build.VERSION.RELEASE); result.put("model",Build.MODEL); }
            require("17".equals(Build.VERSION.RELEASE) && "MODEL_ANDROID17".equals(Build.MODEL),"assigned_device_required");
            record("RUNNING","prepare_immutable_libraries");
            JSONObject manifest; try(InputStream stream=getAssets().open("payload-identity.json")) { manifest=new JSONObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8)); }
            JSONArray entries=manifest.getJSONArray("libraries");String[] names=new String[entries.length()];
            for(int i=0;i<entries.length();i++) {
                JSONObject entry=entries.getJSONObject(i);names[i]=entry.getString("name");
                File file=new File(getApplicationInfo().nativeLibraryDir,entry.getString("packagedName"));
                byte[] bytes=Files.readAllBytes(file.toPath());
                require(hash(bytes).equals(entry.getString("sha256")),"payload_hash_mismatch");
                libraries.add(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY));
            }
            File sentinel=new File(getFilesDir(),"owner-private-sentinel.txt");Files.write(sentinel.toPath(),"OWNER_PRIVATE_FIXTURE".getBytes(StandardCharsets.UTF_8));
            server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(2000);
            // The listening port is actually reachable with the OWNER's INTERNET permission.
            try(Socket owner=new Socket("127.0.0.1",server.getLocalPort());Socket accepted=server.accept()) {require(owner.isConnected(),"owner_network_control_failed");}
            synchronized(result) {result.put("ownerNetworkPositiveControl",true);result.put("payload",manifest);}
            String script;try(InputStream stream=getAssets().open("probe-provider.cjs")) {script=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
            Bundle bundle=new Bundle(); bundle.putStringArray("names",names);bundle.putParcelableArrayList("libraries",libraries);bundle.putString("script",script);
            boolean startupCancel=getIntent().getBooleanExtra("startupCancel",false);
            if(startupCancel)bundle.putLong("startupDelayMs",3000);
            BinderNodeEndpoint endpoint=new BinderNodeEndpoint(this,bundle,libraries,identity -> {
                synchronized(result) {try {result.put("isolatedUid",identity.getInt("uid"));result.put("isolatedPid",identity.getInt("pid"));}catch(JSONException e){throw new IllegalStateException(e);}}
            });
            if(startupCancel) {
                java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
                Future<IsolatedProviderProcess> pending=io.submit(()->IsolatedProviderProcess.launch(endpoint,10000,cancelled::get));
                require(endpoint.starting.await(5,TimeUnit.SECONDS),"service_start_not_announced");
                Bundle identity=endpoint.startingIdentity;
                require(identity.getBoolean("isIsolated") && identity.getInt("uid")!=android.os.Process.myUid(),"not_isolated_during_startup");
                long before=SystemClock.elapsedRealtime();cancelled.set(true);
                try {pending.get(2,TimeUnit.SECONDS);throw new IllegalStateException("cancelled_startup_returned_process");}
                catch(ExecutionException expected) {require(expected.getCause() instanceof InterruptedIOException,"startup_wrong_error");}
                require(endpoint.dead.await(2500,TimeUnit.MILLISECONDS),"startup_cancel_death_deadline");
                long ageAtDeath=SystemClock.elapsedRealtime()-identity.getLong("elapsedRealtimeMs");
                require(ageAtDeath<3000,"death_not_before_delayed_entry");
                require(endpoint.readyCount==0,"native_entry_became_ready");
                synchronized(result) {result.put("isolatedUid",identity.getInt("uid"));result.put("isolatedPid",identity.getInt("pid"));
                    result.put("isIsolated",true);result.put("startupCancelled",true);result.put("readyCount",endpoint.readyCount);
                    result.put("startupDelayMs",3000);result.put("cancelDeathMs",SystemClock.elapsedRealtime()-before);}
                synchronized(result){result.put("serviceAgeAtDeathMs",ageAtDeath);}
                record("PASS","cancel_during_real_isolated_service_startup");return;
            }
            process=IsolatedProviderProcess.launch(endpoint,10000);
            Process running=process;
            Future<String> diagnostics=io.submit(()->new String(running.getErrorStream().readNBytes(8192),StandardCharsets.UTF_8));
            try(OutputStream stdin=process.getOutputStream();InputStream stdout=process.getInputStream()) {
                record("RUNNING","local_protocol_initialize");
                send(stdin,1,"initialize",new JSONObject().put("protocolVersion","1.0"));
                JSONObject initialized=reply(stdout,1);require("1.0".equals(initialized.getString("protocolVersion")),"protocol_version_mismatch");
                JSONObject arguments=new JSONObject().put("privatePath",sentinel.getAbsolutePath()).put("port",server.getLocalPort());
                send(stdin,2,"tools/call",new JSONObject().put("name","probe").put("arguments",arguments));
                JSONObject response=reply(stdout,2);JSONObject data=response.getJSONObject("data");
                synchronized(result) {result.put("toolResult",response);result.put("initializeResult",initialized);}
                require(response.getBoolean("ok"),"provider_failed");
                require(data.getInt("uid")!=android.os.Process.myUid(),"owner_uid_inherited");
                require(!data.getJSONObject("privateRead").getBoolean("allowed"),"private_read_allowed");
                require(!data.getJSONObject("privateWrite").getBoolean("allowed"),"private_write_allowed");
                require("OWNER_PRIVATE_FIXTURE".equals(new String(Files.readAllBytes(sentinel.toPath()),StandardCharsets.UTF_8)),"private_sentinel_changed");
                require(!data.getJSONObject("network").getBoolean("allowed") &&
                    List.of("EACCES","EPERM").contains(data.getJSONObject("network").getString("code")),"network_not_permission_denied");
                require(hash("isolation-fixture".getBytes(StandardCharsets.UTF_8)).equals(data.getString("sha256")),"digest_mismatch");
                require("v24.18.0".equals(data.getString("node")),"node_version_mismatch");
                record("RUNNING","cancel_isolated_process");
                send(stdin,3,"tools/call",new JSONObject().put("name","probe").put("arguments",new JSONObject().put("wait",true)));
                Thread.sleep(150);long before=SystemClock.elapsedRealtime();process.destroy();process.destroyForcibly();
                require(process.waitFor(2500,TimeUnit.MILLISECONDS),"cancel_death_deadline");
                require(!process.isAlive() && process.exitValue()==137,"cancel_process_state");
                synchronized(result){result.put("facadeWaitFor",true);result.put("facadeExit",process.exitValue());result.put("facadeClass",process.getClass().getName());result.put("stderr",diagnostics.get(2,TimeUnit.SECONDS));}
                synchronized(result){result.put("cancelDeathMs",SystemClock.elapsedRealtime()-before);result.put("privateSentinelUnchanged",true);result.put("protocolReplies",2);}
                record("PASS","isolated_node_protocol_permissions_and_cancel");
            }
        } catch(Throwable failure) {
            synchronized(result){try{result.put("failure",failure.getClass().getName()+": "+failure.getMessage());}catch(JSONException ignored){}}
            record("FAIL","probe_failed");
        } finally {
            if(process!=null)process.destroyForcibly();
            for(ParcelFileDescriptor fd:libraries)try{fd.close();}catch(Exception ignored){}
            if(server!=null)try{server.close();}catch(Exception ignored){}
            io.shutdownNow();
        }
    }
}
