package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SharedMemory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Hosts the native provider in the private :qnn process. */
public final class QnnWhisperService extends Service {
    static final int OPEN = 1;
    static final int RECOGNIZE = 2;
    static final int REPLY = 3;
    static final int ABORT = 4;
    static final int FEATURE_COUNT = 80 * 3000;
    static final int FEATURE_BYTES = FEATURE_COUNT * Float.BYTES;
    static final String PACK = "pack";
    static final String FEATURES = "features";
    static final String LANGUAGE = "language";
    static final String TOKENS = "tokens";
    static final String SCORE = "score";
    static final String ERROR = "error";

    private Messenger messenger;
    private HandlerThread inferenceThread;
    private Handler inference;
    private QnnWhisperBackend backend;

    @Override
    public void onCreate() {
        super.onCreate();
        inferenceThread = new HandlerThread("qnn-inference");
        inferenceThread.start();
        inference = new Handler(inferenceThread.getLooper());
        messenger = new Messenger(new Handler(this::receiveRequest));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        inference.post(this::closeBackend);
        inferenceThread.quitSafely();
        super.onDestroy();
    }

    private boolean receiveRequest(Message request) {
        if (request.sendingUid != Process.myUid()) return true;
        if (request.what == ABORT) {
            // A worker stuck inside a driver cannot receive a cooperative cancellation.
            Process.killProcess(Process.myPid());
        } else if (request.replyTo != null) {
            Message copy = Message.obtain(request);
            inference.post(() -> {
                try {
                    handleRequest(copy);
                } finally {
                    copy.recycle();
                }
            });
        }
        return true;
    }

    private void handleRequest(Message request) {
        Bundle result = new Bundle();
        try {
            Bundle input = request.getData();
            if (request.what == OPEN) {
                open(input.getString(PACK));
            } else if (request.what == RECOGNIZE) {
                recognize(input, result);
            } else {
                throw new IOException("Unknown Qualcomm Whisper request");
            }
        } catch (Exception | LinkageError failure) {
            String error = failure.getMessage();
            result.putString(ERROR, error == null ? failure.getClass().getSimpleName()
                    : error.substring(0, Math.min(error.length(), 4096)));
        }

        Message reply = Message.obtain(null, REPLY);
        reply.arg1 = request.arg1;
        reply.setData(result);
        try {
            request.replyTo.send(reply);
        } catch (RemoteException ignored) {
            // The client already stopped waiting or exited.
        }
    }

    private void open(String path) throws IOException {
        File active = QnnModelPack.activeDirectory(this);
        if (path == null || active == null || !active.getCanonicalFile().equals(new File(path).getCanonicalFile())) {
            throw new IOException("The active Qualcomm Whisper model changed");
        }
        closeBackend();
        backend = QnnWhisperBackend.load(this, active);
    }

    private void closeBackend() {
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void recognize(Bundle request, Bundle result) throws Exception {
        try (SharedMemory memory = request.getParcelable(FEATURES)) {
            if (backend == null || memory == null || memory.getSize() != FEATURE_BYTES) {
                throw new IOException("Invalid Qualcomm Whisper request");
            }
            float[] features = new float[FEATURE_COUNT];
            ByteBuffer buffer = memory.mapReadOnly();
            try {
                buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(features);
            } finally {
                SharedMemory.unmap(buffer);
            }
            double[] score = new double[1];
            result.putIntArray(TOKENS, backend.recognize(features, request.getInt(LANGUAGE), score));
            result.putDouble(SCORE, score[0]);
        }
    }
}
