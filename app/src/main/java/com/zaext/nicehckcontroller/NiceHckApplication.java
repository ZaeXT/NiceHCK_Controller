package com.zaext.nicehckcontroller;

import android.app.Application;
import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy2;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator;
import java.io.File;

public class NiceHckApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // 配置 XLog 日志系统
        LogConfiguration config = new LogConfiguration.Builder()
                .logLevel(LogLevel.ALL) // 设置日志级别为全部
                .tag("NiceHCK") // 设置全局标签
                .build();

        File logDir = new File(getExternalFilesDir(null), "logs");
        FilePrinter filePrinter = new FilePrinter.Builder(logDir.getAbsolutePath())
                .fileNameGenerator(new DateFileNameGenerator())
                .backupStrategy(new FileSizeBackupStrategy2(1024 * 1024, 3)) // 每个日志文件最大1MB，最多保留3个备份
                .cleanStrategy(new FileLastModifiedCleanStrategy(7 * 24 * 60 * 60 * 1000L)) // 保留7天内的日志
                .build();

        XLog.init(config, new AndroidPrinter(), filePrinter);
    }
}
