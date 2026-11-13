package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Makes bounded requests to the private Qualcomm Whisper service from a worker. */
final class QnnWhisperServiceClient implements AutoCloseable {
    private final Context app;
    private final CompletableFuture<Messenger> connection = new CompletableFuture<>();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::handleReply));
    private final ServiceConnection service = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            connection.complete(new Messenger(binder));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            markDisconnected();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            markDisconnected();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            markDisconnected();
        }
    };

    private CompletableFuture<Bundle> pending;
    private int requestId;
    private boolean bound;
    private boolean disconnected;

    private QnnWhisperServiceClient(Context context) {
        app = context.getApplicationContext();
    }

    static QnnWhisperServiceClient load(Context context, File pack) throws IOException {
        QnnWhisperServiceClient client = new QnnWhisperServiceClient(context);
        boolean opened = false;
        try {
            client.bound = client.app.bindService(new Intent(client.app, QnnWhisperService.class),
                    client.service, Context.BIND_AUTO_CREATE);
            if (!client.bound) throw new IOException("Could not bind the Qualcomm Whisper service");
            Bundle request = new Bundle();
            request.putString(QnnWhisperService.PACK, pack.getAbsolutePath());
            client.request(QnnWhisperService.OPEN, request, 10);
            opened = true;
            return client;
        } finally {
            if (!opened) client.close();
        }
    }

    int[] recognize(float[] features, int languageToken, double[] score) throws IOException {
        if (features.length != QnnWhisperService.FEATURE_COUNT || score.length != 1) {
            throw new IOException("Invalid Qualcomm Whisper request");
        }
        try (SharedMemory memory = SharedMemory.create("whisper-mel", QnnWhisperService.FEATURE_BYTES)) {
            ByteBuffer buffer = memory.mapReadWrite();
            try {
                buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().put(features);
            } finally {
                SharedMemory.unmap(buffer);
            }
            if (!memory.setProtect(OsConstants.PROT_READ)) throw new IOException("Cannot seal the audio features");
            Bundle input = new Bundle();
            input.putParcelable(QnnWhisperService.FEATURES, memory);
            input.putInt(QnnWhisperService.LANGUAGE, languageToken);
            Bundle output = request(QnnWhisperService.RECOGNIZE, input, 5);
            int[] tokens = output.getIntArray(QnnWhisperService.TOKENS);
            score[0] = output.getDouble(QnnWhisperService.SCORE, Double.NaN);
            if (tokens == null || !Double.isFinite(score[0])) {
                throw new IOException("Invalid Qualcomm Whisper response");
            }
            return tokens;
        } catch (ErrnoException failure) {
            throw new IOException("Could not share Whisper audio features", failure);
        }
    }

    private Bundle request(int command, Bundle input, int seconds) throws IOException {
        Messenger remote = waitFor(connection, 3);
        CompletableFuture<Bundle> result = new CompletableFuture<>();
        Message message = Message.obtain(null, command);
        synchronized (this) {
            if (disconnected) throw new IOException("The Qualcomm Whisper service disconnected");
            if (pending != null) throw new IOException("A Qualcomm Whisper request is already active");
            pending = result;
            message.arg1 = ++requestId;
        }
        try {
            message.replyTo = replies;
            message.setData(input);
            remote.send(message);
            Bundle output;
            try {
                output = result.get(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                abort(remote);
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("The Qualcomm Whisper request was canceled");
            } catch (TimeoutException failure) {
                abort(remote);
                throw new IOException("The Qualcomm Whisper request timed out", failure);
            } catch (ExecutionException failure) {
                throw new IOException("The Qualcomm Whisper service disconnected", failure.getCause());
            }
            String error = output.getString(QnnWhisperService.ERROR);
            if (error != null) throw new IOException(error);
            return output;
        } catch (RemoteException failure) {
            throw new IOException("The Qualcomm Whisper process is unavailable", failure);
        } finally {
            synchronized (this) { pending = null; }
        }
    }

    private static void abort(Messenger remote) {
        try {
            remote.send(Message.obtain(null, QnnWhisperService.ABORT));
        } catch (RemoteException ignored) {
            // The inference process has already exited.
        }
    }

    private synchronized boolean handleReply(Message reply) {
        if (reply.what == QnnWhisperService.REPLY && reply.arg1 == requestId && pending != null) {
            pending.complete(reply.getData());
        }
        return true;
    }

    private static <T> T waitFor(CompletableFuture<T> result, int seconds) throws IOException {
        try {
            return result.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The Qualcomm Whisper request was canceled");
        } catch (TimeoutException failure) {
            throw new IOException("The Qualcomm Whisper request timed out", failure);
        } catch (ExecutionException failure) {
            throw new IOException("The Qualcomm Whisper service disconnected", failure.getCause());
        }
    }

    private synchronized void markDisconnected() {
        disconnected = true;
        IOException error = new IOException("The Qualcomm Whisper service disconnected");
        connection.completeExceptionally(error);
        if (pending != null) pending.completeExceptionally(error);
    }

    @Override
    public synchronized void close() {
        markDisconnected();
        if (bound) {
            bound = false;
            app.unbindService(service);
        }
    }
}
