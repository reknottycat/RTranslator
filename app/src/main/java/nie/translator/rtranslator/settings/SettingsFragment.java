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

package nie.translator.rtranslator.settings;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nie.translator.rtranslator.BuildConfig;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.GalleryImageSelector;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.MediaTekModelPack;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.QnnModelPack;


public class SettingsFragment extends PreferenceFragmentCompat {
    public static final String DEFAULT_NAME = "user";
    //self callbacks
    public static final int ON_INTERNET_LACK = 0;
    //internet Lack types
    public static final int DOWNLOAD_LANGUAGES = 0;
    public static final int ON_MISSING_GOOGLE_TTS = 2;
    //variables
    private int downloads = 0;
    private boolean isDownloading = false;
    //objects
    private GalleryImageSelector userImageContainer;
    private ProgressBar progressBar;
    private Global global;
    private SettingsActivity activity;
    private UserNamePreference userNamePreference;
    private SupportLanguagesQuality supportLanguagesQualityPreference;

    private ShowOriginalTranscriptionMsgPreference showOriginalTranscriptionMsgPreference;
    private SupportTtsQualityPreference supportTtsQualityPreference;
    private LanguagePreference languagePreference;
    private Preference qnnModelPreference;
    private ActivityResultLauncher<String[]> qnnModelPicker;
    private final Runnable refreshQnnModel = this::updateQnnModelSummary;
    private Preference mediaTekModelPreference;
    private ActivityResultLauncher<String[]> mediaTekModelPicker;
    private final Runnable refreshMediaTekModel = this::updateMediaTekModelSummary;

    private Handler selfHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            final Bundle data = message.getData();
            removeDownload();
            switch (data.getInt("type")) {
                case ON_INTERNET_LACK: {
                    //creation of the dialog.
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                    builder.setCancelable(false);

                    switch (data.getInt("action")) {
                        case DOWNLOAD_LANGUAGES: {
                            builder.setMessage(R.string.error_internet_lack_loading_languages);
                            builder.setPositiveButton(R.string.retry, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    supportTtsQualityPreference.downloadLanguages();
                                }
                            });
                            builder.setNegativeButton(android.R.string.cancel, null);
                            break;
                        }
                    }
                    AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                    break;
                }
                case ON_MISSING_GOOGLE_TTS:
                    activity.showMissingGoogleTTSDialog(null);
                    break;

            }
            return true;
        }
    });


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);
        if (BuildConfig.QNN_WHISPER_TEST && (!BuildConfig.MEDIATEK_WHISPER_TEST || !MediaTekModelPack.isSupportedDevice())) {
            addQnnModelPreference();
        }
        if (BuildConfig.MEDIATEK_WHISPER_TEST && (MediaTekModelPack.isSupportedDevice() || !QnnModelPack.isQualcommDevice())) {
            addMediaTekModelPreference();
        }
    }

    private void addQnnModelPreference() {
        qnnModelPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                QnnModelPack.startImport(requireContext(), uri);
                updateQnnModelSummary();
            }
        });
        qnnModelPreference = new Preference(requireContext());
        qnnModelPreference.setKey("qnnWhisperModel");
        qnnModelPreference.setTitle(R.string.qnn_whisper_title);
        qnnModelPreference.setIconSpaceReserved(false);
        qnnModelPreference.setOnPreferenceClickListener(preference -> {
            showQnnModelChoices();
            return true;
        });
        Preference modelsManager = findPreference("modelsManager");
        modelsManager.getParent().addPreference(qnnModelPreference);
        updateQnnModelSummary();
    }

    private void showQnnModelChoices() {
        CharSequence[] options = {
                getText(R.string.qnn_whisper_download_elite),
                getText(R.string.qnn_whisper_import),
                getText(R.string.qnn_whisper_download_gen5)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.qnn_whisper_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 1) {
                        qnnModelPicker.launch(new String[]{"application/zip", "application/x-zip-compressed",
                                "application/octet-stream"});
                    } else {
                        String url = which == 0 ? QnnModelPack.ELITE_DOWNLOAD : QnnModelPack.ELITE_GEN5_DOWNLOAD;
                        openModelDownload(url);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateQnnModelSummary() {
        selfHandler.removeCallbacks(refreshQnnModel);
        if (qnnModelPreference == null || !isAdded()) return;
        if (!QnnModelPack.isQualcommDevice()) {
            qnnModelPreference.setEnabled(false);
            qnnModelPreference.setSummary(R.string.qnn_whisper_other_device);
            return;
        }
        int progress = QnnModelPack.importProgress();
        qnnModelPreference.setEnabled(progress < 0);
        if (progress >= 0) {
            qnnModelPreference.setSummary(getString(R.string.qnn_whisper_importing, progress));
            selfHandler.postDelayed(refreshQnnModel, 500);
            return;
        }
        int summary = R.string.qnn_whisper_no_model;
        if (QnnModelPack.activeDirectory(requireContext()) != null) {
            switch (QnnModelPack.backendStatus()) {
                case QnnModelPack.BACKEND_QNN:
                    summary = R.string.qnn_whisper_active;
                    break;
                case QnnModelPack.BACKEND_CPU_FALLBACK:
                    summary = R.string.qnn_whisper_cpu_fallback;
                    break;
                default:
                    summary = R.string.qnn_whisper_waiting;
                    break;
            }
        }
        String text = getString(summary);
        if (QnnModelPack.importFailed()) text = getString(R.string.qnn_whisper_import_failed, text);
        qnnModelPreference.setSummary(text);
    }

    private void addMediaTekModelPreference() {
        mediaTekModelPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                MediaTekModelPack.startImport(requireContext(), uri);
                updateMediaTekModelSummary();
            }
        });
        mediaTekModelPreference = new Preference(requireContext());
        mediaTekModelPreference.setKey("mediaTekWhisperModel");
        mediaTekModelPreference.setTitle(R.string.mtk_whisper_title);
        mediaTekModelPreference.setIconSpaceReserved(false);
        mediaTekModelPreference.setOnPreferenceClickListener(preference -> {
            showMediaTekModelChoices();
            return true;
        });
        Preference modelsManager = findPreference("modelsManager");
        modelsManager.getParent().addPreference(mediaTekModelPreference);
        updateMediaTekModelSummary();
    }

    private void showMediaTekModelChoices() {
        CharSequence[] options = {getText(R.string.mtk_whisper_download), getText(R.string.mtk_whisper_import)};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.mtk_whisper_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openModelDownload(MediaTekModelPack.MODEL_DOWNLOAD);
                    } else {
                        mediaTekModelPicker.launch(new String[]{"*/*"});
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openModelDownload(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.qnn_whisper_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void updateMediaTekModelSummary() {
        selfHandler.removeCallbacks(refreshMediaTekModel);
        if (mediaTekModelPreference == null || !isAdded()) return;
        if (!MediaTekModelPack.isSupportedDevice()) {
            mediaTekModelPreference.setEnabled(false);
            mediaTekModelPreference.setSummary(R.string.mtk_whisper_other_device);
            return;
        }
        int progress = MediaTekModelPack.importProgress();
        mediaTekModelPreference.setEnabled(progress < 0);
        if (progress >= 0) {
            mediaTekModelPreference.setSummary(getString(R.string.qnn_whisper_importing, progress));
            selfHandler.postDelayed(refreshMediaTekModel, 500);
            return;
        }
        int summary = R.string.mtk_whisper_no_model;
        if (MediaTekModelPack.activeFile(requireContext()) != null) {
            switch (MediaTekModelPack.backendStatus()) {
                case MediaTekModelPack.BACKEND_NPU:
                    summary = R.string.mtk_whisper_active;
                    break;
                case MediaTekModelPack.BACKEND_CPU_FALLBACK:
                    summary = R.string.mtk_whisper_cpu_fallback;
                    break;
                default:
                    summary = R.string.mtk_whisper_waiting;
                    break;
            }
        }
        String text = getString(summary);
        if (MediaTekModelPack.importFailed()) text = getString(R.string.mtk_whisper_import_failed, text);
        mediaTekModelPreference.setSummary(text);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) super.onCreateAdapter(super.getPreferenceScreen());
        RecyclerView recyclerView = view.findViewById(R.id.preference_recyclerview);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));


        // to scroll the scrollview up
        /*recyclerView.setFocusable(false);
        image.requestFocus();*/
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (SettingsActivity) requireActivity();
        global = (Global) activity.getApplication();
        progressBar = activity.findViewById(R.id.progressBarSettings);

        // user image initialization
        final UserImagePreference userImagePreference = (UserImagePreference) findPreference("userImage");
        userImagePreference.setLifecycleListener(new UserImagePreference.LifecycleListener() {
            @Override
            public void onBindViewHolder() {
                userImageContainer = new GalleryImageSelector(userImagePreference.getImage(), activity, SettingsFragment.this, R.drawable.user_icon, activity.getPackageName() + ".fileprovider");
            }
        });

        // user name initialization
        userNamePreference = (UserNamePreference) findPreference("changeName");
        userNamePreference.setActivity(activity);

        // change microphone sensibility initialization
        SeekBarPreference micSensibilityPreference = (SeekBarPreference) findPreference("micSensibilitySetting");
        micSensibilityPreference.initialize(activity, SeekBarPreference.MIC_SENSIBILITY_MODE);

        // language support option with low quality initialization
        supportLanguagesQualityPreference = (SupportLanguagesQuality) findPreference("languagesNNQualityLow");
        supportLanguagesQualityPreference.setFragment(this);

        // show transcription option initialization
        showOriginalTranscriptionMsgPreference = (ShowOriginalTranscriptionMsgPreference) findPreference("ShowOriginalTranscriptionMsgPreference");
        showOriginalTranscriptionMsgPreference.setFragment(this);

        // language support option with low quality tts initialization
        supportTtsQualityPreference = (SupportTtsQualityPreference) findPreference("languagesQualityLow");
        supportTtsQualityPreference.setFragment(this);

        // languages list initialization
        languagePreference = (LanguagePreference) findPreference("language");
        languagePreference.setFragment(this);
        languagePreference.initializeLanguagesList();

        // link tts settings initialization
        Preference ttsPreference = findPreference("tts");
        ttsPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent();
                intent.setAction("com.android.settings.TTS_SETTINGS");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return true;
            }
        });

        // link models manager initialization
        Preference modelsManagerPreference = findPreference("modelsManager");
        modelsManagerPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                activity.startFragment(SettingsActivity.MODEL_MANAGER, null);
                return true;
            }
        });

        // change microphone sensibility initialization
        SeekBarPreference beamSizePreference = (SeekBarPreference) findPreference("BeamSizeSetting");
        beamSizePreference.initialize(activity, SeekBarPreference.BEAM_SIZE_MODE);

        // change microphone sensibility initialization
        SeekBarPreference speechTimeoutPreference = (SeekBarPreference) findPreference("SpeechTimeoutSetting");
        speechTimeoutPreference.initialize(activity, SeekBarPreference.SPEECH_TIMEOUT_MODE);

        // change microphone sensibility initialization
        SeekBarPreference prevVoiceDurationPreference = (SeekBarPreference) findPreference("PrevVoiceDurationSetting");
        prevVoiceDurationPreference.initialize(activity, SeekBarPreference.PREV_VOICE_DURATION_MODE);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateQnnModelSummary();
        updateMediaTekModelSummary();
    }

    @Override
    public void onPause() {
        selfHandler.removeCallbacks(refreshQnnModel);
        selfHandler.removeCallbacks(refreshMediaTekModel);
        super.onPause();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //call onActivityResult
        userImageContainer.onActivityResult(requestCode, resultCode, data, true);
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    public void addDownload() {
        if (downloads == 0) {
            isDownloading = true;
            progressBar.setVisibility(View.VISIBLE);
        }
        downloads++;
    }

    public void removeDownload() {
        if (downloads > 0) {
            if (downloads == 1) {
                progressBar.setVisibility(View.GONE);
                isDownloading = false;
            }
            downloads--;
        }
    }

    public LanguagePreference getLanguagePreference() {
        return languagePreference;
    }

    private void notifyInternetLack(int action, String data) {
        Messenger selfMessenger = new Messenger(selfHandler);
        Message message = Message.obtain();
        Bundle bundle = new Bundle();
        bundle.putInt("type", ON_INTERNET_LACK);
        bundle.putInt("action", action);
        bundle.putString("newPassword", data);
        message.setData(bundle);
        try {
            selfMessenger.send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void notifyMissingGoogleTTSDialog() {
        Messenger selfMessenger = new Messenger(selfHandler);
        Message message = Message.obtain();
        Bundle bundle = new Bundle();
        bundle.putInt("command", ON_MISSING_GOOGLE_TTS);
        message.setData(bundle);
        try {
            selfMessenger.send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }


    public void onFailure(int[] reasons, long value, int action, @Nullable String data) {
        for (int aReason : reasons) {
            switch (aReason) {
                case ErrorCodes.SAFETY_NET_EXCEPTION:
                case ErrorCodes.MISSED_CONNECTION:
                    notifyInternetLack(action, data);
                    break;
                case ErrorCodes.MISSING_GOOGLE_TTS:
                    notifyMissingGoogleTTSDialog();
                default:
                    activity.onError(aReason, value);
                    break;
            }
        }
    }
}
