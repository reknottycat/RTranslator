package nie.translator.rtranslator.voice_translation.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.viewpager.widget.ViewPager;
import java.util.ArrayList;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.CustomLocale;

/** Shared language and meeting controls on both Bluetooth pairing and conversation screens. */
public final class MeetingControlsView extends LinearLayout {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Global global;
    private final SharedPreferences prefs;
    private final Button input, output;
    private final CheckBox meeting;
    private final TextView status;
    private boolean updating;
    public MeetingControlsView(Context context, AttributeSet attrs) {
        super(context,attrs);
        global=(Global)context.getApplicationContext(); prefs=MeetingPreferences.prefs(context);
        setOrientation(VERTICAL); setPadding(dp(12),0,dp(12),dp(4));
        LinearLayout languages=new LinearLayout(context); input=button(""); output=button("");
        languages.addView(input,new LayoutParams(0,-2,1)); languages.addView(output,new LayoutParams(0,-2,1)); addView(languages);
        input.setOnClickListener(v -> pickLanguage(true)); output.setOnClickListener(v -> pickLanguage(false));
        LinearLayout actions=new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        meeting=new CheckBox(context); meeting.setText(tr("会议 · 持续字幕", "Meeting · live captions")); meeting.setTextSize(13);
        actions.addView(meeting,new LayoutParams(0,-2,1));
        Button settings=button(tr("麦克风／成员", "Mic / members")); actions.addView(settings); addView(actions);
        meeting.setOnCheckedChangeListener((v,checked) -> {
            if(updating)return;
            SharedPreferences.Editor e=prefs.edit().putBoolean(MeetingPreferences.ENABLED,checked);
            if(checked)e.putBoolean(MeetingPreferences.SEND_MIC,false);
            e.apply(); refresh();
        });
        settings.setOnClickListener(v -> showSettings());
        status=new TextView(context);status.setTextSize(12); status.setTextColor(getResources().getColor(R.color.primary_very_dark,context.getTheme()));
        status.setPadding(dp(4),0,dp(4),dp(4)); addView(status); refresh();
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String tr(String zh,String en){return getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?zh:en;}
    private Button button(String title){Button b=new Button(getContext());b.setText(title);b.setTextSize(12);b.setAllCaps(false);b.setMinHeight(dp(48));return b;}
    private void refresh(){
        if(status==null)return;
        updating=true;
        input.setText(tr("本机发言：", "I speak: ")+MeetingPreferences.input(global).getDisplayNameWithoutTTS());
        output.setText(tr("接收译文：", "Translate for me: ")+global.getLanguage(true).getDisplayNameWithoutTTS());
        meeting.setChecked(MeetingPreferences.enabled(global));
        int peers=global.getBluetoothCommunicator()==null?0:global.getBluetoothCommunicator().getConnectedPeersList().size();
        String mode=meeting.isChecked()?(MeetingPreferences.sendsMic(global)?tr("本机持续收音 · 只显示字幕", "Mic on · captions only"):tr("仅接收译文 · 本机麦克风关闭", "Receive only · local mic off")):tr("普通蓝牙对话", "Bluetooth conversation");
        status.setText(mode+tr(" · 本机直连 ", " · Direct peers: ")+peers+(meeting.isChecked()&&!MeetingPreferences.runtimeStatus.isEmpty()?"\n"+MeetingPreferences.runtimeStatus:""));
        updating=false;
    }
    private final Runnable refreshLoop=new Runnable(){@Override public void run(){refresh();main.postDelayed(this,1000);}};
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();main.post(refreshLoop);}
    @Override protected void onDetachedFromWindow(){main.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}

    private void pickLanguage(boolean speaking){
        ArrayList<CustomLocale> languages=global.getLanguages(Global.RTranslatorMode.CONVERSATION_MODE,true);
        String[] names=new String[languages.size()];for(int i=0;i<names.length;i++)names[i]=languages.get(i).getDisplayNameWithoutTTS();
        new AlertDialog.Builder(getContext()).setTitle(speaking?tr("这台设备前的人说什么语言？", "What does this device's speaker speak?"):tr("这台设备的译文显示为什么语言？", "Which language should this device receive?"))
            .setItems(names,(d,index)->{if(speaking)MeetingPreferences.setInput(global,languages.get(index));else global.setLanguage(languages.get(index));refresh();})
            .setNegativeButton(android.R.string.cancel,null).show();
    }
    private void showSettings(){
        LinearLayout content=new LinearLayout(getContext());content.setOrientation(VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        CheckBox send=new CheckBox(getContext());send.setText(tr("开启本机麦克风并发送发言", "Capture and send this device's speech"));
        send.setChecked(MeetingPreferences.sendsMic(global));send.setEnabled(MeetingPreferences.enabled(global));content.addView(send);
        CheckBox gain=new CheckBox(getContext());gain.setText(tr("拾音增强（软件增益，最多 4 倍）", "Input boost (software, up to 4×)"));
        gain.setChecked(prefs.getBoolean(MeetingPreferences.BOOST,false));content.addView(gain);
        TextView help=new TextView(getContext());help.setTextSize(13);
        help.setText(tr("会议模式不自动播报译文，避免扬声器被再次识别。\n\n同一房间尽量只开启发言者身边设备的麦克风；重复收音不会自动去重，也不能分离同时说话的人。\n\n3–4 台设备为实验性支持：目前只向直连设备发消息，不做中继。要让所有人互相收到，每两台都需要连接。四台时每台应直连另外三台。实际连接数受设备蓝牙限制。\n\n增益也会放大噪声，不等于远场降噪。", "Meeting mode disables speech playback to avoid acoustic feedback.\n\nIn the same room, enable the mic near the active speaker. Duplicate capture is not deduplicated; overlapping speakers are not separated.\n\n3–4 devices are experimental. Messages go to directly connected peers only; there is no relay. For everyone to receive everyone, connect every pair. With four devices, each should have three direct peers. Actual capacity depends on Bluetooth hardware.\n\nGain can also amplify noise; it is not far-field denoising."));
        content.addView(help);
        android.widget.ScrollView scroll=new android.widget.ScrollView(getContext());scroll.addView(content);
        new AlertDialog.Builder(getContext()).setTitle(tr("会议设置与连接", "Meeting settings & connections")).setView(scroll)
            .setPositiveButton(tr("应用", "Apply"),(d,w)->{
                prefs.edit().putBoolean(MeetingPreferences.SEND_MIC,send.isChecked()).putBoolean(MeetingPreferences.BOOST,gain.isChecked()).apply();refresh();
            }).setNeutralButton(tr("成员／添加设备", "Members / add device"),(d,w)->{
                View pager=getRootView().findViewById(R.id.tabs);
                if(pager instanceof ViewPager)((ViewPager)pager).setCurrentItem(1,true);
                if(global.getBluetoothCommunicator()!=null)global.getBluetoothCommunicator().startSearch();
                Toast.makeText(getContext(),tr("在设备列表逐一连接；对方需要确认。", "Connect each peer in the device list; the peer must accept."),Toast.LENGTH_LONG).show();
            }).setNegativeButton(android.R.string.cancel,null).show();
    }
}
