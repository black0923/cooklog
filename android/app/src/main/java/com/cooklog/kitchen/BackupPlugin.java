package com.cooklog.kitchen;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 浏览器里的 a[download] 在 WebView 中没有下载器接住，点了等于没点。
 * 这里把备份真正写进系统「下载」目录，用户能在文件管理器里找到。
 */
@CapacitorPlugin(name = "Backup")
public class BackupPlugin extends Plugin {

    @PluginMethod
    public void saveText(PluginCall call) {
        String name = call.getString("name", "backup.json");
        String content = call.getString("content", "");
        if (name == null || name.trim().isEmpty()) name = "backup.json";
        name = name.replace('/', '_').replace('\\', '_');
        if (content == null) content = "";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try {
            JSObject ret = new JSObject();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContext().getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("无法创建文件");
                OutputStream os = getContext().getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("无法写入文件");
                os.write(bytes);
                os.flush();
                os.close();
                ret.put("path", "下载/" + name);
                ret.put("uri", uri.toString());
                ret.put("shared", true);
                call.resolve(ret);
            } else {
                File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new Exception("存储不可用");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.flush();
                fos.close();
                ret.put("path", f.getAbsolutePath());
                ret.put("uri", "");
                ret.put("shared", false);
                call.resolve(ret);
            }
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "保存失败" : e.getMessage());
        }
    }
}
