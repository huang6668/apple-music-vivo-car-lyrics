package dev.amenhancer.compat;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Supplies the module's DexKit directory when the embedded loader leaves it null. */
public final class ModuleNativeLibrary {
    private ModuleNativeLibrary() {}

    public static synchronized String resolve(ApplicationInfo info) {
        if (info == null) throw new IllegalStateException("AM++ module ApplicationInfo is null");
        if (info.nativeLibraryDir != null
                && new File(info.nativeLibraryDir, "libdexkit.so").isFile()) {
            return info.nativeLibraryDir;
        }
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null || info.sourceDir == null) {
                throw new IllegalStateException("AM++ application or module sourceDir is unavailable");
            }
            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            try (ZipFile apk = new ZipFile(info.sourceDir)) {
                for (String abi : abis) {
                    ZipEntry entry = apk.getEntry("lib/" + abi + "/libdexkit.so");
                    if (entry == null) continue;
                    File directory = new File(app.getCodeCacheDir(),
                            "ampp-native/" + abi + "-" + Long.toHexString(entry.getCrc()));
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IllegalStateException("Cannot create AM++ native library directory");
                    }
                    File library = new File(directory, "libdexkit.so");
                    if (!library.isFile() || library.length() != entry.getSize()) {
                        File temporary = File.createTempFile("dexkit-", ".tmp", directory);
                        try {
                            try (InputStream input = apk.getInputStream(entry);
                                 FileOutputStream output = new FileOutputStream(temporary)) {
                                byte[] buffer = new byte[32768];
                                int count;
                                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                                output.getFD().sync();
                            }
                            if (temporary.length() != entry.getSize()) {
                                throw new IllegalStateException("Incomplete DexKit extraction");
                            }
                            if (!temporary.setReadable(true, true) || !temporary.setExecutable(true, true)
                                    || !temporary.setWritable(false, false) || !temporary.renameTo(library)) {
                                throw new IllegalStateException("Cannot publish DexKit native library");
                            }
                        } finally {
                            if (temporary.exists()) temporary.delete();
                        }
                    }
                    info.nativeLibraryDir = directory.getAbsolutePath();
                    Log.i("AMPP-NativeCompat", "Resolved DexKit native directory for " + abi);
                    return info.nativeLibraryDir;
                }
            }
            throw new IllegalStateException("Module has no DexKit library for this process ABI");
        } catch (Exception error) {
            throw new IllegalStateException("AM++ native library preparation failed", error);
        }
    }
}
