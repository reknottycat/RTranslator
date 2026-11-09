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

package nie.translator.rtranslator.voice_translation._conversation_mode._conversation;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import nie.translator.rtranslator.voice_translation.live.MeetingPreferences;
import nie.translator.rtranslator.voice_translation.live.CaptionPolicy;
import nie.translator.rtranslator.voice_translation.live.ConversationPacket;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.bluetooth.Message;
import nie.translator.rtranslator.bluetooth.Peer;
import nie.translator.rtranslator.tools.BluetoothHeadsetUtils;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.tools.gui.peers.GuiPeer;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation._conversation_mode.communication.ConversationBluetoothCommunicator;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApiText;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class ConversationService extends VoiceTranslationService {
    //properties
    public static final int SPEECH_BEAM_SIZE = 4;
    public static final int TRANSLATOR_BEAM_SIZE = 1;

    //commands
    public static final int CHANGE_LANGUAGE = 15;

    //others
    private Translator translator;
    private String myPeerName;
    private Recognizer mVoiceRecognizer;
    private RecognizerListener mVoiceRecognizerCallback;
    //private BluetoothHelper mBluetoothHelper;
    private Global global;
    private ConversationBluetoothCommunicator.Callback communicationCallback;
    private static Handler mHandler = new Handler();
    private Handler mainHandler;
    public static boolean isRunning = false;
    private final ArrayDeque<SpeechTurn> speechTurns = new ArrayDeque<>();
    private boolean recognizingTurn;
    private boolean serviceDestroyed;
    private int sessionEpoch;
    private volatile String capturedInputLanguage;
    private final ArrayDeque<Message> incomingTurns = new ArrayDeque<>();
    private boolean translatingTurn;
    private long incomingSequence;
    private SharedPreferences.OnSharedPreferenceChangeListener meetingListener;



    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        global = (Global) getApplication();
        mainHandler = new Handler(Looper.getMainLooper());
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        isAudioMute = MeetingPreferences.enabled(this) || !sharedPreferences.getBoolean("conversationAutoTTS", true);
        if (MeetingPreferences.enabled(this)) isMicMute = !MeetingPreferences.sendsMic(this);
        meetingListener = (prefs, key) -> {
            if (MeetingPreferences.ENABLED.equals(key) || MeetingPreferences.SEND_MIC.equals(key)
                    || MeetingPreferences.BOOST.equals(key)) mainHandler.post(this::applyMeetingSettings);
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(meetingListener);
        //startBluetoothSco
        //mBluetoothHelper = new BluetoothHelper(this);
        mVoiceCallback = new Recorder.Callback() {
            @Override
            public void onVoiceStart() {
                if (mVoiceRecognizer != null) {
                    super.onVoiceStart();
                    capturedInputLanguage = MeetingPreferences.input(global).getCode();
                    Log.e("recorder","onVoiceStart");
                    //we notify the client
                    ConversationService.super.notifyVoiceStart();
                }
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                if (mVoiceRecognizer != null) {
                    super.onVoice(data,size);
                    String language = capturedInputLanguage != null ? capturedInputLanguage : MeetingPreferences.input(global).getCode();
                    if (getVoiceRecorderSampleRate() != 0) mainHandler.post(() -> enqueueSpeech(data, language));
                }
            }

            @Override
            public void onVoiceEnd() {
                if (mVoiceRecognizer != null) {
                    super.onVoiceEnd();
                    // the client is notified
                    ConversationService.super.notifyVoiceEnd();
                }
            }

            @Override
            public void onVolumeLevel(float volumeLevel) {
                super.onVolumeLevel(volumeLevel);
                // we notify the client
                ConversationService.super.notifyVolumeLevel(volumeLevel);
            }
        };
        clientHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(final android.os.Message message) {
                int command = message.getData().getInt("command", -1);
                final String text = message.getData().getString("text");
                if (command != -1) {
                    if (MeetingPreferences.enabled(ConversationService.this)) {
                        if (command == START_MIC) MeetingPreferences.prefs(ConversationService.this).edit().putBoolean(MeetingPreferences.SEND_MIC, true).apply();
                        if (command == STOP_MIC && message.getData().getBoolean("permanent")) MeetingPreferences.prefs(ConversationService.this).edit().putBoolean(MeetingPreferences.SEND_MIC, false).apply();
                        if (command == START_SOUND) return true;
                    }
                    if (!ConversationService.super.executeCommand(command, message.getData())) {
                        switch (command) {
                            case START_SOUND: {
                                isAudioMute = false;
                                SharedPreferences sharedPreferences = ConversationService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("conversationAutoTTS", true);
                                editor.apply();
                                break;
                            }
                            case STOP_SOUND: {
                                isAudioMute = true;
                                SharedPreferences sharedPreferences = ConversationService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("conversationAutoTTS", false);
                                editor.apply();
                                if(ttsEngine != null) {
                                    ttsEngine.stop();
                                }
                                break;
                            }
                            case RECEIVE_TEXT:
                                CustomLocale language = MeetingPreferences.input(global);
                                if (text != null) {
                                    GuiMessage guiMessage = new GuiMessage(new Message(global, text), language, language, global.getTranslator().incrementCurrentResultID(), true, true);
                                    // send the message
                                    sendMessage(new ConversationMessage(new NeuralNetworkApiText(text, language)));

                                    notifyMessage(guiMessage);
                                    // we save every new message in the exchanged messages so that the fragment can restore them
                                    addOrUpdateMessage(guiMessage);
                                }
                                break;
                        }
                    }
                }
                return false;
            }
        });
        communicationCallback = new ConversationBluetoothCommunicator.Callback() {
            @Override
            public void onMessageReceived(final Message message) {
                super.onMessageReceived(message);
                mainHandler.post(() -> enqueueIncoming(message));
            }

            @Override
            public void onPeerUpdated(GuiPeer peer, GuiPeer newPeer) {
                super.onPeerUpdated(peer, newPeer);
                translator.updatePeer(peer, newPeer);
            }

            @Override
            public void onDisconnected(GuiPeer peer, int peersLeft) {
                super.onDisconnected(peer, peersLeft);
                translator.unloadSrcLangResourcesForPeer(peer, null);
                if (peersLeft == 0) {
                    stopSelf();
                }
            }
        };
        if(global.getBluetoothCommunicator() != null) {
            global.getBluetoothCommunicator().addCallback(communicationCallback);
        }

        // speech recognition and translation initialization
        translator = global.getTranslator();
        mVoiceRecognizer = global.getSpeechRecognizer();
        mVoiceRecognizerCallback = new VoiceTranslationServiceRecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (text != null && languageCode != null && !text.equals("") && !isMetaText(text)) {
                    CustomLocale language = CustomLocale.getInstance(languageCode);
                    GuiMessage guiMessage = new GuiMessage(new Message(global, text), language, language, global.getTranslator().incrementCurrentResultID(), true, isFinal);
                    if (isFinal) {
                        // send the message
                        sendMessage(new ConversationMessage(new NeuralNetworkApiText(text, language)));

                        notifyMessage(guiMessage);
                        // we save every new message in the exchanged messages so that the fragment can restore them
                        addOrUpdateMessage(guiMessage);
                    } else {
                        notifyMessage(guiMessage);
                    }
                }
            }

            @Override
            public void onError(int[] reasons, long value) {
                if (mVoiceRecorder != null) {
                    mVoiceRecorder.end();
                }
                ConversationService.super.notifyError(reasons, value);
            }
        };

        // New requests use request-local callbacks, preventing cross-session delivery.
        //mBluetoothHelper.start();

        translator.loadTgtLangResourcesForConversation(global.getLanguage(true), new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                //voice recorder initialization
                initializeVoiceRecorder();
            }
        });
    }


    private String meetingText(String zh, String en) {
        return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh") ? zh : en;
    }

    private void applyMeetingSettings() {
        if (serviceDestroyed || !isRunning) return;
        boolean meeting = MeetingPreferences.enabled(this);
        isAudioMute = meeting || !MeetingPreferences.prefs(this).getBoolean("conversationAutoTTS", true);
        if (meeting && ttsEngine != null) ttsEngine.stop();
        if (mVoiceRecorder != null) mVoiceRecorder.configureCaptionCapture(meeting, MeetingPreferences.prefs(this).getBoolean(MeetingPreferences.BOOST, false));
        if (meeting) {
            isMicMute = !MeetingPreferences.sendsMic(this);
            if (isMicMute) {
                endVoice(); stopVoiceRecorder(); notifyMicDeactivated();
            } else {
                MeetingPreferences.runtimeStatus = "";
                startVoiceRecorder(); notifyMicActivated();
            }
        }
    }

    @Override public void startVoiceRecorder() {
        if (MeetingPreferences.enabled(this) && !MeetingPreferences.sendsMic(this)) { isMicMute = true; return; }
        if (mVoiceRecorder != null && mVoiceRecorder.isCapturing()) return;
        super.startVoiceRecorder();
    }

    @Override public synchronized void speak(GuiMessage message, CustomLocale language) {
        // A meeting receiver must not feed its translation back into nearby microphones.
        if (!MeetingPreferences.enabled(this)) super.speak(message, language);
    }

    private static final class SpeechTurn {
        final float[] pcm; final String language;
        SpeechTurn(float[] pcm, String language) { this.pcm = pcm; this.language = language; }
    }

    private void enqueueSpeech(float[] pcm, String language) {
        if (serviceDestroyed || !isRunning || pcm.length < 1600) return;
        if (speechTurns.size() >= CaptionPolicy.MAX_PENDING_AUDIO) {
            MeetingPreferences.runtimeStatus = meetingText("语音积压过多；本段未排入，请重说。", "Speech queue full; please repeat the last segment.");
            pauseForBackpressure(); return;
        }
        speechTurns.addLast(new SpeechTurn(pcm, language));
        if (speechTurns.size() >= CaptionPolicy.MAX_PENDING_AUDIO) {
            pauseForBackpressure();
            MeetingPreferences.runtimeStatus = meetingText("处理赶不上收音，已暂停麦克风。处理结束后重新开启。", "Capture paused to catch up. Re-enable the mic after processing.");
        }
        processSpeech();
    }

    private void pauseForBackpressure() {
        isMicMute = true; stopVoiceRecorder(); notifyMicDeactivated();
        if (MeetingPreferences.enabled(this)) MeetingPreferences.prefs(this).edit().putBoolean(MeetingPreferences.SEND_MIC, false).apply();
    }

    private void processSpeech() {
        if (serviceDestroyed || !isRunning || recognizingTurn || speechTurns.isEmpty()) return;
        SpeechTurn turn = speechTurns.removeFirst(); recognizingTurn = true;
        final int epoch = sessionEpoch;
        mVoiceRecognizer.recognizeScoped(turn.pcm, MeetingPreferences.enabled(this) ? 1 : SPEECH_BEAM_SIZE,
                turn.language, null, new Recognizer.ScopedCallback() {
            @Override public void onResult(String text, String language, double score, String unused, String unusedLanguage, double unusedScore) {
                mainHandler.post(() -> {
                    if (serviceDestroyed || !isRunning || epoch != sessionEpoch) return;
                    if (CaptionPolicy.usable(text)) mVoiceRecognizerCallback.onSpeechRecognizedResult(text, language, score, true);
                    recognizingTurn = false; processSpeech();
                });
            }
            @Override public void onError(int[] reasons, long value) {
                mainHandler.post(() -> {
                    if (serviceDestroyed || !isRunning || epoch != sessionEpoch) return;
                    recognizingTurn = false;
                    MeetingPreferences.runtimeStatus = meetingText("语音识别失败，请检查本机发言语言。", "Recognition failed; check this device's spoken language.");
                    ConversationService.super.notifyError(reasons, value); processSpeech();
                });
            }
        });
    }

    private void enqueueIncoming(Message message) {
        if (serviceDestroyed || !isRunning) return;
        if (incomingTurns.size() >= 24) {
            MeetingPreferences.runtimeStatus = meetingText("接收译文积压：有消息未处理，请减慢发言。", "Receive queue full: some messages were not processed. Please slow down.");
            return;
        }
        incomingTurns.addLast(message); processIncoming();
    }

    private void processIncoming() {
        if (serviceDestroyed || !isRunning || translatingTurn || incomingTurns.isEmpty()) return;
        Message message = incomingTurns.removeFirst(); translatingTurn = true;
        final long sequence = ++incomingSequence; final int epoch = sessionEpoch;
        final CustomLocale target = global.getLanguage(false);
        ConversationPacket packet = ConversationPacket.parse(message.getText());
        if (packet == null || packet.text.trim().isEmpty()) { finishIncoming(sequence); return; }
        final String original = packet.text;
        ConversationMessage conversationMessage = new ConversationMessage(message.getSender(),
                new NeuralNetworkApiText(original, CustomLocale.getInstance(packet.language)));
        translator.translateMessage(conversationMessage, target, TRANSLATOR_BEAM_SIZE, new Translator.TranslateListener() {
            @Override public void onTranslatedText(String input, String translated, @Nullable String[] synonyms,
                    long resultID, boolean isFinal, ResultType kind, CustomLocale source, CustomLocale output) {
                mainHandler.post(() -> {
                    if (serviceDestroyed || !isRunning || epoch != sessionEpoch || sequence != incomingSequence) return;
                    message.setTextToTranslate(original); message.setText(translated);
                    GuiMessage gui = new GuiMessage(message, source, output, resultID, false, isFinal);
                    notifyMessage(gui); addOrUpdateMessage(gui);
                    if (isFinal) {
                        if (!isAudioMute && !MeetingPreferences.enabled(ConversationService.this)) {
                            global.getTTSLanguages(true, new Global.GetLocalesListListener() {
                                @Override public void onSuccess(ArrayList<CustomLocale> languages) {
                                    if (!serviceDestroyed && epoch == sessionEpoch && !isAudioMute && CustomLocale.containsLanguage(languages, output)) speak(gui, output);
                                }
                                @Override public void onFailure(int[] reasons, long value) { /* Captions do not depend on TTS. */ }
                            });
                        }
                        finishIncoming(sequence);
                    }
                });
            }
            @Override public void onFailure(int[] reasons, long value) {
                mainHandler.post(() -> {
                    if (serviceDestroyed || epoch != sessionEpoch || sequence != incomingSequence) return;
                    message.setTextToTranslate(original);
                    message.setText(meetingText("翻译失败，原文已保留。", "Translation failed; original retained."));
                    GuiMessage gui = new GuiMessage(message, CustomLocale.getInstance(packet.language), target,
                            translator.incrementCurrentResultID(), false, true);
                    notifyMessage(gui); addOrUpdateMessage(gui);
                    ConversationService.super.notifyError(reasons, value); finishIncoming(sequence);
                });
            }
        });
    }

    private void finishIncoming(long sequence) {
        mainHandler.post(() -> {
            if (serviceDestroyed || !isRunning || sequence != incomingSequence || !translatingTurn) return;
            translatingTurn = false; processIncoming();
        });
    }

    private void sendMessage(ConversationMessage conversationMessage) {
        if(global.getBluetoothCommunicator() != null) {
            String languageCode = conversationMessage.getPayload().getLanguage().getCode();
            global.getBluetoothCommunicator().sendMessage(new Message(global, conversationMessage.getPayload().getText() + languageCode + languageCode.length()));
        }
    }

    public void initializeVoiceRecorder() {
        if (mVoiceRecorder != null) return;   // already created lazily by startVoiceRecorder()
        if (Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            //voice recorder initialization
            super.mVoiceRecorder = new Recorder((Global) getApplication(), true, mVoiceCallback, new BluetoothHeadsetCallback(), global.getVad());
            mVoiceRecorder.configureCaptionCapture(MeetingPreferences.enabled(this), MeetingPreferences.prefs(this).getBoolean(MeetingPreferences.BOOST, false));
        }
    }

    public String getMyPeerName() {
        return myPeerName;
    }

    public void setMyPeerName(String myPeerName) {
        this.myPeerName = myPeerName;
    }

    @Override
    protected boolean shouldDeactivateMicDuringTTS() {
        return !isBluetoothHeadsetConnected();
    }

    @Override
    protected boolean isBluetoothHeadsetConnected() {
        if(mVoiceRecorder != null) {
            return mVoiceRecorder.isOnHeadsetSco();
        }else {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        ++sessionEpoch;
        speechTurns.clear(); incomingTurns.clear(); incomingSequence++;
        if (meetingListener != null) MeetingPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(meetingListener);
        MeetingPreferences.runtimeStatus = "";
        // Stop SpeechRecognizer
        //mVoiceRecognizer.destroy();
        mVoiceRecognizer.removeCallback(mVoiceRecognizerCallback);
        // An in-flight native call finishes silently through its expired session callback.
        mVoiceRecognizer = null;
        //stop Bluetooth helper
        //mBluetoothHelper.stop();
        if(global.getBluetoothCommunicator() != null) {
            global.getBluetoothCommunicator().removeCallback(communicationCallback);
        }
        translator.unloadAllLangResourcesForConversation(null);
        isRunning = false;
        super.onDestroy();
    }

    private class BluetoothHelper extends BluetoothHeadsetUtils {
        private BluetoothHelper(Context context) {
            super(context);
        }

        @Override
        public void onHeadsetConnected() {
        }

        @Override
        public void onScoAudioConnected() {
            Bundle bundle = new Bundle();
            bundle.putInt("callback", ON_CONNECTED_BLUETOOTH_HEADSET);
            notifyToClient(bundle);
        }

        @Override
        public void onScoAudioDisconnected() {
            Bundle bundle = new Bundle();
            bundle.putInt("callback", ON_DISCONNECTED_BLUETOOTH_HEADSET);
            notifyToClient(bundle);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stop();
                    start();
                }
            }, 1000);
        }

        @Override
        public void onHeadsetDisconnected() {
        }
    }

    public class BluetoothHeadsetCallback {

        public void onHeadsetConnected() {
        }

        public void onScoAudioConnected() {
            Bundle bundle = new Bundle();
            bundle.putInt("callback", ON_CONNECTED_BLUETOOTH_HEADSET);
            notifyToClient(bundle);
        }

        public void onScoAudioDisconnected() {
            Bundle bundle = new Bundle();
            bundle.putInt("callback", ON_DISCONNECTED_BLUETOOTH_HEADSET);
            notifyToClient(bundle);
        }

        public void onHeadsetDisconnected() {
        }
    }

    public static class ConversationServiceCommunicator extends VoiceTranslationServiceCommunicator {
        public ConversationServiceCommunicator(int id) {
            super(id);
            super.serviceHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(android.os.Message msg) {
                    msg.getData().setClassLoader(Peer.class.getClassLoader());
                    int callbackMessage = msg.getData().getInt("callback", -1);
                    Bundle data = msg.getData();
                    executeCallback(callbackMessage, data);
                    return true;
                }
            });
        }
    }
}