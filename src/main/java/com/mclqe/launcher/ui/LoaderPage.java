package com.mclqe.launcher.ui;

import com.mclqe.launcher.config.LauncherConfig;
import com.mclqe.launcher.game.GamePaths;
import com.mclqe.launcher.game.VersionManager;
import com.mclqe.launcher.loader.LoaderCatalog;
import com.mclqe.launcher.loader.LoaderInstaller;
import com.mclqe.launcher.loader.LoaderType;
import com.mclqe.launcher.loader.LoaderVersion;
import com.mclqe.launcher.model.VersionManifest;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「安装加载器」页面：选择 MC 版本与加载器类型 → 拉取该组合的可用加载器版本 → 下载官方安装器、
 * 执行安装进程并实时回传日志 → 展示安装结果与产物版本目录。
 *
 * <p>网络请求、安装器下载与进程执行均在后台线程完成，日志与结果通过
 * {@link Platform#runLater(Runnable)} 回到 UI 线程。</p>
 */
public class LoaderPage extends VBox {

    private static final String TYPE_FABRIC = "Fabric";
    private static final String TYPE_QUILT = "Quilt";
    private static final String TYPE_FORGE = "Forge";
    private static final String TYPE_NEOFORGE = "NeoForge";

    private final LauncherConfig config;
    private final Consumer<String> logSink;
    private final Consumer<String> statusSink;
    private final Consumer<String> externalLog;
    private final LoaderInstaller installer;

    private ComboBox<VersionManifest.VersionEntry> mcBox;
    private ComboBox<String> typeBox;
    private ComboBox<LoaderVersion> versionBox;
    private Label gameDirLabel;
    private Label versionHint;
    private Label resultLabel;
    private Button refreshButton;
    private Button installButton;
    private Button openDirButton;
    private ProgressBar progressBar;
    private TextArea logArea;

    private boolean versionsLoaded;
    private boolean busy;
    private boolean autoSelecting;

    public LoaderPage(LauncherConfig config, Consumer<String> logSink, Consumer<String> statusSink) {
        this.config = config;
        this.logSink = logSink == null ? msg -> { } : logSink;
        this.statusSink = statusSink == null ? msg -> { } : statusSink;
        this.externalLog = msg -> Platform.runLater(() -> appendLog(msg));
        this.installer = new LoaderInstaller(externalLog);

        getStyleClass().add("page");
        setSpacing(6);

        // 注意顺序：buildForm 负责初始化 resultLabel 等控件，buildCenter 依赖它们，必须先构建表单
        Node header = buildHeader();
        Node form = buildForm();
        VBox center = buildCenter();
        getChildren().addAll(header, form, center);
        VBox.setVgrow(center, Priority.ALWAYS);

        updateGameDirLabel();
        logArea.appendText(nowText() + " [加载器] 就绪：支持 Fabric / Quilt / Forge / NeoForge 官方安装器\n");
    }

    /** 页面被切换到前台时调用（首次进入才拉取 MC 版本列表）。 */
    public void onShown() {
        if (!versionsLoaded) {
            versionsLoaded = true;
            loadGameVersionsAsync();
        }
        updateGameDirLabel();
    }

    // ------------------------------------------------------------------ 布局

    private Node buildHeader() {
        Label title = new Label("安装加载器");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("从官方元数据拉取加载器版本，下载官方安装器并执行，日志实时回传；安装完成后可直接在「版本下载」中启动");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);
        return new VBox(2, title, subtitle);
    }

    private Node buildForm() {
        mcBox = new ComboBox<>();
        mcBox.setMaxWidth(Double.MAX_VALUE);
        mcBox.setPromptText("MC 版本");
        mcBox.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(VersionManifest.VersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getId() + (item.isInstalled() ? "  ·  已安装" : ""));
            }
        });
        mcBox.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(VersionManifest.VersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getId());
            }
        });
        mcBox.setOnAction(e -> onTargetChanged());

        typeBox = new ComboBox<>();
        typeBox.getItems().addAll(TYPE_FABRIC, TYPE_QUILT, TYPE_FORGE, TYPE_NEOFORGE);
        typeBox.getSelectionModel().selectFirst();
        typeBox.setMaxWidth(Double.MAX_VALUE);
        typeBox.setOnAction(e -> onTargetChanged());

        versionBox = new ComboBox<>();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPromptText("加载器版本");
        versionBox.setDisable(true);

        refreshButton = new Button("刷新版本");
        refreshButton.getStyleClass().add("button-raised");
        refreshButton.setOnAction(e -> reloadVersions());

        installButton = new Button("下载并安装");
        installButton.getStyleClass().add("button-raised");
        installButton.setDisable(true);
        installButton.setOnAction(e -> startInstall());

        gameDirLabel = new Label();
        gameDirLabel.getStyleClass().add("page-subtitle");
        gameDirLabel.setStyle("-fx-font-size: 11px;");
        gameDirLabel.setWrapText(true);
        HBox.setHgrow(gameDirLabel, Priority.ALWAYS);

        openDirButton = new Button("打开游戏目录");
        openDirButton.setOnAction(e -> openGameDir());

        versionHint = new Label();
        versionHint.getStyleClass().add("page-subtitle");
        versionHint.setStyle("-fx-font-size: 11px;");

        resultLabel = new Label("尚未安装");
        resultLabel.getStyleClass().add("page-subtitle");
        resultLabel.setWrapText(true);

        progressBar = new ProgressBar();
        progressBar.setVisible(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(6);

        // 每个筛选项「标签在上、控件在下」等宽排布：避免三个下拉挤在一行导致文字被截断
        VBox mcField = fieldBox("MC 版本", mcBox, 1.1);
        VBox typeField = fieldBox("加载器", typeBox, 0.8);
        VBox versionField = fieldBox("加载器版本", versionBox, 1.3);
        HBox buttons = new HBox(8, refreshButton, installButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        HBox row1 = new HBox(12, mcField, typeField, versionField, buttons);
        row1.setAlignment(Pos.BOTTOM_LEFT);

        HBox row2 = new HBox(8, gameDirLabel, openDirButton);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(5, row1, row2, versionHint, progressBar);
        box.setSpacing(5);
        return box;
    }

    private VBox buildCenter() {
        Label logTitle = new Label("安装日志");
        logTitle.getStyleClass().add("section-title");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox center = new VBox(6, logTitle, logArea, resultLabel);
        center.getStyleClass().add("card");
        VBox.setVgrow(center, Priority.ALWAYS);
        return center;
    }

    /** 标签在上、控件在下的字段容器；weight 决定额外宽度的分配比例。 */
    private VBox fieldBox(String labelText, Region field, double weight) {
        VBox box = new VBox(3, labelTag(labelText), field);
        box.setFillWidth(true);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMinWidth(110);
        box.setPrefWidth(160 * weight);
        return box;
    }

    private Label labelTag(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    // ------------------------------------------------------------------ 数据加载

    private void loadGameVersionsAsync() {
        logArea.appendText(nowText() + " [加载器] 正在拉取 MC 版本列表…\n");
        Thread thread = new Thread(() -> {
            List<VersionManifest.VersionEntry> entries = new ArrayList<>();
            String error = null;
            try {
                Path gameDir = gameDir();
                VersionManager manager = new VersionManager(new GamePaths(gameDir), externalLog);
                VersionManifest manifest = manager.getManifest(false);
                for (VersionManifest.VersionEntry entry : manifest.getVersions()) {
                    if ("release".equalsIgnoreCase(entry.getType())) {
                        entries.add(entry);
                    }
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            String finalError = error;
            Platform.runLater(() -> applyGameVersions(entries, finalError));
        }, "mclqe-loader-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyGameVersions(List<VersionManifest.VersionEntry> entries, String error) {
        if (error != null) {
            logArea.appendText(nowText() + " [加载器] MC 版本列表加载失败：" + error + "\n");
            return;
        }
        mcBox.getItems().setAll(entries);
        if (!entries.isEmpty()) {
            mcBox.getSelectionModel().selectFirst();
            logArea.appendText(nowText() + " [加载器] 已加载 " + entries.size() + " 个正式版 MC 版本\n");
            onTargetChanged();
        }
        autoInstallIfRequested();
    }

    private void onTargetChanged() {
        if (autoSelecting) {
            return;
        }
        VersionManifest.VersionEntry mcEntry = mcBox.getValue();
        String type = typeBox.getValue();
        versionBox.getItems().clear();
        versionBox.setDisable(true);
        installButton.setDisable(true);
        if (mcEntry == null || type == null) {
            return;
        }
        LoaderType loaderType = parseType(type);
        if (!loaderType.supports(mcEntry.getId())) {
            versionHint.setText(loaderType.unsupportedHint(mcEntry.getId()));
            versionBox.setPromptText("不可用");
            logArea.appendText(nowText() + " [加载器] " + loaderType.unsupportedHint(mcEntry.getId()) + "\n");
            return;
        }
        versionHint.setText("正在获取 " + mcEntry.getId() + " 的 " + type + " 版本…");
        loadLoaderVersions(loaderType, mcEntry.getId());
    }

    private void reloadVersions() {
        if (busy) {
            return;
        }
        onTargetChanged();
    }

    private void loadLoaderVersions(LoaderType type, String mcVersion) {
        Thread thread = new Thread(() -> {
            List<LoaderVersion> versions = List.of();
            String error = null;
            try {
                versions = installer.catalog().versions(type, mcVersion);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            List<LoaderVersion> finalVersions = versions;
            String finalError = error;
            Platform.runLater(() -> applyLoaderVersions(type, mcVersion, finalVersions, finalError));
        }, "mclqe-loader-loader-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyLoaderVersions(LoaderType type, String mcVersion, List<LoaderVersion> versions,
                                     String error) {
        // 用户可能已切换目标，丢弃过期结果
        VersionManifest.VersionEntry current = mcBox.getValue();
        if (current == null || !current.getId().equals(mcVersion) || parseType(typeBox.getValue()) != type) {
            return;
        }
        if (error != null) {
            versionHint.setText(error);
            versionBox.setPromptText("获取失败");
            logArea.appendText(nowText() + " [加载器] " + type.displayName() + " 版本获取失败：" + error + "\n");
            return;
        }
        versionBox.getItems().setAll(versions);
        versionBox.getSelectionModel().selectFirst();
        versionBox.setDisable(false);
        installButton.setDisable(false);
        versionHint.setText("共 " + versions.size() + " 个 " + type.displayName() + " 版本（按版本号倒序），首个为最新");
        logArea.appendText(nowText() + " [加载器] " + mcVersion + " 可用 " + type.displayName()
                + " 版本 " + versions.size() + " 个，默认选中最新的 " + versions.get(0).version() + "\n");
        autoInstallIfRequested();
    }

    // ------------------------------------------------------------------ 安装

    private void startInstall() {
        if (busy) {
            return;
        }
        VersionManifest.VersionEntry mcEntry = mcBox.getValue();
        LoaderVersion loaderVersion = versionBox.getValue();
        if (Boolean.getBoolean("mclqe.ui.trace")) {
            System.out.println("[LoaderPage] startInstall mc=" + (mcEntry == null ? "null" : mcEntry.getId())
                    + " version=" + (loaderVersion == null ? "null" : loaderVersion.version())
                    + " busy=" + busy);
        }
        if (mcEntry == null || loaderVersion == null) {
            resultLabel.setText("请先选择 MC 版本与加载器版本");
            return;
        }
        LoaderType type = parseType(typeBox.getValue());
        String mcVersion = mcEntry.getId();
        Path gameDir = gameDir();

        busy = true;
        installButton.setDisable(true);
        refreshButton.setDisable(true);
        mcBox.setDisable(true);
        typeBox.setDisable(true);
        versionBox.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        resultLabel.setText("正在安装 " + type.displayName() + " " + loaderVersion.version() + " …");
        statusSink.accept("正在安装 " + type.displayName() + " " + loaderVersion.version());
        logArea.appendText(nowText() + " [加载器] ─── 开始安装 " + type.displayName() + " "
                + loaderVersion.version() + " / MC " + mcVersion + " ───\n");

        Thread thread = new Thread(() -> {
            LoaderInstaller.InstallResult result = installer.install(type, mcVersion, loaderVersion.version(),
                    gameDir);
            Platform.runLater(() -> finishInstall(result));
        }, "mclqe-loader-install");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishInstall(LoaderInstaller.InstallResult result) {
        busy = false;
        installButton.setDisable(false);
        refreshButton.setDisable(false);
        mcBox.setDisable(false);
        typeBox.setDisable(false);
        versionBox.setDisable(versionBox.getItems().isEmpty());
        progressBar.setVisible(false);

        if (result.success()) {
            resultLabel.setText("安装成功：版本 " + result.versionId() + " → " + result.versionDir());
            logSink.accept("[加载器] 安装成功：" + result.versionId());
            statusSink.accept("加载器安装成功：" + result.versionId());
            logArea.appendText(nowText() + " [加载器] 安装成功，版本 ID：" + result.versionId()
                    + "，可在启动页选择该版本启动\n");
            verifyVersionJson(result);
        } else {
            resultLabel.setText(result.message());
            logSink.accept("[加载器] " + result.message());
            statusSink.accept("加载器安装失败");
            logArea.appendText(nowText() + " [加载器] 失败：" + result.message() + "\n");
        }
        logArea.appendText("[TEST-INSTALL-RESULT] success=" + result.success()
                + " version=" + result.versionId() + " exit=" + result.exitCode() + "\n");
        logArea.appendText("[TEST-DONE]\n");
    }

    /** 用 VersionManager 再解析一次版本 JSON，确认启动器可直接识别该版本。 */
    private void verifyVersionJson(LoaderInstaller.InstallResult result) {
        try {
            VersionManager manager = new VersionManager(new GamePaths(gameDir()), externalLog);
            manager.getVersionMeta(result.versionId(), false);
            logArea.appendText(nowText() + " [加载器] 版本 JSON 校验通过，启动器可识别 " + result.versionId() + "\n");
        } catch (IOException | RuntimeException e) {
            logArea.appendText(nowText() + " [加载器] 版本 JSON 校验失败：" + e.getMessage() + "\n");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private Path gameDir() {
        String dir = config == null ? null : config.getGameDir();
        if (dir == null || dir.isBlank()) {
            return GamePaths.defaultGameDir();
        }
        return Path.of(dir);
    }

    private void updateGameDirLabel() {
        if (gameDirLabel == null) {
            return;
        }
        Path dir = gameDir();
        gameDirLabel.setText("安装目标：" + dir + (Files.isDirectory(dir) ? "" : "（目录不存在，安装时自动创建）"));
    }

    private void openGameDir() {
        Path dir = gameDir();
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (IOException | UnsupportedOperationException e) {
            logArea.appendText(nowText() + " [加载器] 打开目录失败：" + e.getMessage() + "\n");
        }
    }

    private static LoaderType parseType(String label) {
        if (TYPE_QUILT.equals(label)) {
            return LoaderType.QUILT;
        }
        if (TYPE_FORGE.equals(label)) {
            return LoaderType.FORGE;
        }
        if (TYPE_NEOFORGE.equals(label)) {
            return LoaderType.NEOFORGE;
        }
        return LoaderType.FABRIC;
    }

    private void appendLog(String line) {
        if (Boolean.getBoolean("mclqe.ui.trace")) {
            System.out.println("[LoaderPage] " + line);
        }
        if (logArea != null) {
            logArea.appendText(line + "\n");
        }
    }

    private static String nowText() {
        LocalTime time = LocalTime.now();
        return String.format("[%02d:%02d:%02d]", time.getHour(), time.getMinute(), time.getSecond());
    }

    // ------------------------------------------------------------------ 自检钩子（仅在启动参数存在时触发）

    private void autoInstallIfRequested() {
        if (!Boolean.getBoolean("mclqe.loader.autoInstall")) {
            return;
        }
        if (Boolean.getBoolean("mclqe.ui.trace")) {
            System.out.println("[LoaderPage] autoInstall check mcItems=" + mcBox.getItems().size()
                    + " verItems=" + versionBox.getItems().size()
                    + " started=" + Boolean.getBoolean("mclqe.loader.autoInstall.started"));
        }
        if (mcBox.getItems().isEmpty() || versionBox.getItems().isEmpty()) {
            return;
        }
        String mc = System.getProperty("mclqe.loader.mc");
        String type = System.getProperty("mclqe.loader.type");
        String version = System.getProperty("mclqe.loader.version");
        VersionManifest.VersionEntry entry = mcBox.getItems().stream()
                .filter(item -> mc == null || mc.equals(item.getId()))
                .findFirst().orElse(null);
        if (entry == null) {
            return;
        }
        // 第一阶段：只校准 MC 版本与加载器类型的选择（会触发版本列表重载），
        // 待重载完成回调再次进入本方法后才进入第二阶段真正执行安装。
        if (!Boolean.getBoolean("mclqe.loader.autoInstall.prepared")) {
            System.setProperty("mclqe.loader.autoInstall.prepared", "true");
            mcBox.getSelectionModel().select(entry);
            if (type != null) {
                typeBox.getSelectionModel().select(type);
            }
            return;
        }
        if (mc != null && (mcBox.getValue() == null || !mc.equals(mcBox.getValue().getId()))) {
            return;
        }
        if (type != null && !type.equals(typeBox.getValue())) {
            return;
        }
        autoSelecting = true;
        if (version != null) {
            versionBox.getItems().stream()
                    .filter(item -> version.equals(item.version()))
                    .findFirst()
                    .ifPresent(item -> versionBox.getSelectionModel().select(item));
        }
        autoSelecting = false;
        if (!Boolean.getBoolean("mclqe.loader.autoInstall.started")) {
            System.setProperty("mclqe.loader.autoInstall.started", "true");
            startInstall();
        }
    }
}
