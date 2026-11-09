package nie.translator.rtranslator.voice_translation.live;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApiResult;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;

/** One microphone, two selected languages. Capture never waits for translation or TTS. */
public final class SingleDeviceConversationActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Turn> pending = new ArrayDeque<>();
    private final ArrayList<Caption> captions = new ArrayList<>();
    private Global global;
    private Translator translator;
    private Recognizer recognizer;
    private Recorder recorder;
    private CustomLocale first, second;
    private ArrayList<CustomLocale> languages;
    private Button firstButton, secondButton, autoButton, forceFirst, forceSecond, listenButton;
    private TextView status;
    private ProgressBar level;
    private RecyclerView transcript;
    private CaptionAdapter adapter;
    private CheckBox boost;
    private boolean ready, listening, busy, destroyed, visible, permissionStart;
    private volatile int mode = CaptionPolicy.AUTO;
    private volatile int utteranceMode = CaptionPolicy.AUTO;
    private int epoch;
    private String phase = "";
    private long lastMeterUpdate;

    private String tr(String zh, String en) {
        return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh") ? zh : en;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size);
        v.setTextColor(getColor(R.color.primary_very_dark)); return v;
    }
    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setTextSize(14); return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); return r;
    }
    private void equal(LinearLayout r, View child) {
        r.addView(child, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        global = (Global) getApplication(); translator = global.getTranslator(); recognizer = global.getSpeechRecognizer();
        buildUi();
        if (translator == null || recognizer == null || global.getVad() == null) {
            phase = tr("模型未就绪，请返回主页完成模型下载。", "Models are not ready. Complete setup from the home screen.");
            renderState(); return;
        }
        languages = global.getLanguages(Global.RTranslatorMode.WALKIE_TALKIE_MODE, true);
        if (languages.size() < 2) {
            phase = tr("请先安装至少两种语言的语音翻译资源。", "Install voice translation resources for at least two languages.");
            renderState(); return;
        }
        first = savedLanguage("solo.first", global.getFirstTextLanguage(true));
        second = savedLanguage("solo.second", global.getSecondTextLanguage(true));
        if (first.equalsLanguage(second)) for (CustomLocale language : languages)
            if (!language.equalsLanguage(first)) { second = language; break; }
        updateLanguageLabels(); loadPair();
    }

    private CustomLocale savedLanguage(String key, CustomLocale fallback) {
        String code = MeetingPreferences.prefs(this).getString(key, fallback == null ? "" : fallback.getCode());
        for (CustomLocale language : languages) if (language.getCode().equals(code)) return language;
        return languages.get(0);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.primary_very_lite)); root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setFitsSystemWindows(true); setContentView(root);
        LinearLayout heading = row();
        Button back = button("‹"); back.setContentDescription(tr("返回", "Back")); back.setOnClickListener(v -> finish());
        heading.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title = text(tr("单机对话", "On-device conversation"),21); title.setTypeface(null,Typeface.BOLD);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button copy = button(tr("复制", "Copy")); copy.setOnClickListener(v -> copyTranscript()); heading.addView(copy);
        root.addView(heading);
        TextView hint = text(tr("无需蓝牙 · 原文在上，译文在下 · 轮流发言效果更好", "No Bluetooth · Original above translation · Take turns for best accuracy"),12);
        hint.setPadding(dp(4),0,dp(4),dp(4)); root.addView(hint);
        LinearLayout pair = row(); firstButton=button("A"); secondButton=button("B");
        equal(pair,firstButton); equal(pair,secondButton); root.addView(pair);
        firstButton.setOnClickListener(v -> selectLanguage(true)); secondButton.setOnClickListener(v -> selectLanguage(false));
        LinearLayout selection = row(); autoButton=button(tr("自动 A ↔ B", "Auto A ↔ B"));
        forceFirst=button("A"); forceSecond=button("B"); equal(selection,autoButton); equal(selection,forceFirst); equal(selection,forceSecond);
        root.addView(selection);
        autoButton.setOnClickListener(v -> setMode(CaptionPolicy.AUTO));
        forceFirst.setOnClickListener(v -> setMode(CaptionPolicy.FIRST)); forceSecond.setOnClickListener(v -> setMode(CaptionPolicy.SECOND));
        status=text("",13); status.setPadding(dp(4),dp(4),dp(4),dp(4)); status.setMinHeight(dp(36));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        level=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); level.setMax(100);
        level.setContentDescription(tr("麦克风音量", "Microphone level")); root.addView(level,new LinearLayout.LayoutParams(-1,dp(4)));
        transcript=new RecyclerView(this); transcript.setLayoutManager(new LinearLayoutManager(this));
        transcript.setBackgroundColor(getColor(R.color.white)); adapter=new CaptionAdapter(); transcript.setAdapter(adapter);
        root.addView(transcript,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout options=row(); boost=new CheckBox(this); boost.setText(tr("拾音增强（最多 4 倍）", "Input boost (up to 4×)")); boost.setTextSize(12);
        boost.setChecked(MeetingPreferences.prefs(this).getBoolean("solo.boost",false)); equal(options,boost);
        boost.setOnCheckedChangeListener((b,checked) -> {
            MeetingPreferences.prefs(this).edit().putBoolean("solo.boost",checked).apply();
            if(recorder!=null) recorder.configureCaptionCapture(true,checked);
            if(checked) toast(tr("也可能放大噪声；不代表能恢复听不清的声音。", "Boost can amplify noise; it cannot recover inaudible speech."));
        });
        Button clear=button(tr("清空", "Clear")); clear.setOnClickListener(v -> {
            if(listening || busy || !pending.isEmpty()) { toast(tr("请先暂停并等待处理结束。", "Pause and wait for processing to finish.")); return; }
            new AlertDialog.Builder(this).setMessage(tr("清空本次字幕？", "Clear this transcript?"))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w) -> {captions.clear(); adapter.notifyDataSetChanged();}).show();
        }); options.addView(clear); root.addView(options);
        listenButton=button(tr("开始持续收听", "Start listening")); root.addView(listenButton,new LinearLayout.LayoutParams(-1,-2));
        listenButton.setOnClickListener(v -> { if(listening) pause(true); else startListening(); });
        setMode(CaptionPolicy.AUTO); renderState();
    }

    private void updateLanguageLabels() {
        firstButton.setText("A · " + first.getDisplayNameWithoutTTS()); secondButton.setText("B · " + second.getDisplayNameWithoutTTS());
        forceFirst.setText(tr("只听 ", "Only ")+first.getDisplayNameWithoutTTS()); forceSecond.setText(tr("只听 ", "Only ")+second.getDisplayNameWithoutTTS());
    }
    private void setMode(int value) {
        mode=value;
        autoButton.setEnabled(value!=CaptionPolicy.AUTO); forceFirst.setEnabled(value!=CaptionPolicy.FIRST); forceSecond.setEnabled(value!=CaptionPolicy.SECOND);
        if(listening) toast(tr("下一段语音使用新模式。", "The next speech segment will use this mode."));
    }
    private void selectLanguage(boolean isFirst) {
        if(languages==null || languages.isEmpty()) return;
        if(listening || busy || !pending.isEmpty() || !ready) { toast(tr("请先暂停，等当前处理结束后再换语言。", "Pause and finish processing before changing languages.")); return; }
        String[] names=new String[languages.size()]; for(int i=0;i<names.length;i++) names[i]=languages.get(i).getDisplayNameWithoutTTS();
        new AlertDialog.Builder(this).setTitle(isFirst?tr("语言 A", "Language A"):tr("语言 B", "Language B"))
            .setItems(names,(d,index) -> {
                CustomLocale selected=languages.get(index);
                if(selected.equalsLanguage(isFirst?second:first)) { toast(tr("请选择两种不同语言。", "Select two different languages.")); return; }
                if(isFirst) first=selected; else second=selected;
                MeetingPreferences.prefs(this).edit().putString("solo.first",first.getCode()).putString("solo.second",second.getCode()).apply();
                updateLanguageLabels(); loadPair();
            }).show();
    }
    private void loadPair() {
        ready=false; phase=tr("正在加载所选语言…", "Loading selected languages…"); renderState(); final int ticket=++epoch;
        translator.loadLanguageResources(first,second,Global.RTranslatorMode.WALKIE_TALKIE_MODE,new Translator.GeneralListener() {
            @Override public void onSuccess() { main.post(() -> { if(!active(ticket)) return; ready=true; phase=""; renderState(); }); }
            @Override public void onFailure(int[] reasons,long value) { main.post(() -> { if(!active(ticket))return; phase=tr("语言模型加载失败，请返回模型管理检查。", "Language models failed to load. Check model manager."); renderState(); }); }
        });
    }
    private boolean active(int ticket) { return !destroyed && ticket==epoch; }
    private void startListening() {
        if(!ready || !visible) return;
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            permissionStart=true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},410); return;
        }
        if(pending.size()>=CaptionPolicy.MAX_PENDING_AUDIO) { toast(tr("仍在处理积压语音，请稍后再开始。", "Processing queued speech. Start after it catches up.")); return; }
        try {
            if(recorder==null) recorder=new Recorder(global,false,new Recorder.SimpleCallback() {
                @Override public void onVoiceStart() { utteranceMode=mode; }
                @Override public void onVoice(@NonNull float[] pcm,int size) {
                    final int snapshot=utteranceMode;
                    main.post(() -> enqueue(pcm,snapshot));
                }
                @Override public void onVolumeLevel(float value) {
                    long now=android.os.SystemClock.elapsedRealtime();
                    if(now-lastMeterUpdate<150) return; lastMeterUpdate=now;
                    main.post(() -> {if(!destroyed) level.setProgress(listening?Math.max(0,Math.min(100,(int)(value*100))):0);});
                }
            },null,global.getVad());
            recorder.configureCaptionCapture(true,boost.isChecked());
            listening=true; phase=""; recorder.start(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); renderState();
            main.postDelayed(captureHealth,1500);
        } catch(RuntimeException error) {
            listening=false; phase=tr("麦克风启动失败，请检查权限或其他录音应用。", "Microphone could not start. Check permission and other audio apps."); renderState();
        }
    }
    private final Runnable captureHealth=new Runnable() {
        @Override public void run() {
            if(destroyed || !listening) return;
            if(recorder==null || !recorder.isCapturing()) { listening=false; phase=tr("收音已中断，请检查权限或音频设备后重试。", "Capture interrupted. Check permissions or audio devices and retry."); renderState(); return; }
            main.postDelayed(this,1500);
        }
    };
    private void pause(boolean flush) {
        listening=false; main.removeCallbacks(captureHealth); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(recorder!=null) { if(flush) recorder.end(); recorder.stop(); }
        level.setProgress(0); renderState();
    }
    private void enqueue(float[] pcm,int capturedMode) {
        if(destroyed || !visible || pcm.length<1600) return;
        if(pending.size()>=CaptionPolicy.MAX_PENDING_AUDIO) {
            pause(false); phase=tr("处理赶不上收音：已暂停；本段未排入队列，请重说。", "Processing is behind: capture paused; please repeat the last segment."); renderState(); return;
        }
        pending.addLast(new Turn(pcm,capturedMode,first,second,epoch));
        if(pending.size()>=CaptionPolicy.MAX_PENDING_AUDIO) {
            pause(false); phase=tr("积压已达上限，自动暂停收音；处理完后点开始。", "Queue full: capture paused. Start again after processing.");
        }
        processNext(); renderState();
    }
    private void processNext() {
        if(destroyed || busy || pending.isEmpty()) return;
        Turn turn=pending.removeFirst(); busy=true; renderState();
        String a=(turn.mode==CaptionPolicy.SECOND?turn.second:turn.first).getCode();
        String b=turn.mode==CaptionPolicy.AUTO?turn.second.getCode():null;
        recognizer.recognizeScoped(turn.pcm,1,a,b,new Recognizer.ScopedCallback() {
            @Override public void onResult(String ta,String la,double sa,String tb,String lb,double sb) {
                main.post(() -> {
                    if(!active(turn.epoch)) return;
                    if(turn.mode!=CaptionPolicy.AUTO) { translate(turn,ta,turn.mode); return; }
                    if(!CaptionPolicy.usable(ta) || !CaptionPolicy.usable(tb)) {
                        int choice=CaptionPolicy.choose(ta,sa,tb,sb,false,false); translate(turn,choice==CaptionPolicy.FIRST?ta:tb,choice); return;
                    }
                    NeuralNetworkApiResult ra=new NeuralNetworkApiResult(ta,sa,true), rb=new NeuralNetworkApiResult(tb,sb,true);
                    translator.detectLanguage(ra,rb,false,new CustomLocale[]{turn.first,turn.second},new Translator.DetectMultiLanguageListener() {
                        @Override public void onDetectedText(NeuralNetworkApiResult x,NeuralNetworkApiResult y,int result) {
                            main.post(() -> {
                                if(!active(turn.epoch)) return;
                                boolean ma=x.getLanguage()!=null && x.getLanguage().equalsLanguage(turn.first);
                                boolean mb=y.getLanguage()!=null && y.getLanguage().equalsLanguage(turn.second);
                                int selected=CaptionPolicy.choose(ta,sa,tb,sb,ma,mb); translate(turn,selected==CaptionPolicy.FIRST?ta:tb,selected);
                            });
                        }
                        @Override public void onFailure(int[] reasons,long value) { main.post(() -> {
                            if(!active(turn.epoch))return; int selected=CaptionPolicy.choose(ta,sa,tb,sb,false,false); translate(turn,selected==CaptionPolicy.FIRST?ta:tb,selected);
                        }); }
                    });
                });
            }
            @Override public void onError(int[] reasons,long value) { main.post(() -> {
                if(!active(turn.epoch))return; phase=tr("语音识别失败；可手动指定语言再试。", "Recognition failed. Try selecting the spoken language."); complete(turn);
            }); }
        });
    }
    private void translate(Turn turn,String original,int choice) {
        if(!active(turn.epoch)) return;
        if(choice<0 || !CaptionPolicy.usable(original)) { complete(turn); return; }
        CustomLocale source=choice==CaptionPolicy.FIRST?turn.first:turn.second;
        CustomLocale target=choice==CaptionPolicy.FIRST?turn.second:turn.first;
        Caption caption=new Caption(original,source.getDisplayNameWithoutTTS(),target.getDisplayNameWithoutTTS());
        caption.translation=tr("正在翻译…", "Translating…"); captions.add(caption);
        if(captions.size()>CaptionPolicy.MAX_HISTORY) captions.remove(0);
        adapter.notifyDataSetChanged(); transcript.scrollToPosition(captions.size()-1);
        translator.translate(original,source,target,1,false,Global.RTranslatorMode.WALKIE_TALKIE_MODE,new Translator.TranslateListener() {
            @Override public void onTranslatedText(String input,String output,String[] synonyms,long id,boolean isFinal,ResultType kind,CustomLocale src,CustomLocale tgt) {
                main.post(() -> {
                    if(!active(turn.epoch) || turn.complete) return;
                    caption.translation=output; int index=captions.indexOf(caption); if(index>=0) adapter.notifyItemChanged(index);
                    if(isFinal) complete(turn);
                });
            }
            @Override public void onFailure(int[] reasons,long value) { main.post(() -> {
                if(!active(turn.epoch) || turn.complete)return;
                caption.translation=tr("翻译失败，原文已保留。", "Translation failed; original retained.");
                int index=captions.indexOf(caption); if(index>=0)adapter.notifyItemChanged(index); complete(turn);
            }); }
        });
    }
    private void complete(Turn turn) {
        if(turn.complete || !active(turn.epoch))return;
        turn.complete=true; busy=false; renderState(); processNext();
    }
    private void renderState() {
        if(status==null)return;
        String current=listening?tr("● 持续收听", "● Listening"):tr("○ 已暂停", "○ Paused");
        if(busy) current+=tr(" · 正在识别／翻译", " · Recognizing / translating");
        if(!pending.isEmpty()) current+=tr(" · 等待 ", " · Queued ")+pending.size();
        status.setText(phase.isEmpty()?current:phase+"\n"+current);
        listenButton.setText(listening?tr("暂停收听", "Pause listening"):tr("开始持续收听", "Start listening"));
        listenButton.setEnabled(ready); firstButton.setEnabled(ready&&!listening&&!busy&&pending.isEmpty());
        secondButton.setEnabled(firstButton.isEnabled());
    }
    private void copyTranscript() {
        StringBuilder b=new StringBuilder();
        for(Caption c:captions) b.append(c.time).append(" · ").append(c.source).append(" → ").append(c.target)
            .append('\n').append(c.original).append('\n').append(c.translation).append("\n\n");
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("RTranslator",b.toString()));
        toast(tr("已复制当前字幕（最多 200 段）。", "Current transcript copied (up to 200 segments)."));
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override protected void onStart() { super.onStart(); visible=true; }
    @Override protected void onStop() { visible=false; permissionStart=false; pause(false); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed=true; ++epoch; pending.clear(); main.removeCallbacksAndMessages(null);
        if(recorder!=null)recorder.destroy(); super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code,@NonNull String[] permissions,@NonNull int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==410 && permissionStart) { permissionStart=false;
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)startListening();
            else toast(tr("未获得麦克风权限，未开始录音。", "Microphone permission denied. Recording has not started."));
        }
    }
    private static final class Turn {
        final float[] pcm; final int mode,epoch; final CustomLocale first,second; boolean complete;
        Turn(float[] pcm,int mode,CustomLocale first,CustomLocale second,int epoch){this.pcm=pcm;this.mode=mode;this.first=first;this.second=second;this.epoch=epoch;}
    }
    private static final class Caption {
        final String original,source,target,time; String translation;
        Caption(String text,String source,String target){this.original=text;this.source=source;this.target=target;this.time=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());}
    }
    private final class CaptionAdapter extends RecyclerView.Adapter<CaptionHolder> {
        @NonNull @Override public CaptionHolder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
            LinearLayout card=new LinearLayout(SingleDeviceConversationActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14),dp(12),dp(14),dp(12));
            RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,-2); p.setMargins(dp(4),dp(4),dp(4),dp(4)); card.setLayoutParams(p);
            GradientDrawable bg=new GradientDrawable();bg.setColor(getColor(R.color.primary_very_lite));bg.setCornerRadius(dp(16));card.setBackground(bg);
            TextView meta=text("",12),original=text("",19),translation=text("",21); original.setTextIsSelectable(true);translation.setTextIsSelectable(true);
            translation.setTypeface(null,Typeface.BOLD); translation.setPadding(0,dp(8),0,0);card.addView(meta);card.addView(original);card.addView(translation);
            return new CaptionHolder(card,meta,original,translation);
        }
        @Override public void onBindViewHolder(@NonNull CaptionHolder h,int i) {
            Caption c=captions.get(i);h.meta.setText(c.time+" · "+c.source+" → "+c.target);h.original.setText(c.original);h.translation.setText(c.translation);
        }
        @Override public int getItemCount(){return captions.size();}
    }
    private static final class CaptionHolder extends RecyclerView.ViewHolder {
        final TextView meta,original,translation;
        CaptionHolder(View v,TextView m,TextView o,TextView t){super(v);meta=m;original=o;translation=t;}
    }
}
