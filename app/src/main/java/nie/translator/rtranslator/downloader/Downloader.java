package nie.translator.rtranslator.downloader;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.downloader.Error;
import com.downloader.OnCancelListener;
import com.downloader.OnDownloadListener;
import com.downloader.OnPauseListener;
import com.downloader.OnProgressListener;
import com.downloader.OnStartOrResumeListener;
import com.downloader.PRDownloader;
import com.downloader.Progress;
import com.downloader.Status;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;

public class Downloader {
    public static final int GENERAL_ERROR = 1;
    public static final int UNZIP_FAILED = 2;
    public static final int INTEGRITY_CHECK_FAILED = 3;
    private Context context;
    private final DownloadGroupInfo downloadGroupInfo;
    private ClientCallback callback;
    private int lastDownloadSuccessIndex = -1;
    private int id;
    private Handler mainHandler;
    private boolean downloadsStarted = false;
    private long generation = 0;
    private long waitingForPause = -1;
    private boolean resumeAfterPause = false;
    private boolean networkActive = false;
    private int activeNetworkDownloadId = -1;
    private final boolean[] localFileChecked;
    private final boolean[] importedLocally;
    private Thread importThread;
    @Nullable
    private Thread unzipThread = null;
    @Nullable
    private Thread testIntegrityThread = null;

    public Downloader(DownloadGroupInfo downloadGroupInfo, Context context, int id, ClientCallback callback) {
        this.downloadGroupInfo = downloadGroupInfo;
        this.mainHandler = new android.os.Handler(Looper.getMainLooper());
        //this.downloadGroupInfo.setRunningDownloadIndex(-1);
        this.context = context;
        this.id = id;
        this.callback = callback;
        localFileChecked = new boolean[downloadGroupInfo.downloadsInfo.length];
        importedLocally = new boolean[downloadGroupInfo.downloadsInfo.length];
    }

    public void startDownloads(){
        // PRDownloader.pause() only changes a flag. Do not submit a second writer while the
        // previous HTTP/copy/extraction task is still unwinding and flushing its checkpoint.
        if (waitingForPause != -1) {
            resumeAfterPause = true;
            return;
        }
        if(!downloadsStarted && downloadGroupInfo.downloadsInfo.length > 0 && !downloadGroupInfo.isAllDownloadCompleted()) {
            downloadsStarted = true;
            generation++;
            // eventual resume of the download based on the previous ones that have been completed
            // (or start from 0 if none of the previous ones are completed)
            int startIndex = downloadGroupInfo.getRunningDownloadIndex() != -1 ? downloadGroupInfo.getRunningDownloadIndex() : findFirstIncompletedDownload();
            lastDownloadSuccessIndex = startIndex - 1;
            if(startIndex >= 0 && startIndex < downloadGroupInfo.downloadsInfo.length) {
                DownloadInfo startDownload = downloadGroupInfo.downloadsInfo[startIndex];
                downloadGroupInfo.setRunningDownloadIndex(startIndex);
                int percentageTotalProgress = calculateTotalProgress(lastDownloadSuccessIndex + 1, 0);
                if (!startDownload.isDownloadCompleted()) {
                    startDownload(startIndex);
                } else {
                    if (startDownload.shouldUnzip() && !startDownload.isUnzipped()) {
                        if (!new File(startDownload.getDestinationCompletePath()).isFile()) {
                            // The process may have died after archive cleanup but before its
                            // preference update reached disk. Reuse/download the official ZIP
                            // again instead of treating a missing archive as a completed model.
                            startDownload.setDownloadCompleted(false);
                            localFileChecked[startIndex] = false;
                            startDownload(startIndex);
                        } else {
                            unzipRunningDownloadAndTestIntegrity(percentageTotalProgress);
                        }
                    } else {
                        if (startDownload.shouldTestIntegrity() && !startDownload.isIntegrityTested()) {
                            testRunningDownloadIntegrity(percentageTotalProgress, startDownload.getInternalFolder());
                        } else {
                            startNextDownload();
                        }
                    }
                }
            }else{
                // the download group is completed even if for some errors the group is not marked as completed
                downloadGroupInfo.setAllDownloadCompleted(true);
                callback.onAllCompleted(downloadGroupInfo);
            }
        }
    }

    public void pauseDownloads(){
        int currentDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
        if(currentDownloadIndex != -1) {
            if (networkActive || importThread != null || unzipThread != null || testIntegrityThread != null) {
                waitingForPause = generation;
            }
            generation++;
            resumeAfterPause = false;
            localFileChecked[currentDownloadIndex] = false;
            int downloadId = downloadGroupInfo.downloadsInfo[currentDownloadIndex].getDownloadId();
            Status status = PRDownloader.getStatus(downloadId);
            if (importThread != null) {
                importThread.interrupt();
            } else if(downloadGroupInfo.getRunningDownload().isDownloading()) {    //status == Status.QUEUED || status == Status.RUNNING || status == Status.UNKNOWN
                PRDownloader.pause(downloadId);
            }else if(downloadGroupInfo.getRunningDownload().isUnzipping()){
                if(unzipThread != null){
                    unzipThread.interrupt();
                }
            }else if(downloadGroupInfo.getRunningDownload().isTestingIntegrity()){
                if(testIntegrityThread != null){
                    testIntegrityThread.interrupt();
                }
            }
            downloadGroupInfo.setRunningDownloadIndex(-1);
            downloadsStarted = false;
        }
    }

    public void cancelDownloads(){
        downloadsStarted = false;
        generation++;
        waitingForPause = -1;
        resumeAfterPause = false;
        if (importThread != null) importThread.interrupt();
        if (activeNetworkDownloadId != -1) {
            PRDownloader.cancel(activeNetworkDownloadId);
            activeNetworkDownloadId = -1;
            networkActive = false;
        }
        if (unzipThread != null) unzipThread.interrupt();
        if (testIntegrityThread != null) testIntegrityThread.interrupt();
        int currentDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
        if(currentDownloadIndex != -1) {
            if(downloadGroupInfo.getRunningDownload().isDownloading()) {
                // we cancel the current download
                PRDownloader.cancel(downloadGroupInfo.downloadsInfo[currentDownloadIndex].getDownloadId());
            }else if(downloadGroupInfo.getRunningDownload().isUnzipping()){
                if(unzipThread != null){
                    unzipThread.interrupt();
                }
            }else if(downloadGroupInfo.getRunningDownload().isTestingIntegrity()){
                if(testIntegrityThread != null){
                    testIntegrityThread.interrupt();
                }
            }
        }
        downloadGroupInfo.setRunningDownloadIndex(-1);
        // we delete the already downloaded files of this group of download
        DownloaderTools.deleteDownloadedFiles(downloadGroupInfo);
    }

    public DownloadGroupInfo getDownloadGroupInfo() {
        return downloadGroupInfo;
    }

    public int getId() {
        return id;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if(obj instanceof DownloadGroupInfo) {
            return downloadGroupInfo.equals(obj);
        }
        return super.equals(obj);
    }

    public int findFirstIncompletedDownload(){
        for (int i = 0; i < downloadGroupInfo.downloadsInfo.length; i++) {
            if (!downloadGroupInfo.downloadsInfo[i].isAllCompleted()) return i;
        }
        return downloadGroupInfo.downloadsInfo.length;
    }

    private void startDownload(int index){
        if (!downloadsStarted) return;
        downloadGroupInfo.setRunningDownloadIndex(index);
        DownloadInfo info = downloadGroupInfo.downloadsInfo[index];
        // Restored/migrated groups can contain completed files after a missing one.
        // Keep their completed steps instead of downloading them again when advancing.
        if (info.isDownloadCompleted()) {
            int progress = calculateTotalProgress(index, 0);
            if (info.shouldUnzip() && !info.isUnzipped()) {
                unzipRunningDownloadAndTestIntegrity(progress);
            } else if (info.shouldTestIntegrity() && !info.isIntegrityTested()) {
                testRunningDownloadIntegrity(progress, info.getInternalFolder());
            } else {
                startNextDownload();
            }
            return;
        }
        ModelDownloadManifest.Entry entry = ModelDownloadManifest.forUrl(info.getUrl());
        if (!localFileChecked[index] && entry != null) {
            DownloadTrace.state("检查可导入的本地模型：" + info.getName());
            localFileChecked[index] = true;
            final long attempt = generation;
            importThread = new Thread(() -> {
                boolean reused = false;
                IOException failure = null;
                try {
                    File destination = new File(info.getDestinationCompletePath());
                    File external = context.getExternalFilesDir(null);
                    File source = external == null ? destination : new File(external, info.getName());
                    reused = LocalModelFiles.importIfValid(source, destination, entry.sizeBytes, entry.sha256);
                } catch (IOException error) {
                    failure = error;
                }
                Thread finished = Thread.currentThread();
                final boolean imported = reused;
                final IOException error = failure;
                mainHandler.post(() -> {
                    if (importThread == finished) importThread = null;
                    if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                    if (error != null) {
                        DownloadTrace.state("本地模型导入失败：" + info.getName()
                                + "；" + error.getMessage());
                        failDownload(index, GENERAL_ERROR);
                    } else if (imported) {
                        DownloadTrace.state("已从本地导入模型：" + info.getName());
                        importedLocally[index] = true;
                        onFileDownloaded(index);
                    } else {
                        DownloadTrace.record("本地未找到有效文件，开始联网下载：" + info.getName());
                        startNetworkDownload(index);
                    }
                });
            }, "model-import");
            importThread.start();
            return;
        }
        startNetworkDownload(index);
    }

    private boolean isActive(long attempt, int index) {
        return downloadsStarted && generation == attempt && downloadGroupInfo.getRunningDownloadIndex() == index;
    }

    private boolean finishPausedOperation(long attempt) {
        if (waitingForPause != attempt) return false;
        waitingForPause = -1;
        networkActive = false;
        if (resumeAfterPause) {
            resumeAfterPause = false;
            mainHandler.post(this::startDownloads);
        }
        return true;
    }

    private void failDownload(int index, int reason) {
        DownloadInfo info = downloadGroupInfo.downloadsInfo[index];
        info.setCurrentError(reason);
        downloadGroupInfo.setRunningDownloadIndex(-1);
        downloadsStarted = false;
        callback.onError(downloadGroupInfo, info, reason);
    }

    private void onFileDownloaded(int index) {
        DownloadInfo finishedDownload = downloadGroupInfo.downloadsInfo[index];
        int percentageTotalProgress = calculateTotalProgress(index, 0);
        downloadGroupInfo.setCurrentProgress(percentageTotalProgress);
        finishedDownload.setCurrentProgress(100);
        finishedDownload.setDownloadCompleted(true);
        callback.onDownloadCompleted(downloadGroupInfo, finishedDownload);
        if (finishedDownload.shouldUnzip()) {
            unzipRunningDownloadAndTestIntegrity(percentageTotalProgress);
        } else if (finishedDownload.shouldTestIntegrity()) {
            testRunningDownloadIntegrity(percentageTotalProgress, null);
        } else {
            startNextDownload();
        }
    }

    private void startChunkedDownload(int index, DownloadInfo info,
                                      ModelDownloadManifest.Entry entry) {
        if (importThread != null || !downloadsStarted) return;
        final long attempt = generation;
        final int parallelism = DownloadSourceOrder.parallelThreads(context);
        downloadGroupInfo.setRunningDownloadIndex(index);
        info.setCurrentError(-1);
        DownloadTrace.state("启动高速分块下载：" + info.getName()
                + " · 4 MiB/块 · " + parallelism + " 路并发");
        importThread = new Thread(() -> {
            IOException failure = null;
            final java.util.concurrent.atomic.AtomicLong lastUpdate =
                    new java.util.concurrent.atomic.AtomicLong(0);
            try {
                new ChunkedModelDownloader().download(info.getUrl(),
                        new File(info.getDestinationCompletePath()), entry, parallelism,
                        (bytes, total) -> {
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (bytes != total && now - lastUpdate.get() < 300) return;
                            lastUpdate.set(now);
                            mainHandler.post(() -> {
                                if (!isActive(attempt, index)) return;
                                int overall = calculateTotalProgress(lastDownloadSuccessIndex,
                                        bytes / 1000);
                                int partial = (int) Math.min(100, bytes * 100 / total);
                                downloadGroupInfo.setCurrentProgress(overall);
                                info.setCurrentProgress(partial);
                                callback.onProgress(downloadGroupInfo, info,
                                        overall, partial, false, false);
                            });
                        });
            } catch (IOException error) {
                failure = error;
            }
            final IOException stopped = failure;
            final Thread finishing = Thread.currentThread();
            mainHandler.post(() -> {
                if (importThread == finishing) importThread = null;
                if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                if (stopped == null) {
                    onFileDownloaded(index);
                } else {
                    DownloadTrace.state("分块下载失败：" + stopped.getMessage());
                    failDownload(index, GENERAL_ERROR);
                }
            });
        }, "model-range-download");
        importThread.start();
    }

    private void startNetworkDownload(int index){
        if (!downloadsStarted) return;
        DownloadInfo info = downloadGroupInfo.downloadsInfo[index];
        ModelDownloadManifest.Entry entry = ModelDownloadManifest.forUrl(info.getUrl());
        if (entry != null && entry.sizeBytes >= ChunkedModelDownloader.MIN_PARALLEL_BYTES) {
            startChunkedDownload(index, info, entry);
            return;
        }
        downloadGroupInfo.setRunningDownloadIndex(index);
        downloadGroupInfo.getRunningDownload().setCurrentError(-1);
        int downloadId = downloadGroupInfo.downloadsInfo[index].getDownloadId();
        Status status = PRDownloader.getStatus(downloadId);
        if(downloadId != -1){
            if (status == Status.RUNNING || status == Status.QUEUED) {
                // A download is already in progress.
                // Return early so we DO NOT start a duplicate download task.
                return;
            }
        }
        // A paused worker has fully exited before reaching here. A new request keeps the same
        // canonical ID/DB checkpoint and fresh callbacks, rather than reusing stale listeners.
        final long attempt = generation;
        networkActive = true;
        DownloadTrace.record("发起下载：" + downloadGroupInfo.downloadsInfo[index].getName());
        //start the download from scratch or resume it after the app has been restarted
        downloadId = PRDownloader.download(downloadGroupInfo.downloadsInfo[index].getUrl(), downloadGroupInfo.downloadsInfo[index].getDestinationPath(), downloadGroupInfo.downloadsInfo[index].getName())
                .build()
                .setOnStartOrResumeListener(new OnStartOrResumeListener() {
                    @Override
                    public void onStartOrResume() {

                    }
                })
                .setOnPauseListener(new OnPauseListener() {
                    @Override
                    public void onPause() {
                        finishPausedOperation(attempt);
                    }
                })
                .setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel() {

                    }
                })
                .setOnProgressListener(new OnProgressListener() {
                    @Override
                    public void onProgress(Progress progress) {
                        if (!isActive(attempt, index)) return;
                        //here we return a value between 0 and 100 that represents the total progress made so far with the download
                        int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
                        if(runningDownloadIndex != -1 && !downloadGroupInfo.getRunningDownload().isDownloadCompleted()) {
                            int percentageTotalProgress = calculateTotalProgress(lastDownloadSuccessIndex, progress.currentBytes/1000);
                            int percentageProgress = calculateProgress(runningDownloadIndex, progress.currentBytes / 1000);
                            downloadGroupInfo.setCurrentProgress(percentageTotalProgress);
                            if (runningDownloadIndex >= 0) {
                                downloadGroupInfo.downloadsInfo[runningDownloadIndex].setCurrentProgress(percentageProgress);
                                callback.onProgress(downloadGroupInfo, downloadGroupInfo.downloadsInfo[runningDownloadIndex], percentageTotalProgress, percentageProgress, false, false);
                            }
                        }
                    }
                })
                .start(new OnDownloadListener() {
                    @Override
                    public void onDownloadComplete() {
                        networkActive = false;
                        activeNetworkDownloadId = -1;
                        if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                        onFileDownloaded(index);
                    }

                    @Override
                    public void onError(Error error) {
                        networkActive = false;
                        activeNetworkDownloadId = -1;
                        if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                        Log.e("download error", (downloadGroupInfo.getRunningDownload() != null ? downloadGroupInfo.getRunningDownload().getName() : "") + "    " + error.toString());
                        DownloadTrace.state("下载已停止：" + downloadGroupInfo.downloadsInfo[index].getName()
                                + "；" + error.toString());
                        int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
                        if(runningDownloadIndex != -1 && !downloadGroupInfo.getRunningDownload().isDownloadCompleted()) {
                            DownloadInfo download = downloadGroupInfo.downloadsInfo[runningDownloadIndex];
                            downloadGroupInfo.downloadsInfo[runningDownloadIndex].setCurrentError(GENERAL_ERROR);
                            downloadGroupInfo.setRunningDownloadIndex(-1);
                            downloadsStarted = false;
                            callback.onError(downloadGroupInfo, download, GENERAL_ERROR);
                        }
                    }
                });
        downloadGroupInfo.downloadsInfo[index].setDownloadId(downloadId);
        activeNetworkDownloadId = downloadId;
    }

    private void unzipRunningDownloadAndTestIntegrity(int percentageTotalProgress){
        int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
        if(runningDownloadIndex != -1) {
            final long attempt = generation;
            downloadGroupInfo.getRunningDownload().setCurrentError(-1);
            DownloadInfo finishedDownload = downloadGroupInfo.downloadsInfo[runningDownloadIndex];
            callback.onProgress(downloadGroupInfo, downloadGroupInfo.downloadsInfo[runningDownloadIndex], percentageTotalProgress, 100, true, false);
            unpackZip(finishedDownload.destinationPath, finishedDownload.name, new UnpackListener() {
                @Override
                public void onSuccess(String topInternalFolder) {
                    if (!isActive(attempt, runningDownloadIndex)) return;
                    downloadGroupInfo.downloadsInfo[runningDownloadIndex].setUnzipped(true);
                    downloadGroupInfo.downloadsInfo[runningDownloadIndex].setInternalFolder(topInternalFolder);
                    callback.onUnzippingCompleted(downloadGroupInfo, finishedDownload);
                    // Worker completion can be discarded by pause/cancel. Only remove the ZIP
                    // after this accepted, main-thread state transition, leaving a paused worker
                    // with a replayable archive. A failed cleanup merely leaves a reusable ZIP.
                    new File(finishedDownload.getDestinationCompletePath()).delete();
                    if (finishedDownload.shouldTestIntegrity()) {
                        testRunningDownloadIntegrity(percentageTotalProgress, topInternalFolder);
                    } else {
                        startNextDownload();
                    }
                }

                @Override
                public void onFailure() {
                    if (!isActive(attempt, runningDownloadIndex)) return;
                    int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
                    if (runningDownloadIndex >= 0) {
                        downloadGroupInfo.downloadsInfo[runningDownloadIndex].setCurrentError(UNZIP_FAILED);
                    }
                    downloadGroupInfo.setRunningDownloadIndex(-1);
                    downloadsStarted = false;
                    callback.onError(downloadGroupInfo, finishedDownload, UNZIP_FAILED);
                }
            });
        }
    }

    private void testRunningDownloadIntegrity(int percentageTotalProgress, @Nullable String internalFolder) {
        int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
        if(runningDownloadIndex != -1) {
            final long attempt = generation;
            downloadGroupInfo.getRunningDownload().setCurrentError(-1);
            DownloadInfo finishedDownload = downloadGroupInfo.downloadsInfo[runningDownloadIndex];
            callback.onProgress(downloadGroupInfo, downloadGroupInfo.downloadsInfo[runningDownloadIndex], percentageTotalProgress, 100, false, true);
            final Runnable[] result = new Runnable[1];
            NeuralNetworkApi.InitListener listener = new NeuralNetworkApi.InitListener() {
                @Override
                public void onInitializationFinished() {
                    result[0] = () -> {
                        if (!isActive(attempt, runningDownloadIndex)) return;
                        testIntegrityThread = null;
                        downloadGroupInfo.downloadsInfo[runningDownloadIndex].setIntegrityTested(true);
                        callback.onIntegrityTestCompleted(downloadGroupInfo, downloadGroupInfo.downloadsInfo[runningDownloadIndex]);
                        startNextDownload();
                    };
                }

                @Override
                public void onError(int[] reasons, long value) {
                    result[0] = () -> {
                        if (!isActive(attempt, runningDownloadIndex)) return;
                        testIntegrityThread = null;
                        // Old ONNX releases have sizes but no published hashes. A same-size bad
                        // sideload must not become a permanent "downloaded, retry validation" loop.
                        if (!finishedDownload.shouldUnzip()) {
                            File invalid = new File(finishedDownload.getDestinationCompletePath());
                            if (invalid.exists() && !invalid.delete()) {
                                failDownload(runningDownloadIndex, INTEGRITY_CHECK_FAILED);
                                return;
                            }
                            finishedDownload.setDownloadCompleted(false);
                            finishedDownload.setIntegrityTested(false);
                            finishedDownload.setCurrentProgress(0);
                            localFileChecked[runningDownloadIndex] = true;
                            if (importedLocally[runningDownloadIndex]) {
                                importedLocally[runningDownloadIndex] = false;
                                callback.onProgress(downloadGroupInfo, finishedDownload,
                                        calculateTotalProgress(runningDownloadIndex - 1, 0), 0, false, false);
                                mainHandler.post(() -> {
                                    if (isActive(attempt, runningDownloadIndex)) startNetworkDownload(runningDownloadIndex);
                                });
                                return;
                            }
                        }
                        int runningDownloadIndex1 = downloadGroupInfo.getRunningDownloadIndex();
                        if (runningDownloadIndex1 >= 0) {
                            downloadGroupInfo.downloadsInfo[runningDownloadIndex1].setCurrentError(INTEGRITY_CHECK_FAILED);
                        }
                        downloadGroupInfo.setRunningDownloadIndex(-1);
                        downloadsStarted = false;
                        callback.onError(downloadGroupInfo, finishedDownload, INTEGRITY_CHECK_FAILED);
                    };
                }
            };
            if (internalFolder == null && !finishedDownload.shouldUnzip()) {  //it is null only when we downloaded a file, not a .zip folder
                runIntegrityCheck(attempt, runningDownloadIndex,
                        () -> NeuralNetworkApi.testModelIntegrity(finishedDownload.getDestinationCompletePath(), listener),
                        () -> { if (result[0] != null) result[0].run(); else failDownload(runningDownloadIndex, INTEGRITY_CHECK_FAILED); });
            } else if (internalFolder != null && finishedDownload.shouldUnzip) {  //if it is not null it means that we should test the models inside the top folder of an extracted .zip file
                runIntegrityCheck(attempt, runningDownloadIndex, () -> {
                        String folderPath;
                        if (!internalFolder.isEmpty()) {
                            folderPath = finishedDownload.getDestinationPath() + "/" + internalFolder + "/";
                        } else {
                            folderPath = finishedDownload.getDestinationPath();
                        }
                        NeuralNetworkApi.testFolderIntegrity(folderPath, listener);
                }, () -> { if (result[0] != null) result[0].run(); else failDownload(runningDownloadIndex, INTEGRITY_CHECK_FAILED); });
            } else {
                listener.onError(new int[]{GENERAL_ERROR}, 0);
                if (result[0] != null) result[0].run();
            }
        }
    }

    private void runIntegrityCheck(long attempt, int index, Runnable check, Runnable completion) {
        testIntegrityThread = new Thread(() -> {
            try {
                check.run();
            } finally {
                Thread finished = Thread.currentThread();
                mainHandler.post(() -> {
                    if (testIntegrityThread == finished) testIntegrityThread = null;
                    if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                    completion.run();
                });
            }
        }, "model-integrity");
        testIntegrityThread.start();
    }

    private void startNextDownload(){
        int runningDownloadIndex = downloadGroupInfo.getRunningDownloadIndex();
        if (!downloadsStarted || runningDownloadIndex < 0) return;
        if (runningDownloadIndex < downloadGroupInfo.downloadsInfo.length - 1) {
            //we start the next download and notify the end of the current one
            lastDownloadSuccessIndex = runningDownloadIndex;
            callback.onCompleted(downloadGroupInfo, downloadGroupInfo.downloadsInfo[runningDownloadIndex]);
            startDownload(runningDownloadIndex+1);
        } else {
            //we notify the end of all downloads
            downloadGroupInfo.setAllDownloadCompleted(true);
            callback.onAllCompleted(downloadGroupInfo);
        }
    }

    private int calculateTotalProgress(int lastDownloadSuccessIndex, long downloadedKb) {
        if(lastDownloadSuccessIndex <= downloadGroupInfo.downloadsInfo.length-1) {
            double totalSize = 0;
            for (DownloadInfo downloadInfo : downloadGroupInfo.downloadsInfo) {
                totalSize = totalSize + downloadInfo.getSize();
            }
            int baseProgress = 0;
            double baseSize = 0;
            for (int i = 0; i <= lastDownloadSuccessIndex; i++) {
                baseSize = baseSize + downloadGroupInfo.downloadsInfo[i].getSize();
            }
            baseProgress = (int) ((baseSize * 100) / totalSize);
            int currentProgress = (int) (((downloadedKb) * 100) / totalSize);
            return baseProgress + currentProgress;
        }else{
            return 100;
        }
    }

    private int calculateProgress(int downloadIndex, long downloadedKb){
        if(downloadIndex >= 0 && downloadIndex < downloadGroupInfo.downloadsInfo.length) {
            double totalSizeKb = downloadGroupInfo.downloadsInfo[downloadIndex].getSize();
            return (int) ((downloadedKb * 100) / totalSizeKb);
        }else{
            return 0;
        }
    }

    private void unpackZip(String path, String zipname, UnpackListener listener) {
        if (unzipThread != null) return;
        final long attempt = generation;
        final int index = downloadGroupInfo.getRunningDownloadIndex();
        unzipThread = new Thread(() -> {
            String folder = null;
            IOException failure = null;
            try {
                folder = extractZip(path, zipname);
            } catch (IOException error) {
                failure = error;
            }
            final String topFolder = folder;
            final IOException error = failure;
            Thread finished = Thread.currentThread();
            // Clear the worker and transition in one main-thread callback. A second finally
            // callback could otherwise release a pause belonging to the next stage.
            mainHandler.post(() -> {
                if (unzipThread == finished) unzipThread = null;
                if (finishPausedOperation(attempt) || !isActive(attempt, index)) return;
                if (listener != null) {
                    if (error == null) listener.onSuccess(topFolder);
                    else listener.onFailure();
                }
            });
        }, "model-unzip");
        unzipThread.start();
    }

    private String extractZip(String path, String zipname) throws IOException {
        Path zipPath = new File(path, zipname).toPath();
        String root = new File(path).getCanonicalPath() + File.separator;
        String topFolder = "";
        boolean hasEntries = false;
        try (InputStream input = Files.newInputStream(zipPath);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                checkExtractionInterrupted();
                hasEntries = true;
                String filename = entry.getName();
                if (topFolder.isEmpty() && filename.contains("/")) {
                    topFolder = filename.substring(0, filename.indexOf("/"));
                }
                File destination = new File(path, filename);
                if (!destination.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Archive entry is outside the model directory");
                }
                // Completed entries survive a pause; incomplete entries are staged separately.
                if (destination.exists()) {
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!destination.mkdirs() && !destination.isDirectory()) {
                        throw new IOException("Cannot create model directory");
                    }
                    zip.closeEntry();
                    continue;
                }
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Cannot create model directory");
                }
                File temporary = new File(destination.getPath() + ".tmp");
                boolean published = false;
                try {
                    try (FileOutputStream output = new FileOutputStream(temporary)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            checkExtractionInterrupted();
                            output.write(buffer, 0, count);
                        }
                    }
                    checkExtractionInterrupted();
                    if (!temporary.renameTo(destination)) throw new IOException("Cannot publish model file");
                    published = true;
                } finally {
                    if (!published) Files.deleteIfExists(temporary.toPath());
                }
                zip.closeEntry();
            }
        }
        checkExtractionInterrupted();
        if (!hasEntries) throw new IOException("Model archive is empty or invalid");
        return topFolder;
    }

    private static void checkExtractionInterrupted() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Model extraction paused or cancelled");
        }
    }

    public abstract static class ClientCallback {
        public abstract void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity);
        public void onDownloadCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download){}
        public void onUnzippingCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download){}
        public void onIntegrityTestCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download){}
        public abstract void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download);
        public abstract void onAllCompleted(DownloadGroupInfo downloadGroup);
        public abstract void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason);
    }

    public abstract static class UnpackListener {
        public abstract void onSuccess(String topInternalFolder);
        public abstract void onFailure();
    }

    public static class DownloadError {
        public DownloadInfo downloadInfo;
        public int reason;

        public DownloadError(DownloadInfo downloadInfo, int reason) {
            this.downloadInfo = downloadInfo;
            this.reason = reason;
        }
    }
}

