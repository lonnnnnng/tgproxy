package dev.tgproxy.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** long: 测试提供器会单独启动进程，入口只依赖 Android/Java，不能依赖目标 APK 的 Kotlin 运行时。 */
public final class ExportTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) {
        int caller = Binder.getCallingUid();
        int target;
        try { target = getContext().getPackageManager().getApplicationInfo("dev.tgproxy.mobile.poc", 0).uid; }
        catch (PackageManager.NameNotFoundException error) { throw new SecurityException("fixture target unavailable"); }
        if (caller != getContext().getApplicationInfo().uid && caller != target) throw new SecurityException("fixture caller rejected");
        if (uri.getPathSegments().size() != 1) throw new IllegalArgumentException("fixture path rejected");
        String id = uri.getPathSegments().get(0);
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("fixture identity rejected");
        // long: 文件只能落在测试 APK 的独立缓存中，UUID 不映射任何用户文件或公共目录。
        File directory = new File(getContext().getCacheDir(), "export-validation");
        directory.mkdirs();
        return new File(directory, id + ".bin");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        File target = file(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = target.getName();
            if (OpenableColumns.SIZE.equals(columns[index]) && !"unknown".equals(uri.getQueryParameter("case"))) values[index] = target.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("deny".equals(uri.getQueryParameter("case")) && !"r".equals(mode)) throw new SecurityException("fixture access denied");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public int delete(Uri uri, String selection, String[] args) {
        File target = file(uri);
        return !target.exists() || target.delete() ? 1 : 0;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public static Uri uri() { return uri(""); }
    public static Uri uri(String kind) {
        Uri.Builder builder = new Uri.Builder().scheme("content").authority("dev.tgproxy.mobile.poc.test.exports").appendPath(UUID.randomUUID().toString());
        if (!kind.isEmpty()) builder.appendQueryParameter("case", kind);
        return builder.build();
    }
}
