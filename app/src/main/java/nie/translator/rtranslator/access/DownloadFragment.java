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
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import nie.translator.rtranslator.downloader.ModelDownloadManifest;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import nie.translator.rtranslator.downloader.ModelDownloadLog;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
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
    private TextView sourceStatusText;
    private final ActivityResultLauncher<String[]> modelFilePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importSelectedModel);
    private final Runnable sourceStatusRefresh = new Runnable() {
        @Override public void run() {
            if (sourceStatusText != null) sourceStatusText.setText(ModelDownloadLog.summary());
            if (mainHandler != null && isAdded()) mainHandler.postDelayed(this, 1000);
        }
    };
    private DownloadManager.Callback downloadManagerCallback;
    private boolean guiStateRestored = false;

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
        sourceStatusText = view.findViewById(R.id.download_source_status);
        Button importButton = view.findViewById(R.id.import_model_button);
        importButton.setOnClickListener(v -> modelFilePicker.launch(new String[]{"application/octet-stream", "application/zip", "*/*"}));
        Button logButton = view.findViewById(R.id.download_log_button);
        logButton.setOnClickListener(v -> new AlertDialog.Builder(requireContext())
                .setTitle("模型下载日志")
                .setMessage(ModelDownloadLog.history().isEmpty() ? "暂无记录" : ModelDownloadLog.history())
                .setPositiveButton("关闭", null)
                .show());
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();

        DOWNLOAD_INFOS = global.getInitialDownloadInfo().downloadsInfo;

        mainHandler = new android.os.Handler(Looper.getMainLooper());
        downloader = new DownloadManager(global);
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
        if (mainHandler != null) mainHandler.post(sourceStatusRefresh);
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
        guiStateRestored = false;
        if (mainHandler != null) mainHandler.removeCallbacks(sourceStatusRefresh);
        downloader.unsubscribe();
        //we cancel the storage warning (in this way when the user reopens the app the warning is shown only if the storage is still low)
        storageWarningText.setVisibility(View.GONE);
    }


    private void importSelectedModel(Uri uri) {
        if (uri == null || getContext() == null) return;
        String filename = null;
        try (Cursor cursor = requireContext().getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) filename = cursor.getString(0);
        } catch (Exception ignored) { }
        if (filename == null || filename.contains("/") || filename.contains("\\\\")
                || filename.equals(".") || filename.equals("..")
                || !filename.matches("[A-Za-z0-9_.-]+")) {
            Toast.makeText(requireContext(), "文件名无效，请选择原始模型文件", Toast.LENGTH_LONG).show();
            return;
        }
        final String selectedName = filename;
        boolean supported = false;
        if (DOWNLOAD_INFOS != null) for (DownloadInfo info : DOWNLOAD_INFOS) {
            String url = info.getUrl();
            if (url != null && url.endsWith("/" + selectedName)
                    && ModelDownloadManifest.forUrl(url) != null) supported = true;
        }
        if (!supported) {
            Toast.makeText(requireContext(), "不是当前下载任务需要的模型文件：" + selectedName, Toast.LENGTH_LONG).show();
            return;
        }
        File dir = requireContext().getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(requireContext(), "无法访问应用文件目录", Toast.LENGTH_LONG).show();
            return;
        }
        final File destination = new File(dir, selectedName);
        final android.content.ContentResolver resolver = requireContext().getContentResolver();
        ModelDownloadLog.record("正在导入 " + selectedName, "local-file");
        Executors.newSingleThreadExecutor().execute(() -> {
            File tmp = new File(dir, selectedName + ".importing");
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("无法读取文件");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.getFD().sync();
                if (!tmp.renameTo(destination)) throw new IOException("保存文件失败");
                ModelDownloadLog.record("已保存 " + selectedName + "，请暂停并重新打开应用以导入校验", "local-file");
                if (isAdded()) requireActivity().runOnUiThread(() ->
                        new AlertDialog.Builder(requireContext()).setTitle("模型文件已导入")
                                .setMessage(selectedName + " 已复制到应用目录。请退出并重新打开应用，系统将校验并复用该文件。")
                                .setPositiveButton("确定", null).show());
            } catch (Exception e) {
                tmp.delete();
                ModelDownloadLog.record("本地导入失败：" + e.getMessage(), "local-file");
                if (isAdded()) requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
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

    private void retryCurrentDownload(){
        startAllDownloads();
    }

    private void startAllDownloads(){
        downloader.startAllDownloads();
        //we change the icon and tag
        setPauseButtonStatus(false);
    }
}