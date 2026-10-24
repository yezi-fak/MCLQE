package com.mclqe.launcher;

import com.mclqe.launcher.ui.LauncherApp;
import javafx.application.Application;

/**
 * 程序入口。
 * <p>独立主类调用 {@link Application#launch(Class, String...)}，
 * 可避免非模块化环境下 JavaFX 直接以主类启动时的模块路径问题。</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(LauncherApp.class, args);
    }
}
