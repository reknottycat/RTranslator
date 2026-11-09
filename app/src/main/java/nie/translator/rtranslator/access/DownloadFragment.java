/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.access;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.button.MaterialButton;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.downloader.DownloadSourceOrder;
import nie.translator.rtranslator.downloader.DownloadTrace;
import nie.translator.rtranslator.downloader.LocalModelFiles;
import nie.translator.rtranslator.downloader.ModelDownloadManifest;
import nie.translator.rtranslator.tools.DownloaderTools;

public class DownloadFragment extends Fragment {
    @Nullable
    public static DownloadInfo[] DOWNLOAD_INFOS;
    private static final long INTERVAL_TIME_FOR_GUI_UPDATES_MS = 100;  //500
    private AccessActivity activity;
    private Global global;
    private DownloadManager downloader;
    private android.os.Handler mainHandler;   // handler that can be used to post to the main thread

    //Gui components
    private ImageButton retryButton;
    private ImageButton pauseButton;
    private TextView downloadErrorText;
    private TextView transferErrorText;
    private TextView storageWarningText;
    private LinearProgressIndicator progressBar;
    private TextView progressDescriptionText;
    private TextView progressNumbersText;
    private DownloadManager.Callback downloadManagerCallback;
    private boolean guiStateRestored = false;
    private MaterialButton importModelButton;
    private MaterialButton sourceModeButton;
    private MaterialButton toggleLogButton;
    private TextView downloadSourceStatus;
    private TextView downloadLogText;
    private ScrollView downloadLogScroll;
    private boolean importingFromPhone = false;

    private final ActivityResultLauncher<String[]> chooseModelFiles =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),
                    this::onDocumentsSelected);

    private final Runnable updateDownloadTrace = new Runnable() {
        @Override public void run() {
            if (downloadSourceStatus == null || getView() == null || mainHandler == null) return;
            String status = DownloadTrace.status();
            if (!status.equals(downloadSourceStatus.getText().toString())) {
                downloadSourceStatus.setText(status);
            }
            if (downloadLogScroll != null && downloadLogScroll.getVisibility() == View.VISIBLE) {
                String detail = DownloadTrace.recentLines();
                if (!detail.equals(downloadLogText.getText().toString())) {
                    downloadLogText.setText(detail);
                }
            }
            mainHandler.postDelayed(this, 750);
        }
    };

    public DownloadFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        retryButton = view.findViewById(R.id.retryButton);
        downloadErrorText = view.findViewById(R.id.text_error_download);
        transferErrorText = view.findViewById(R.id.text_error_transfer);
        storageWarningText = view.findViewById(R.id.text_error_storage);
        progressBar = view.findViewById(R.id.barRam);
        progressDescriptionText = view.findViewById(R.id.progress_description);
        pauseButton = view.findViewById(R.id.pauseButton);
        pauseButton.setTag("iconPause");
        progressNumbersText = view.findViewById(R.id.progress_numbers);
        importModelButton = view.findViewById(R.id.importModelButton);
        sourceModeButton = view.findViewById(R.id.sourceModeButton);
        toggleLogButton = view.findViewById(R.id.toggleDownloadLogButton);
        downloadSourceStatus = view.findViewById(R.id.downloadSourceStatus);
        downloadLogText = view.findViewById(R.id.downloadLogText);
        downloadLogScroll = view.findViewById(R.id.downloadLogScroll);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();

        DOWNLOAD_INFOS = global.getInitialDownloadInfo().downloadsInfo;

        mainHandler = new android.os.Handler(Looper.getMainLooper());
        downloader = new DownloadManager(global);
        importModelButton.setOnClickListener(v -> {
            if (importingFromPhone) return;
            DownloadTrace.state("打开系统文件选择器，等待选择模型文件");
            downloader.pauseAllDownloads();
            setPauseButtonStatus(true);
            chooseModelFiles.launch(new String[]{"*/*"});
        });
        sourceModeButton.setOnClickListener(v -> {
            String[] options = {"国内镜像优先（推荐）", "GitHub 官方优先", "仅使用加速镜像"};
            new AlertDialog.Builder(requireContext())
                    .setTitle("模型下载来源")
                    .setSingleChoiceItems(options, DownloadSourceOrder.get(), (dialog, selected) -> {
                        DownloadSourceOrder.set(requireContext(), selected);
                        sourceModeButton.setText(getString(R.string.cn_source_choose)
                                + " · " + DownloadSourceOrder.modeName(selected));
                        downloader.pauseAllDownloads();
                        startAllDownloads();
                        dialog.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        sourceModeButton.setText(getString(R.string.cn_source_choose)
                + " · " + DownloadSourceOrder.modeName(DownloadSourceOrder.get()));
        MaterialButton parallelismButton = requireView().findViewById(R.id.parallelismButton);
        parallelismButton.setText(getString(R.string.cn_parallelism_choose)
                + " · " + DownloadSourceOrder.parallelThreads(requireContext()) + " 路");
        parallelismButton.setOnClickListener(v ->
                new AlertDialog.Builder(requireContext())
                        .setTitle("4 MiB 分块下载并发数")
                        .setSingleChoiceItems(new String[]{"14 路（推荐）", "28 路（高速网络）"},
                                DownloadSourceOrder.parallelThreads(requireContext()) == 28 ? 1 : 0,
                                (dialog, selected) -> {
                                    int threads = selected == 1 ? 28 : 14;
                                    DownloadSourceOrder.setParallelThreads(requireContext(), threads);
                                    parallelismButton.setText(getString(R.string.cn_parallelism_choose)
                                            + " · " + threads + " 路");
                                    downloader.pauseAllDownloads();
                                    startAllDownloads();
                                    dialog.dismiss();
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
        toggleLogButton.setOnClickListener(v -> {
            boolean expand = downloadLogScroll.getVisibility() != View.VISIBLE;
            downloadLogScroll.setVisibility(expand ? View.VISIBLE : View.GONE);
            toggleLogButton.setText(expand ? R.string.cn_hide_log : R.string.cn_show_log);
            if (expand) downloadLogText.setText(DownloadTrace.recentLines());
        });
        viewCopyLog().setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("RTranslator model log",
                    DownloadTrace.status() + System.lineSeparator() + DownloadTrace.recentLines()));
            Toast.makeText(requireContext(), R.string.cn_copy_log, Toast.LENGTH_SHORT).show();
        });
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(downloadErrorText.getVisibility() == View.VISIBLE){  //that means that we should restart the download
                    downloadErrorText.setVisibility(View.GONE);
                    transferErrorText.setVisibility(View.GONE);
                    retryButton.setVisibility(View.GONE);
                    retryCurrentDownload();
                }
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(pauseButton.getTag().equals("iconPause")){
                    //we pause the download
                    boolean success = downloader.pauseAllDownloads();
                    if(success) {
                        //we change the icon and tag
                        setPauseButtonStatus(true);
                    }
                }else{
                    startAllDownloads();
                }
            }
        });

        downloadManagerCallback = new DownloadManager.Callback() {
            public void onServiceConnected(){
                if(!guiStateRestored) {
                    ArrayList<DownloadGroupInfo> downloadStatus = downloader.getDownloadsStatus();
                    // we change the GUI based on current download status
                    restoreGuiState(downloadStatus);
                }
            }

            @Override
            public void onAllCompleted(DownloadGroupInfo downloadGroup) {
                activity.startFragment(AccessActivity.MODEL_MANAGER, null);
            }

            @Override
            public void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download) {
                //for now we do nothing here
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                //update of progress bar
                int progressNormalized = totalProgress * progressBar.getMax() / 100;
                progressBar.setProgress(progressNormalized, true);
                //we update the progressNumbersText
                double totalSize = 0;
                for (DownloadInfo info : DOWNLOAD_INFOS) {
                    totalSize = totalSize + info.getSize();
                }
                totalSize = totalSize/1000000;   //we convert from Kb to Gb
                float downloadedGb = (float) (totalProgress*totalSize/100);    //progress : 100 = x : totalSize   (where x is downloadedGb)
                DecimalFormat decimalFormat = new DecimalFormat("#.##");
                progressNumbersText.setText(decimalFormat.format(downloadedGb)+" / "+decimalFormat.format(totalSize)+" GB");
                //update of the progress description
                if(testingIntegrity){
                    progressDescriptionText.setText(getString(R.string.description_integrity_check, download.getName()));
                }else if(unzipping) {
                    progressDescriptionText.setText(getString(R.string.description_unzip, download.getName()));
                }else{
                    progressDescriptionText.setText(getString(R.string.description_download, download.getName()));
                }
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                showDownloadError();
            }
        };
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(updateDownloadTrace);
            mainHandler.post(updateDownloadTrace);
        }
        if(global != null && DOWNLOAD_INFOS != null) {
            //if the internal or external free memory are low, we show a warning
            double requiredSize = 0;
            for (DownloadInfo downloadInfo : DOWNLOAD_INFOS) {
                requiredSize = requiredSize + downloadInfo.getSize();
            }
            requiredSize = requiredSize / 1000;   //we convert from Kb to Mb
            requiredSize = requiredSize + 800;   //we add a margin (because the transfer process requires more space)
            if(global.getAvailableExternalMemorySize() < requiredSize || global.getAvailableInternalMemorySize() < requiredSize){
                //we show the warning
                storageWarningText.setVisibility(View.VISIBLE);
            }

            boolean serviceStarted = downloader.subscribeAndResumeDownload(downloadManagerCallback);

            ArrayList<DownloadGroupInfo> downloadStatus = downloader.getSavedDownloadStatus();
            // we eventually start the download if it is the first time
            DownloadGroupInfo downloadGroupInfo = new DownloadGroupInfo(DOWNLOAD_INFOS);
            if(downloadStatus == null || !downloadStatus.contains(downloadGroupInfo)){
                downloader.startDownload(downloadGroupInfo);
            }else if(!serviceStarted){
                // we change the GUI based on current saved download status
                // normally we do this when the service starts, but if it won't start (paused download or other reasons)
                // we restore the GUI state based on the saved download state instead.
                restoreGuiState(downloadStatus);
            }
        }
    }

    private void restoreGuiState(ArrayList<DownloadGroupInfo> downloadStatus){
        // we change the GUI based on current download status
        if(downloadStatus != null){
            guiStateRestored = true;
            int index = downloadStatus.indexOf(new DownloadGroupInfo(DOWNLOAD_INFOS));
            if(index != -1) {
                if (downloadStatus.get(index).isAllDownloadCompleted()) {
                    downloadManagerCallback.onAllCompleted(downloadStatus.get(index));
                } else {
                    DownloadInfo runningDownload = downloadStatus.get(index).getRunningDownload();
                    if (runningDownload != null) {
                        if (runningDownload.getCurrentError() != -1) {
                            //the download has an error
                            downloadManagerCallback.onError(downloadStatus.get(index), runningDownload, runningDownload.getCurrentError());
                        } else {
                            //the download is running
                            downloadManagerCallback.onProgress(downloadStatus.get(index), runningDownload, downloadStatus.get(index).getCurrentProgress(), runningDownload.getCurrentProgress(), runningDownload.isUnzipping(), runningDownload.isTestingIntegrity());
                        }
                    }else{
                        int firstIncompleteIndex = DownloaderTools.findFirstIncompletedDownload(downloadStatus.get(index));
                        if(firstIncompleteIndex < downloadStatus.get(index).downloadsInfo.length) {
                            //the download is paused
                            DownloadInfo pausedDownload = downloadStatus.get(index).downloadsInfo[firstIncompleteIndex];
                            downloadManagerCallback.onProgress(downloadStatus.get(index), pausedDownload, downloadStatus.get(index).getCurrentProgress(), pausedDownload.getCurrentProgress(), pausedDownload.isUnzipping(), pausedDownload.isTestingIntegrity());
                            //we change the pause icon and tag
                            setPauseButtonStatus(true);
                        }else{
                            // the download group is completed even if for some errors the group is not marked as completed
                            downloadManagerCallback.onAllCompleted(downloadStatus.get(index));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mainHandler != null) mainHandler.removeCallbacks(updateDownloadTrace);
        guiStateRestored = false;
        downloader.unsubscribe();
        //we cancel the storage warning (in this way when the user reopens the app the warning is shown only if the storage is still low)
        storageWarningText.setVisibility(View.GONE);
    }

    private void showDownloadError(){
        //we show the download error and the retry button
        mainHandler.post(() -> {
            downloadErrorText.setVisibility(View.VISIBLE);
            transferErrorText.setVisibility(View.GONE);
            retryButton.setVisibility(View.VISIBLE);
            //we change the icon and tag of the pauseButton
            setPauseButtonStatus(true);
        });
    }

    private void setPauseButtonStatus(boolean paused){
        if(paused){
            pauseButton.setImageResource(R.drawable.play_icon);
            pauseButton.setTag("iconPlay");
            pauseButton.setContentDescription(getResources().getString(R.string.resume));
        }else{
            pauseButton.setImageResource(R.drawable.pause_icon);
            pauseButton.setTag("iconPause");
            pauseButton.setContentDescription(getResources().getString(R.string.pause));
        }
    }

    private MaterialButton viewCopyLog() {
        return requireView().findViewById(R.id.copyDownloadLogButton);
    }

    private void onDocumentsSelected(List<Uri> files) {
        if (files == null || files.isEmpty()) {
            DownloadTrace.record("文件选择已取消，继续下载");
            if (downloader != null) startAllDownloads();
            return;
        }
        importingFromPhone = true;
        importModelButton.setEnabled(false);
        final Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            int importedCount = 0;
            int failedCount = 0;
            for (Uri uri : files) {
                try {
                    String name = pickedFilename(appContext, uri);
                    ModelDownloadManifest.Entry metadata = ModelDownloadManifest.forFilename(name);
                    if (metadata == null) {
                        throw new IOException("未知或不支持的官方模型文件名：" + name);
                    }
                    File externalDir = appContext.getExternalFilesDir(null);
                    if (externalDir == null) throw new IOException("无法访问应用模型导入目录");
                    DownloadTrace.state("正在导入选中的文件：" + name);
                    try (InputStream stream = appContext.getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new IOException("文件无法读取：" + name);
                        LocalModelFiles.stagePickedModel(stream, new File(externalDir, name),
                                metadata.sizeBytes, metadata.sha256);
                    }
                    importedCount++;
                    DownloadTrace.state("文件已验证并放入导入目录：" + name);
                } catch (Exception error) {
                    failedCount++;
                    DownloadTrace.state("文件导入失败：" + error.getMessage());
                }
            }
            final int done = importedCount;
            final int rejected = failedCount;
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    importingFromPhone = false;
                    if (importModelButton != null) importModelButton.setEnabled(true);
                    Toast.makeText(appContext,
                            "已导入 " + done + " 个，失败 " + rejected + " 个；详情见日志",
                            Toast.LENGTH_LONG).show();
                    if (downloader != null) startAllDownloads();
                });
            }
        }, "picked-model-import").start();
    }

    private static String pickedFilename(Context context, Uri uri) throws IOException {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException error) {
            throw new IOException("无法读取模型文件名", error);
        }
        throw new IOException("系统文件选择器未提供文件名");
    }

    private void retryCurrentDownload(){
        startAllDownloads();
    }

    private void startAllDownloads(){
        downloader.startAllDownloads();
        //we change the icon and tag
        setPauseButtonStatus(false);
    }
}