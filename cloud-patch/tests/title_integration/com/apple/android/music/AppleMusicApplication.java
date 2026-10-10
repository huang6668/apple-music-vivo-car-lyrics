package com.apple.android.music;

import android.app.Application;
import java.io.File;

public final class AppleMusicApplication {
    private static volatile File filesDirectory;
    private static final TestApplication APPLICATION = new TestApplication();

    public static void setFilesDirectory(File directory) {
        filesDirectory = directory;
    }

    public static final class a {
        public static Application c() { return APPLICATION; }
        public static Application a() { return APPLICATION; }
    }

    public static final class TestApplication extends Application {
        public File getFilesDir() { return filesDirectory; }
    }
}
