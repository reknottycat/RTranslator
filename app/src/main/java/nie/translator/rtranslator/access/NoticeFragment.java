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

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;

public class NoticeFragment extends Fragment {
    private TextView noticeDescription;
    private Button buttonConfirm;
    private AccessActivity activity;
    private TextView ramErrorText;
    private Global global;


    public NoticeFragment() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_notice, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        noticeDescription = view.findViewById(R.id.noticeDescription);
        buttonConfirm = view.findViewById(R.id.buttonConfirm);
        ramErrorText = view.findViewById(R.id.ramErrorText);
        noticeDescription.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();
        if(global.getTotalRamSize() <= 5000) {
            ramErrorText.setVisibility(View.VISIBLE);
        }
        buttonConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.startFragment(AccessActivity.USER_DATA_FRAGMENT,null);
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if(getContext() != null && !NotificationManagerCompat.from(getContext()).areNotificationsEnabled()){
            //Toast.makeText(getContext(), "Notification permission granted", Toast.LENGTH_SHORT).show();
        }
        //we create the readme file in the externalFilesDir
        if(getContext() != null && getContext().getExternalFilesDir(null) != null) {
            File readmeFile = new File(getContext().getExternalFilesDir(null), "Readme.txt");
            if(!readmeFile.exists()){
                try (FileWriter writer = new FileWriter(readmeFile)) {
                    writer.append("RTranslator 3.0.0-beta2 model downloads / 模型下载\n\n")
                            .append("Official downloads automatically fall back to the configured GitHub proxies.\n")
                            .append("官方下载失败时会自动尝试备用代理。\n\n")
                            .append("For offline import, put the ORIGINAL release files directly in this folder, then tap download or retry in the app.\n")
                            .append("离线导入：将官方发布的原始文件直接放在本目录，再在应用内点击下载或重试。\n")
                            .append("Whisper: keep the original .onnx filenames. Translation models: keep HY-MT.zip, Madlad.zip or Mozilla_<language>.zip zipped and unchanged.\n")
                            .append("Whisper 保留原 .onnx 文件名；翻译模型使用原始 HY-MT.zip、Madlad.zip 或 Mozilla_<语言>.zip，不要手动解压或改名。\n")
                            .append("TranslationDictionaries.zip and Tatoeba.zip are also supported. The app checks files before use and preserves the imported originals.\n")
                            .append("也支持 TranslationDictionaries.zip 和 Tatoeba.zip。应用会先检查文件，导入成功后保留本目录中的原文件。\n")
                            .append("The 2.0 NLLB model set does not replace the 3.0 translation models.\n")
                            .append("2.0 的 NLLB 模型不能替代 3.0 的翻译模型。\n");
                    android.util.Log.i("files", "Readme created");
                } catch (IOException e) {
                    android.util.Log.w("files", "Could not create model download instructions", e);
                }
            }
        }
    }
}
