package nie.translator.rtranslator.downloader;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Imports original allowlisted assets, only from documents explicitly selected/granted by the user. */
public final class ModelFileImporter {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "verified-model-import"));
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final int MAX_DOCUMENTS = 4096;
    private static final int MAX_DEPTH = 4;
    private final WeakReference<Fragment> owner;
    private final ActivityResultLauncher<String[]> picker;
    private final ActivityResultLauncher<Uri> folderPicker;
    private Runnable imported;

    public ModelFileImporter(Fragment fragment) {
        owner = new WeakReference<>(fragment);
        picker = fragment.registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::receive);
        folderPicker = fragment.registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::receiveFolder);
    }

    public void launch(Runnable onImported) {
        Fragment fragment = owner.get();
        if (fragment == null || !fragment.isAdded()) return;
        if (BUSY.get()) { Toast.makeText(fragment.requireContext(), "Import is running / 正在导入", Toast.LENGTH_LONG).show(); return; }
        imported = onImported;
        boolean zh = isChinese(fragment.requireContext());
        String[] options = zh ? new String[]{"选择模型文件（可多选）", "选择已有模型文件夹", "旧版模型在哪里？"}
                : new String[]{"Select model files", "Select existing model folder", "Where are my previous models?"};
        new AlertDialog.Builder(fragment.requireContext())
                .setTitle(zh ? "复用已有模型，不必重复下载" : "Reuse existing model files")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) picker.launch(new String[]{"*/*"});
                    else if (which == 1) folderPicker.launch(null);
                    else showHelp();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showHelp() {
        Fragment f = owner.get();
        if (f == null || !f.isAdded()) return;
        boolean zh = isChinese(f.requireContext());
        String message = zh
                ? "1. 浏览器或电脑下载的原始模型：在系统文件选择器中进入「下载 / Download」或你保存模型的文件夹，可多选 .onnx 和原始 .zip。也可选择 Download/RTranslator-models 等子文件夹扫描。\n\n"
                  + "2. 旧版 App 自动下载的模型通常在旧应用的私有 files 目录，部分 ZIP 已自动解压。Android 11 起，不能从这里直接打开另一个包的 Android/data 或私有目录；缺少权限不代表模型丢了。\n\n"
                  + "3. 不要卸载旧版或清除数据。保留模型的升级必须同时匹配包名和签名；已有模型仅在旧 App 内时，需要兼容升级或先从旧 App 导出/通过已授权电脑备份。\n\n"
                  + "文件夹导入只读取你明确选择的目录（最多 4 层），按官方文件名、精确大小和 SHA-256 校验，不会修改原文件。已经解压的 Translation 文件夹不是原始模型 ZIP。"
                : "Select original .onnx / .zip assets in Downloads or a folder you chose. Folder import scans up to four levels.\n\n"
                  + "Models downloaded inside the old app usually live in its private files directory; some archives have already been extracted. Android 11+ prevents this picker from reading another app's private files or Android/data.\n\n"
                  + "Do not uninstall the old app or clear its data. An in-place update needs BOTH the same application ID and compatible signing key. Export/authorized computer backup is required when only the old app holds the files.\n\n"
                  + "Original names, exact sizes and SHA-256 are checked; source files are not modified. An extracted Translation folder is not an original model ZIP.";
        new AlertDialog.Builder(f.requireContext()).setTitle(zh ? "旧模型复用说明" : "Reusing previous models")
                .setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

    private static boolean isChinese(Context c) {
        return "zh".equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    private void receive(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        startImport(uris, null);
    }

    private void receiveFolder(Uri uri) {
        if (uri != null) startImport(null, uri);
    }

    private void startImport(List<Uri> selected, Uri tree) {
        Fragment fragment = owner.get();
        if (fragment == null || !fragment.isAdded() || !BUSY.compareAndSet(false, true)) return;
        Context context = fragment.requireContext().getApplicationContext();
        WORKER.execute(() -> {
            int success = 0; String lastError = "";
            try {
                List<Uri> files = selected;
                if (tree != null) {
                    DownloadTrace.state("正在扫描用户授权的模型文件夹 / Scanning selected folder");
                    files = scanFolder(context.getContentResolver(), tree);
                    if (files.isEmpty()) throw new IOException("未找到原始官方 .onnx / .zip；已解压模型和旧 App 私有目录不能这样导入 / No original assets found");
                }
                if (files.size() > ModelDownloadManifest.entries().size()) throw new IOException("Too many model files");
                Set<String> names = new HashSet<>();
                for (Uri uri : files) {
                    try {
                        String name = displayName(context.getContentResolver(), uri);
                        if (!names.add(name)) throw new IOException("重复文件名 / Duplicate filename: " + name);
                        copy(context, uri, name); success++;
                    } catch (Exception e) {
                        lastError = errorText(e);
                        DownloadTrace.state("导入失败 / Import failed: " + lastError);
                    }
                }
            } catch (Exception e) {
                lastError = errorText(e); DownloadTrace.state(lastError);
            } finally { BUSY.set(false); }
            final int count = success; final String error = lastError;
            new Handler(Looper.getMainLooper()).post(() -> {
                Fragment current = owner.get();
                if (current == null || !current.isAdded() || current.getView() == null) return;
                String result = isChinese(context)
                        ? "已导入 " + count + " 个文件。点击对应模型的下载/重试以复用。"
                        : "Imported " + count + " files. Tap download/retry to reuse.";
                if (!error.isEmpty()) result += "\n" + error;
                new AlertDialog.Builder(current.requireContext()).setTitle(isChinese(context) ? "模型导入结果" : "Import results")
                        .setMessage(result).setPositiveButton(android.R.string.ok, null).show();
                if (count > 0 && imported != null) imported.run();
            });
        });
    }

    /** Bounded traversal of a granted SAF tree, never raw filesystem paths or other apps' sandboxes. */
    private static List<Uri> scanFolder(ContentResolver resolver, Uri tree) throws IOException {
        List<Uri> result = new ArrayList<>();
        ArrayDeque<String[]> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Set<String> names = new HashSet<>();
        pending.add(new String[]{DocumentsContract.getTreeDocumentId(tree), "0"});
        int inspected = 0;
        while (!pending.isEmpty()) {
            String[] item = pending.removeFirst();
            if (!visited.add(item[0])) continue;
            int depth = Integer.parseInt(item[1]);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, item[0]);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
            try (Cursor c = resolver.query(children, columns, null, null, null)) {
                if (c == null) throw new IOException("无法读取此文件夹 / Cannot read selected folder");
                while (c.moveToNext()) {
                    if (++inspected > MAX_DOCUMENTS) throw new IOException("文件太多，请直接选择模型所在子文件夹 / Folder too large");
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (depth < MAX_DEPTH) pending.add(new String[]{id, Integer.toString(depth + 1)});
                    } else if (ModelDownloadManifest.forFilename(name) != null) {
                        if (!names.add(name)) throw new IOException("同名文件重复，请直接选择所需文件 / Duplicate model: " + name);
                        result.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    }
                }
            } catch (SecurityException e) { throw new IOException("文件夹未授权，请重新选择 / Folder permission denied", e); }
        }
        return result;
    }

    private static String displayName(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (ModelDownloadManifest.forFilename(name) != null) return name;
                throw new IOException("不是原始官方模型 / Not an original model asset: " + name);
            }
        }
        throw new IOException("无法读取文件名 / Filename unavailable");
    }

    private static void copy(Context context, Uri uri, String name) throws IOException {
        ModelDownloadManifest.Entry metadata = ModelDownloadManifest.forFilename(name);
        if (metadata == null) throw new IOException("Unsupported model filename");
        File directory = context.getExternalFilesDir(null);
        if (directory == null) throw new IOException("App storage unavailable / 应用存储不可用");
        DownloadTrace.state("开始导入 / Importing " + name);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open model document");
            LocalModelFiles.stagePickedModel(in, new File(directory, name), metadata.sizeBytes, metadata.sha256);
        }
        DownloadTrace.state("已验证并导入 / Verified import: " + name);
    }

    private static String errorText(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
