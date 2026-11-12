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

package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

public class TTS {
    //object
    private volatile TextToSpeech tts;
    private volatile boolean initialized;
    private volatile boolean languageReady;

    //Attributes used for getting the supported languages
    private static Thread getSupportedLanguageThread;
    private static ArrayDeque<SupportedLanguagesListener> supportedLanguagesListeners = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();


    // Independent installed engines may cover different languages. No system setting is changed.
    private final java.util.List<TextToSpeech> allEngines = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<TextToSpeech> readyEngines = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final android.os.Handler initHandler = new android.os.Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean initReported = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private volatile UtteranceProgressListener progressListener;

    public TTS(Context context, final InitListener listener) {
        final Context app = context.getApplicationContext();
        final Runnable finish = () -> {
            if (closed || !initReported.compareAndSet(false, true)) return;
            initialized = !readyEngines.isEmpty();
            if (initialized) {
                tts = readyEngines.get(0);
                listener.onInit();
            } else {
                shutdown();
                listener.onError(ErrorCodes.GOOGLE_TTS_ERROR);
            }
        };
        // Android service binding is asynchronous. Delay callbacks to the main loop so
        // constructor assignments are complete even for a very fast local engine.
        tts = new TextToSpeech(app, status -> initHandler.post(() -> {
            if (closed) return;
            TextToSpeech primary = tts;
            if (status == TextToSpeech.SUCCESS && primary != null) readyEngines.add(primary);
            java.util.List<TextToSpeech.EngineInfo> installed = primary == null
                    ? java.util.Collections.emptyList() : primary.getEngines();
            String defaultPackage = primary == null ? null : primary.getDefaultEngine();
            java.util.List<String> alternatives = new java.util.ArrayList<>();
            if (installed != null) for (TextToSpeech.EngineInfo info : installed) {
                if (info.name != null && !info.name.equals(defaultPackage)) alternatives.add(info.name);
            }
            java.util.Collections.sort(alternatives);
            if (alternatives.isEmpty()) { finish.run(); return; }
            java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(alternatives.size());
            for (String packageName : alternatives) {
                final TextToSpeech[] holder = new TextToSpeech[1];
                try {
                    holder[0] = new TextToSpeech(app, result -> initHandler.post(() -> {
                        if (closed) return;
                        TextToSpeech engine = holder[0];
                        if (result == TextToSpeech.SUCCESS && engine != null) {
                            if (progressListener != null) engine.setOnUtteranceProgressListener(progressListener);
                            readyEngines.add(engine);
                        }
                        if (pending.decrementAndGet() == 0) finish.run();
                    }), packageName);
                    allEngines.add(holder[0]);
                } catch (RuntimeException failure) {
                    if (pending.decrementAndGet() == 0) finish.run();
                }
            }
        }), null);
        allEngines.add(tts);
        // A broken installed service must not prevent another offline engine from working.
        initHandler.postDelayed(finish, 8000);
    }

    public boolean isActive() {
        return initialized && tts != null;
    }

    public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
        if (isActive() && languageReady && isOfflineInstalled(tts.getVoice())) {
            return tts.speak(text, queueMode, params, utteranceId);
        }
        return TextToSpeech.ERROR;
    }

    @Nullable
    public Voice getVoice() {
        if (isActive()) {
            return tts.getVoice();
        }
        return null;
    }

    @Nullable
    public Set<Voice> getVoices() {
        if (!isActive()) return null;
        Set<Voice> voices = new java.util.HashSet<>();
        for (TextToSpeech engine : readyEngines) {
            Set<Voice> available = engine.getVoices();
            if (available != null) voices.addAll(available);
        }
        return voices;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        progressListener = listener;
        int result = TextToSpeech.ERROR;
        for (TextToSpeech engine : readyEngines) {
            if (engine.setOnUtteranceProgressListener(listener) == TextToSpeech.SUCCESS) result = TextToSpeech.SUCCESS;
        }
        return result;
    }

    public static boolean isOfflineInstalled(Voice voice) {
        return voice != null && !voice.isNetworkConnectionRequired()
                && (voice.getFeatures() == null || !voice.getFeatures().contains(
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
    }

    public int setLanguage(CustomLocale loc, Context context) {
        languageReady = false; // Never speak the previous language after a failed switch.
        if (!isActive() || loc == null) return TextToSpeech.ERROR;
        // Preserve the default engine if it has a suitable offline voice, then
        // try other installed engines (e.g. Kokoro zh/en plus Piper Russian).
        for (TextToSpeech engine : readyEngines) {
            Set<Voice> available = engine.getVoices();
            if (available == null) continue;
            java.util.List<Voice> candidates = new java.util.ArrayList<>();
            for (Voice voice : available) {
                if (isOfflineInstalled(voice) && OfflineVoicePolicy.score(loc.getLocale(),
                        voice.getLocale(), false, false, voice.getQuality()) >= 0) candidates.add(voice);
            }
            candidates.sort((left, right) -> {
                int byScore = Integer.compare(
                    OfflineVoicePolicy.score(loc.getLocale(), right.getLocale(), false, false, right.getQuality()),
                    OfflineVoicePolicy.score(loc.getLocale(), left.getLocale(), false, false, left.getQuality()));
                return byScore != 0 ? byScore : left.getName().compareTo(right.getName());
            });
            for (Voice voice : candidates) {
                if (engine.setVoice(voice) == TextToSpeech.SUCCESS) {
                    if (tts != engine && tts != null) tts.stop();
                    tts = engine;
                    languageReady = true;
                    return TextToSpeech.LANG_AVAILABLE;
                }
            }
        }
        // Do not fall back to a network voice or silently read Russian with another locale.
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context.getApplicationContext(),
                        context.getString(nie.translator.rtranslator.R.string.offline_tts_missing,
                                loc.getLocale().getDisplayLanguage()), android.widget.Toast.LENGTH_LONG).show());
        return TextToSpeech.LANG_MISSING_DATA;
    }

    public int stop() {
        if (isActive()) {
            return tts.stop();
        }
        return TextToSpeech.ERROR;
    }

    public void shutdown() {
        closed = true;
        initHandler.removeCallbacksAndMessages(null);
        initialized = false;
        languageReady = false;
        for (TextToSpeech engine : allEngines) engine.shutdown();
        readyEngines.clear();
        allEngines.clear();
    }

    public static void getSupportedLanguages(Context context, SupportedLanguagesListener responseListener){
        synchronized (lock) {
            if (responseListener != null) {
                supportedLanguagesListeners.addLast(responseListener);
            }
            if (getSupportedLanguageThread == null) {
                getSupportedLanguageThread = new Thread(new GetSupportedLanguageRunnable(context, new SupportedLanguagesListener() {
                    @Override
                    public void onLanguagesListAvailable(ArrayList<CustomLocale> languages) {
                        notifyGetSupportedLanguagesSuccess(languages);
                    }
                    @Override
                    public void onError(int reason) {
                        notifyGetSupportedLanguagesFailure(reason);
                    }
                }), "getSupportedLanguagePerformer");
                getSupportedLanguageThread.start();
            }
        }
    }

    private static void notifyGetSupportedLanguagesSuccess(ArrayList<CustomLocale> languages) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onLanguagesListAvailable(languages);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static void notifyGetSupportedLanguagesFailure(final int reasons) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onError(reasons);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static class GetSupportedLanguageRunnable implements Runnable {
        private SupportedLanguagesListener responseListener;
        private Context context;
        private static TTS tempTts;
        private static android.os.Handler mainHandler;   // handler that can be used to post to the main thread



        private GetSupportedLanguageRunnable(Context context, final SupportedLanguagesListener responseListener) {
            this.responseListener = responseListener;
            this.context = context;
            mainHandler = new android.os.Handler(Looper.getMainLooper());
        }

        @Override
        public void run() {
            tempTts = new TTS((context), new TTS.InitListener() {    // tts initialization (to be improved, automatic package installation)
                @Override
                public void onInit() {
                    Set<Voice> set = tempTts.getVoices();
                    SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                    boolean qualityLow = sharedPreferences.getBoolean("languagesQualityLow", false);
                    int quality;
                    if (qualityLow) {
                        quality = Voice.QUALITY_VERY_LOW;
                    } else {
                        quality = Voice.QUALITY_NORMAL;
                    }
                    ttsLanguages.clear();
                    boolean foundLanguage = false; // if there is available languages
                    if (set != null) {
                        // we filter the languages that have a tts that reflects the quality characteristics we want
                        for (Voice aSet : set) {
                            if (isOfflineInstalled(aSet) && aSet.getQuality() >= quality) {
                                CustomLocale language;
                                if(aSet.getLocale() != null){
                                    language = new CustomLocale(aSet.getLocale()); // Use .getLocale() for google
                                    foundLanguage = true;
                                }else{
                                    language = CustomLocale.getInstance(aSet.getName()); // Use .getName() for samsung/huawei (maybe others also)
                                    foundLanguage = true;
                                }

                                if (!CustomLocale.containsLanguage(ttsLanguages, language)) ttsLanguages.add(language);
                            }
                        }
                    }
                    if (foundLanguage) {    // start TTS if the above lines find at least 1 supported language
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                responseListener.onLanguagesListAvailable(ttsLanguages);
                            }
                        });
                    } else {
                        onError(ErrorCodes.GOOGLE_TTS_ERROR);
                    }
                    tempTts.stop();
                    tempTts.shutdown();
                }

                @Override
                public void onError(final int reason) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            responseListener.onError(reason);
                        }
                    });
                }
            });
        }
    }

    public interface InitListener {
        void onInit();

        void onError(int reason);
    }

    public interface SupportedLanguagesListener {
        void onLanguagesListAvailable(ArrayList<CustomLocale> languages);
        void onError(int reason);
    }
}
