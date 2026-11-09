package nie.translator.rtranslator.downloader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** SAF import of allowlisted original assets, verified before publication. No broad storage permission. */
public final class ModelFileImporter {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "verified-model-import"));
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final WeakReference<Fragment> owner;
    private final ActivityResultLauncher<String[]> picker;
    private Runnable imported;
    public ModelFileImporter(Fragment fragment) {
        owner = new WeakReference<>(fragment);
        picker = fragment.registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::receive);
    }
    public void launch(Runnable onImported) {
        Fragment fragment = owner.get();
        if(fragment==null || !fragment.isAdded()) return;
        if(BUSY.get()){Toast.makeText(fragment.requireContext(), "Import is running / 正在导入",Toast.LENGTH_LONG).show();return;}
        imported=onImported;
        picker.launch(new String[]{"*/*"});
    }
    private void receive(List<Uri> uris) {
        Fragment fragment=owner.get();
        if(uris==null || uris.isEmpty() || fragment==null || !fragment.isAdded())return;
        if(!BUSY.compareAndSet(false,true))return;
        Context context=fragment.requireContext().getApplicationContext();
        boolean zh=context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh");
        WORKER.execute(() -> {
            int success=0; String lastError="";
            try {
                if(uris.size()>ModelDownloadManifest.entries().size())throw new IOException("Too many files");
                for(Uri uri:uris) {
                    try { copy(context,uri);success++; }
                    catch(Exception error){lastError=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();ModelDownloadLog.record("导入失败 / Import failed: "+lastError,"local-file");}
                }
            } catch(Exception e) { lastError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
            finally { BUSY.set(false); }
            final int count=success; final String error=lastError;
            new Handler(Looper.getMainLooper()).post(() -> {
                Fragment current=owner.get();
                if(current==null || !current.isAdded() || current.getView()==null)return;
                String result=zh?"已导入 "+count+" 个文件。点击对应模型的下载/重试以复用。":"Imported "+count+" files. Tap the model's download/retry to reuse.";
                if(!error.isEmpty())result+="\n"+error;
                Toast.makeText(current.requireContext(),result,Toast.LENGTH_LONG).show();
                if(count>0 && imported!=null)imported.run();
            });
        });
    }
    private void copy(Context context,Uri uri) throws Exception {
        ContentResolver resolver=context.getContentResolver();String name=null;
        try(Cursor cursor=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst())name=cursor.getString(0);
        }
        if(name==null || !name.matches("[A-Za-z0-9_.-]+"))throw new IOException("Invalid filename / 文件名无效");
        ModelDownloadManifest.Entry metadata=null;
        for(Map.Entry<String,ModelDownloadManifest.Entry> entry:ModelDownloadManifest.entries().entrySet())
            if(entry.getKey().endsWith("/"+name)){metadata=entry.getValue();break;}
        if(metadata==null)throw new IOException("Not an original RTranslator asset / 不是官方模型文件: "+name);
        File directory=context.getExternalFilesDir(null);
        if(directory==null)throw new IOException("App storage unavailable / 应用存储不可用");
        File staging=File.createTempFile(".model-import-",".tmp",directory);
        try {
            MessageDigest digest=metadata.sha256==null?null:MessageDigest.getInstance("SHA-256");
            long copied=0,lastReport=0;
            ModelDownloadLog.record("开始导入 / Importing "+name,"local-file");
            try(InputStream input=resolver.openInputStream(uri);FileOutputStream output=new FileOutputStream(staging)){
                if(input==null)throw new IOException("Cannot open file");
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1){
                    if(n>metadata.sizeBytes-copied)throw new IOException("Incorrect file size / 文件大小不符: "+name);
                    output.write(buffer,0,n);if(digest!=null)digest.update(buffer,0,n);copied+=n;
                    long now=android.os.SystemClock.elapsedRealtime();
                    if(now-lastReport>1500){lastReport=now;ModelDownloadLog.record(name+" · "+(copied/1000000)+" / "+(metadata.sizeBytes/1000000)+" MB","local-file");}
                }
                if(copied!=metadata.sizeBytes)throw new IOException("Incomplete file / 文件不完整: "+name);
                if(digest!=null){
                    StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(!metadata.sha256.equalsIgnoreCase(hash.toString()))throw new IOException("SHA-256 mismatch / 模型哈希不符: "+name);
                }
                output.getFD().sync();
            }
            Files.move(staging.toPath(),new File(directory,name).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            ModelDownloadLog.record("已导入 / Imported "+name+(digest==null?" · 大小已验证，仍需模型加载检查":" · SHA-256 OK"),"local-file");
        } finally { Files.deleteIfExists(staging.toPath()); }
    }
}
