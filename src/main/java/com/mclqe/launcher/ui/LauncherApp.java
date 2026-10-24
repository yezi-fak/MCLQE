package com.mclqe.launcher.ui;

import com.mclqe.launcher.auth.AccountStore;
import com.mclqe.launcher.auth.AuthAccount;
import com.mclqe.launcher.auth.AuthException;
import com.mclqe.launcher.auth.AuthPaths;
import com.mclqe.launcher.auth.MicrosoftAccount;
import com.mclqe.launcher.auth.MicrosoftAuthService;
import com.mclqe.launcher.auth.OfflineAccount;
import com.mclqe.launcher.auth.OfflineAccountStore;
import com.mclqe.launcher.config.ConfigManager;
import com.mclqe.launcher.config.LauncherConfig;
import com.mclqe.launcher.config.LauncherPaths;
import com.mclqe.launcher.game.GameDirEntry;
import com.mclqe.launcher.game.GameDirManager;
import com.mclqe.launcher.game.GameLauncher;
import com.mclqe.launcher.game.GamePaths;
import com.mclqe.launcher.game.Instance;
import com.mclqe.launcher.game.InstanceManager;
import com.mclqe.launcher.game.JavaDetector;
import com.mclqe.launcher.game.JavaRequirement;
import com.mclqe.launcher.game.LocalVersion;
import com.mclqe.launcher.game.VersionManager;
import com.mclqe.launcher.model.VersionManifest;
import com.mclqe.launcher.net.DownloadSource;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * MCLQE 启动器主界面。
 * <p>界面结构参照 HMCL：自绘顶栏（窗口按钮自绘）、左侧导航 + 内容区
 * （主页卡片 / 版本列表 / 设置页）+ 底部状态栏。所有颜色通过 app.css 中的
 * {@code -monet-*} 变量体系提供，支持浅色与深色两套主题。</p>
 *
 * <p>原有功能全部保留：版本清单解析、Java 运行时检测、内存与用户名配置、
 * 配置持久化、启动 / 停止游戏、进度与日志输出。</p>
 */
public class LauncherApp extends Application {

    private static final String PAGE_HOME = "home";
    private static final String PAGE_INSTANCES = "instances";
    private static final String PAGE_ACCOUNTS = "accounts";
    private static final String PAGE_VERSIONS = "versions";
    private static final String PAGE_DIRS = "dirs";
    private static final String PAGE_MODS = "mods";
    private static final String PAGE_LOADER = "loader";
    private static final String PAGE_SETTINGS = "settings";

    private static final int LOG_CHAR_LIMIT = 200_000;
    private static final int LOG_TRIM_SIZE = 100_000;
    private static final int MEMORY_MIN_MB = 512;
    private static final int MEMORY_MAX_MB = 32768;
    private static final int MEMORY_STEP_MB = 512;
    private static final int THREADS_MIN = 1;
    private static final int THREADS_MAX = 64;

    private final ConfigManager configManager = new ConfigManager(ConfigManager.defaultConfigDir());
    /** 正版账户存储（D:\MCLQE\SA\STEV\accounts.json）。 */
    private final AccountStore accountStore = new AccountStore();
    /** 非正版（离线）账户存储（D:\MCLQE\SA\offline.json）。 */
    private final OfflineAccountStore offlineStore = new OfflineAccountStore();

    private LauncherConfig config;
    private GamePaths gamePaths;
    private GameLauncher gameLauncher;
    private Stage primaryStage;

    /**
     * 游戏会话是否处于运行中。
     *
     * <p>对标 Prism Launcher 的 MinecraftAccount::shouldRefresh：账户被游戏使用时绝不刷新，
     * 因为刷新会使正在运行的游戏持有的令牌失效、直接踢出会话。volatile 保证启动线程与 JavaFX 线程可见性。</p>
     */
    private volatile boolean gameSessionActive = false;

    /** 当前选中的 Microsoft 正版账户；null 表示使用离线账户 */
    private MicrosoftAccount currentMsa;
    /** 正在进行的登录流程；非空时禁用「正版登录」入口 */
    private MicrosoftAuthService authService;
    /** 登录弹窗的剩余有效期倒计时 */
    private Timeline loginCountdown;
    /** 截图演示模式：用假设备码渲染登录弹窗（不发起任何网络请求） */
    private boolean mockLogin = false;

    private StackPane sceneRoot;
    private StackPane contentArea;
    private String currentPage = PAGE_HOME;

    private Node homePage;
    private Node instancesPage;
    private Node accountsPage;
    private Node versionsPage;
    private Node dirsPage;
    private Node settingsPage;
    private ModsPage modsPage;
    private LoaderPage loaderPage;

    private HBox navHome;
    private HBox navInstances;
    private HBox navMods;
    private HBox navLoader;
    private HBox navAccounts;
    private HBox navSettings;
    private Label themeItemLabel;

    // ---------------- 主页（Monet 风格：问候语 + Hero 实例卡 + 双列内容区） ----------------
    private Label greetingLabel;
    private Label heroAvatarLabel;
    private Label heroTitleLabel;
    private Label heroMetaLabel;
    private Label heroPathLabel;
    private HBox heroTagRow;
    private Button heroOpenButton;

    // ---------------- 实例页 ----------------
    private ListView<Instance> instanceList;
    private TextField instanceSearchField;
    private Label instancesSubtitle;
    private Label detailTitleLabel;
    private Label detailMetaLabel;
    private Label detailPathLabel;
    private CheckBox isolationBox;
    private FlowPane contentEntryPane;
    private Label contentSummaryLabel;
    private Button detailLaunchButton;
    private Instance selectedInstance;

    // ---------------- 账户页 ----------------
    private VBox accountListBox;
    private Label accountsSubtitle;

    // ---------------- 主页右侧 / 设置页（多目录、线程数、隔离） ----------------
    private VBox homeInstanceList;
    private VBox homeDirList;
    private VBox gameDirListBox;
    private Slider threadsSlider;
    private Label threadsValueLabel;
    private CheckBox defaultIsolationBox;
    /** 程序化同步实例列表选中项时置位，避免与「选中实例 → 定位版本」互相递归 */
    private boolean syncingInstanceSelection = false;
    /** 当前游戏目录下的实例缓存 */
    private List<Instance> instances = new ArrayList<>();

    private Label accountNameLabel;
    private Label accountDetailLabel;
    private Button accountActionButton;
    private Button accountLogoutButton;
    /** 设置页：Azure 应用 ID 输入框 */
    private TextField clientIdField;
    private Label versionsPageSubtitle;
    private Label selectedVersionLabel;
    /** 设置页：启动 Java 检测开关 */
    private CheckBox javaCheckBox;

    private Button launchButton;
    private Button launchMenuButton;
    private Button applyVersionButton;
    private Button deleteVersionButton;
    private CheckBox installedOnlyBox;

    private ListView<VersionManifest.VersionEntry> versionList;
    private ListView<GameDirEntry> dirList;
    private TextField versionSearchField;
    private TextField newDirField;
    private Label currentDirLabel;
    private Button openFolderButton;
    private Button deleteFolderButton;
    private TextField gameDirField;
    private ComboBox<JavaDetector.JavaInfo> javaBox;
    private Slider memorySlider;
    private Label memoryValueLabel;
    private Slider minMemorySlider;
    private Label minMemoryValueLabel;
    private TextField usernameField;
    /** 下载源下拉（自动优选 / 官方 / 社区镜像 / 自定义） */
    private ComboBox<DownloadSource> sourceBox;
    /** 下载源状态说明（显示自动优选结果） */
    private Label sourceStatusLabel;
    /** 程序化刷新下拉显示时置位，避免误触发切换日志 */
    private boolean refreshingSourceBox = false;
    /** 自定义下载源地址输入框 */
    private TextField customSourceField;
    private TextArea logArea;
    private ProgressBar progressBar;
    private Label statusLabel;

    private List<VersionManifest.VersionEntry> allVersions = new ArrayList<>();
    private VersionManifest.VersionEntry selectedVersion;
    private List<LocalVersion> localVersions = new ArrayList<>();
    private boolean darkMode;
    /** 演示模式：自动走一遍「新建目录 + 删除确认」流程，仅用于截图验证（-Dmclqe.ui.demo=new|delete） */
    private boolean demoMode = !System.getProperty("mclqe.ui.demo", "").isBlank();

    /** 自动化验证通过 -Dmclqe.ui.gamedir 覆盖游戏目录时保存的原始值（非空表示配置不得被覆盖值污染） */
    private String gameDirOverrideBackup;

    // ---------------- 启动时 Java 检测 ----------------
    /** 启动 Java 检测服务（探测 / 判定 / 防打扰策略，纯逻辑） */
    private JavaCheckService javaCheckService;
    /** 当前展示的 Java 检测提示层（null 表示未展示） */
    private Node javaCheckOverlay;
    /** 自动打开下载页的定时器与倒计时（取消时需停止） */
    private PauseTransition javaCheckAutoOpen;
    private Timeline javaCheckCountdown;

    // ================================================================== 生命周期

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        this.config = configManager.load();
        // 非正版账户以 D:\MCLQE\SA\offline.json 为准；文件缺失时把旧 config.json 里的离线账户迁移过去
        if (offlineStore.load()) {
            config.setUsername(offlineStore.getUsername());
            config.setUuid(offlineStore.getUuid());
        } else {
            persistOfflineQuietly();
        }
        String dirOverride = System.getProperty("mclqe.ui.gamedir", "").trim();
        if (!dirOverride.isEmpty()) {
            // 仅本次运行生效（供自动化截图与功能自验使用），退出时不会写回配置文件
            gameDirOverrideBackup = config.getGameDir();
            config.setGameDir(dirOverride);
        } else if (config.getGameDir() == null || config.getGameDir().isBlank()) {
            // 首次启动：默认使用「启动器所在目录\MCLQE」，程序目录不可写时回退到「%USERPROFILE%\MCLQE」。
            // 仅在此分支生成默认值，已有配置（gameDir / gameDirs）一律不覆盖。
            Path defaultDir = LauncherPaths.defaultGameDir();
            config.setGameDir(defaultDir.toString());
            try {
                GameDirManager.createStandardStructure(defaultDir);
            } catch (IOException ignored) {
                // 创建失败不阻塞启动：后续下载 / 启动时仍会按需创建
            }
        }
        // 便于排查与自动化验证：把最终解析出的游戏目录输出到控制台
        System.out.println("[MCLQE] 游戏目录：" + config.getGameDir());
        this.gamePaths = new GamePaths(Path.of(config.getGameDir()));
        this.darkMode = config.isDarkMode() || Boolean.getBoolean("mclqe.ui.dark");
        this.javaCheckService = new JavaCheckService(config, configManager);

        // 载入已保存的正版账户（文件缺失 / 损坏时静默回退为离线账户）
        try {
            accountStore.load();
            currentMsa = accountStore.selectedAccount();
        } catch (Exception e) {
            currentMsa = null;
        }

        BorderPane window = new BorderPane();
        window.getStyleClass().add("window-root");
        window.setTop(new TitleBar(stage, "MCLQE Launcher", this::shutdown));
        window.setLeft(buildNavigation());
        window.setCenter(buildContent());
        window.setBottom(buildStatusBar());

        sceneRoot = new StackPane(window);
        sceneRoot.setPadding(new Insets(10));

        Scene scene = new Scene(sceneRoot, 1040, 720, Color.TRANSPARENT);
        URL stylesheet = getClass().getResource("/app.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("MCLQE Launcher");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(event -> saveConfigQuietly());
        stage.show();

        applyTheme();
        String initialPage = System.getProperty("mclqe.ui.page");
        if (initialPage != null && !initialPage.isBlank()) {
            showPage(initialPage.trim().toLowerCase(Locale.ROOT));
        }
        updateVersionCard();
        updateAccountCard();
        appendLog("MCLQE 启动器已就绪，游戏目录：" + config.getGameDir());
        appendLog("正版账户文件：" + accountStore.getFile());
        appendLog("离线账户文件：" + offlineStore.getFile());
        detectJavaAsync();
        // 先扫描本地实例（纯本地 IO，快），再拉取远端版本清单
        refreshInstancesAsync();
        refreshVersionsAsync(false);
        // 启动 Java 检测：放在首帧显示之后异步执行，绝不阻塞窗口出现
        Platform.runLater(this::runJavaCheck);
        if (demoMode) {
            scheduleDemoFlow();
        }
    }

    /** 保存配置并退出。 */
    private void shutdown() {
        saveConfigQuietly();
        if (gameLauncher != null && gameLauncher.isRunning()) {
            gameLauncher.stop();
        }
        Platform.exit();
    }

    private void saveConfigQuietly() {
        try {
            applyConfigFromUi();
            String current = config.getGameDir();
            if (gameDirOverrideBackup != null) {
                // -Dmclqe.ui.gamedir 的覆盖值只用于本次运行，写盘前还原用户原本的游戏目录
                config.setGameDir(gameDirOverrideBackup);
            }
            configManager.save(config);
            if (gameDirOverrideBackup != null) {
                config.setGameDir(current);
            }
        } catch (Exception ignored) {
            // 退出阶段忽略保存失败
        }
    }

    // ================================================================== 导航与页面骨架
    private VBox buildNavigation() {
        VBox drawer = new VBox(2);
        drawer.getStyleClass().add("nav-drawer");

        navHome = navItem("icon-home", "主页", PAGE_HOME);
        navInstances = navItem("icon-grid", "实例", PAGE_INSTANCES);
        navMods = navItem("icon-mod", "模组下载", PAGE_MODS);
        navLoader = navItem("icon-java", "安装加载器", PAGE_LOADER);
        navAccounts = navItem("icon-account", "账户", PAGE_ACCOUNTS);
        navSettings = navItem("icon-settings", "设置", PAGE_SETTINGS);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        themeItemLabel = new Label("深色模式");
        HBox themeItem = new HBox(12, Icons.of("icon-theme", 16), themeItemLabel);
        themeItem.getStyleClass().add("nav-item");
        themeItem.setAlignment(Pos.CENTER_LEFT);
        themeItem.setOnMouseClicked(event -> toggleTheme());

        HBox versionsItem = navItem("icon-versions", "版本下载", PAGE_VERSIONS);
        HBox dirsItem = navItem("icon-folder", "目录内容", PAGE_DIRS);

        // 版本号统一只在右下角状态栏展示，侧栏脚注不再重复（避免左下 / 右下双份版本）
        Label footerLabel = new Label("Java 21 · JavaFX");
        footerLabel.getStyleClass().add("nav-footer-label");
        footerLabel.setWrapText(true);

        VBox footer = new VBox(4, versionsItem, dirsItem, themeItem, footerLabel);
        footer.getStyleClass().add("nav-footer");
        drawer.getChildren().addAll(navHome, navInstances, navMods, navLoader, navAccounts, navSettings, spacer,
                footer);
        return drawer;
    }

    private HBox navItem(String icon, String text, String page) {
        HBox item = new HBox(12, Icons.of(icon, 16), new Label(text));
        item.getStyleClass().add("nav-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.setOnMouseClicked(event -> showPage(page));
        return item;
    }

    private StackPane buildContent() {
        contentArea = new StackPane();
        contentArea.getStyleClass().add("content-area");

        homePage = buildHomePage();
        instancesPage = buildInstancesPage();
        accountsPage = buildAccountsPage();
        versionsPage = buildVersionsPage();
        dirsPage = buildDirsPage();
        settingsPage = buildSettingsPage();
        modsPage = new ModsPage(config, configManager, this::currentInstance, this::appendLog, this::setStatus);
        loaderPage = new LoaderPage(config, this::appendLog, this::setStatus);

        contentArea.getChildren().addAll(homePage, instancesPage, accountsPage, versionsPage, dirsPage,
                settingsPage, modsPage, loaderPage);
        showPage(PAGE_HOME);
        return contentArea;
    }

    private void showPage(String page) {
        currentPage = page;
        for (Node node : List.of(homePage, instancesPage, accountsPage, versionsPage, dirsPage, settingsPage,
                modsPage, loaderPage)) {
            node.setVisible(false);
            node.setManaged(false);
        }
        Node target = switch (page) {
            case PAGE_INSTANCES -> instancesPage;
            case PAGE_ACCOUNTS -> accountsPage;
            case PAGE_VERSIONS -> versionsPage;
            case PAGE_DIRS -> dirsPage;
            case PAGE_MODS -> modsPage;
            case PAGE_LOADER -> loaderPage;
            case PAGE_SETTINGS -> settingsPage;
            default -> homePage;
        };
        target.setVisible(true);
        target.setManaged(true);

        if (PAGE_HOME.equals(page)) {
            renderHomeInstances();
            renderHomeDirs();
            updateAccountCard();
        }
        if (PAGE_MODS.equals(page)) {
            if (modsPage != null) {
                modsPage.onShown();
            }
        }
        if (PAGE_LOADER.equals(page)) {
            if (loaderPage != null) {
                loaderPage.onShown();
            }
        }
        if (PAGE_INSTANCES.equals(page)) {
            if (instances.isEmpty()) {
                refreshInstancesAsync();
            } else {
                renderInstanceList();
            }
        }
        if (PAGE_ACCOUNTS.equals(page)) {
            renderAccounts();
        }
        if (PAGE_VERSIONS.equals(page)) {
            if (allVersions.isEmpty()) {
                refreshVersionsAsync(false);
            }
        }
        if (PAGE_DIRS.equals(page)) {
            renderGameDirs();
            refreshDirList();
        }
        updateNavSelection();
    }

    private void updateNavSelection() {
        List<HBox> items = List.of(navHome, navInstances, navMods, navLoader, navAccounts, navSettings);
        for (HBox item : items) {
            if (item != null) {
                item.getStyleClass().remove("selected");
            }
        }
        switch (currentPage) {
            case PAGE_INSTANCES -> navInstances.getStyleClass().add("selected");
            case PAGE_MODS -> navMods.getStyleClass().add("selected");
            case PAGE_LOADER -> navLoader.getStyleClass().add("selected");
            case PAGE_ACCOUNTS -> navAccounts.getStyleClass().add("selected");
            case PAGE_SETTINGS -> navSettings.getStyleClass().add("selected");
            case PAGE_HOME -> navHome.getStyleClass().add("selected");
            default -> {
                // 版本下载 / 目录内容为二级页面，导航保持无选中
            }
        }
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        config.setDarkMode(darkMode);
        applyTheme();
    }

    private void applyTheme() {
        if (darkMode) {
            if (!sceneRoot.getStyleClass().contains("dark")) {
                sceneRoot.getStyleClass().add("dark");
            }
        } else {
            sceneRoot.getStyleClass().remove("dark");
        }
        if (themeItemLabel != null) {
            themeItemLabel.setText(darkMode ? "浅色模式" : "深色模式");
        }
    }

    // ================================================================== 主页

    private VBox buildHomePage() {
        greetingLabel = new Label("你好，" + playerName());
        greetingLabel.getStyleClass().add("greeting");
        Label greetingSub = new Label("选择一个实例，配置运行时，然后开始你的 Minecraft 之旅");
        greetingSub.getStyleClass().add("greeting-sub");
        VBox greeting = new VBox(3, greetingLabel, greetingSub);

        HBox body = new HBox(14, buildHomeLeftColumn(), buildHomeRightColumn());
        body.setAlignment(Pos.TOP_LEFT);

        VBox content = new VBox(14, greeting, buildHeroCard(), body);
        content.getStyleClass().add("page");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    /** 主页 Hero 卡：当前实例 + 分裂启动按钮（HMCL 风格）。 */
    private HBox buildHeroCard() {
        heroAvatarLabel = new Label("?");
        heroAvatarLabel.getStyleClass().add("hero-avatar-text");
        StackPane avatar = new StackPane(heroAvatarLabel);
        avatar.getStyleClass().add("hero-avatar");

        heroTitleLabel = new Label("尚未选择实例");
        heroTitleLabel.getStyleClass().add("hero-title");
        heroTagRow = new HBox(6);
        heroTagRow.setAlignment(Pos.CENTER_LEFT);
        heroMetaLabel = new Label("前往「实例」页选择或创建一个实例");
        heroMetaLabel.getStyleClass().add("hero-subtitle");
        heroMetaLabel.setWrapText(true);
        heroPathLabel = new Label("");
        heroPathLabel.getStyleClass().add("hero-path");

        VBox text = new VBox(5, heroTitleLabel, heroTagRow, heroMetaLabel, heroPathLabel);
        HBox.setHgrow(text, Priority.ALWAYS);

        launchButton = new Button("启动游戏");
        launchButton.setGraphic(Icons.of("icon-play", 18));
        launchButton.getStyleClass().add("launch-button");
        launchButton.setDefaultButton(true);
        launchButton.setOnAction(event -> onLaunchButton());

        launchMenuButton = new Button();
        launchMenuButton.setGraphic(Icons.of("icon-expand", 16));
        launchMenuButton.getStyleClass().add("launch-menu-button");
        launchMenuButton.setFocusTraversable(false);
        launchMenuButton.setOnAction(event -> showLaunchMenu());

        HBox launchPane = new HBox(launchButton, launchMenuButton);
        launchPane.getStyleClass().add("launch-pane");
        launchPane.setAlignment(Pos.CENTER_LEFT);

        heroOpenButton = new Button("打开目录");
        heroOpenButton.setGraphic(Icons.of("icon-folder", 14));
        heroOpenButton.getStyleClass().add("button-border");
        heroOpenButton.setOnAction(event -> openCurrentContentDir());

        Button detailButton = new Button("实例详情");
        detailButton.setGraphic(Icons.of("icon-info", 14));
        detailButton.getStyleClass().add("button-border");
        detailButton.setOnAction(event -> showPage(PAGE_INSTANCES));

        HBox secondary = new HBox(8, heroOpenButton, detailButton);
        secondary.setAlignment(Pos.CENTER_RIGHT);

        VBox actions = new VBox(10, launchPane, secondary);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox hero = new HBox(16, avatar, text, actions);
        hero.getStyleClass().add("hero-card");
        hero.setAlignment(Pos.CENTER_LEFT);
        return hero;
    }

    private VBox buildHomeLeftColumn() {
        Label listTitle = new Label("实例");
        listTitle.getStyleClass().add("card-title");
        Button manageButton = new Button("全部实例");
        manageButton.getStyleClass().add("button-text");
        manageButton.setOnAction(event -> showPage(PAGE_INSTANCES));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox listHeader = new HBox(8, Icons.of("icon-grid", 16), listTitle, spacer, manageButton);
        listHeader.setAlignment(Pos.CENTER_LEFT);

        homeInstanceList = new VBox(4);
        Label hint = new Label("正在读取实例 ...");
        hint.getStyleClass().add("card-subtitle");
        homeInstanceList.getChildren().add(hint);

        VBox instanceCard = new VBox(8, listHeader, homeInstanceList);
        instanceCard.getStyleClass().add("card");

        VBox column = new VBox(14, instanceCard, buildLogCard());
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private VBox buildLogCard() {
        Label title = new Label("运行日志");
        title.getStyleClass().add("card-title");

        Button clearButton = new Button("清空");
        clearButton.getStyleClass().add("button-text");
        clearButton.setOnAction(event -> logArea.clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, Icons.of("icon-terminal", 16), title, spacer, clearButton);
        header.setAlignment(Pos.CENTER_LEFT);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        logArea.setPrefRowCount(9);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox card = new VBox(10, header, logArea);
        card.getStyleClass().addAll("card", "log-card");
        VBox.setVgrow(card, Priority.ALWAYS);
        return card;
    }

    private VBox buildHomeRightColumn() {
        // ---------------- 游戏目录（多目录） ----------------
        Label dirTitle = new Label("游戏目录");
        dirTitle.getStyleClass().add("card-title");
        Button manageDirsButton = new Button("管理");
        manageDirsButton.getStyleClass().add("button-text");
        manageDirsButton.setOnAction(event -> showPage(PAGE_SETTINGS));
        Region dirSpacer = new Region();
        HBox.setHgrow(dirSpacer, Priority.ALWAYS);
        HBox dirHeader = new HBox(8, Icons.of("icon-folder", 16), dirTitle, dirSpacer, manageDirsButton);
        dirHeader.setAlignment(Pos.CENTER_LEFT);

        homeDirList = new VBox(4);
        VBox dirCard = new VBox(8, dirHeader, homeDirList);
        dirCard.getStyleClass().add("card");

        // ---------------- 账户 ----------------
        accountNameLabel = new Label(config.getUsername());
        accountNameLabel.getStyleClass().add("two-line-title");
        accountDetailLabel = new Label("离线账户");
        accountDetailLabel.getStyleClass().add("two-line-subtitle");

        VBox accountText = new VBox(4, accountNameLabel, accountDetailLabel);
        HBox.setHgrow(accountText, Priority.ALWAYS);

        HBox accountHeader = new HBox(12, Icons.of("icon-account", 24), accountText);
        accountHeader.setAlignment(Pos.CENTER_LEFT);

        accountActionButton = new Button("正版登录");
        accountActionButton.getStyleClass().add("button-border");
        accountActionButton.setOnAction(event -> onAccountAction());

        accountLogoutButton = new Button("退出登录");
        accountLogoutButton.getStyleClass().add("button-border");
        accountLogoutButton.setOnAction(event -> logoutMicrosoft());
        accountLogoutButton.setManaged(false);
        accountLogoutButton.setVisible(false);

        HBox accountButtons = new HBox(8, accountActionButton, accountLogoutButton);
        accountButtons.setAlignment(Pos.CENTER_LEFT);

        VBox accountCard = new VBox(10, accountHeader, accountButtons);
        accountCard.getStyleClass().addAll("card", "clickable");
        accountCard.setOnMouseClicked(event -> showPage(PAGE_ACCOUNTS));

        // ---------------- 快捷操作 ----------------
        VBox quickList = new VBox(
                quickItem("icon-folder", "打开游戏目录", this::openGameDir),
                quickItem("icon-refresh", "刷新实例与版本", () -> {
                    refreshInstancesAsync();
                    refreshVersionsAsync(true);
                }),
                quickItem("icon-java", "重新检测 Java", this::detectJavaAsync),
                quickItem("icon-account", "打开凭据目录", this::openAuthDir),
                quickItem("icon-settings", "打开配置目录", this::openConfigDir));
        Label quickTitle = new Label("快捷操作");
        quickTitle.getStyleClass().add("card-title");
        VBox quickCard = new VBox(6, quickTitle, quickList);
        quickCard.getStyleClass().add("card");

        VBox column = new VBox(14, dirCard, accountCard, quickCard);
        column.setPrefWidth(300);
        column.setMinWidth(260);
        return column;
    }

    /** 按钮按内容撑开，禁止被 HBox 压缩导致文字出现省略号。 */
    private static <T extends javafx.scene.control.ButtonBase> T noShrink(T button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private HBox quickItem(String icon, String text, Runnable action) {
        HBox item = new HBox(10, Icons.of(icon, 16), new Label(text));
        item.getStyleClass().add("iconed-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.setOnMouseClicked(event -> action.run());
        return item;
    }

    private Label pageTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-title");
        return label;
    }

    private Label pageSubtitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-subtitle");
        return label;
    }

    // ================================================================== 实例页
    private VBox buildInstancesPage() {
        instancesSubtitle = pageSubtitle("versions 下的每个版本目录即一个实例，可单独命名、切换隔离并管理模组与存档");

        instanceSearchField = new TextField();
        instanceSearchField.setPromptText("搜索实例（显示名 / 版本 id）");
        instanceSearchField.getStyleClass().add("search-field");
        instanceSearchField.textProperty().addListener((observable, oldValue, newValue) -> renderInstanceList());
        HBox.setHgrow(instanceSearchField, Priority.ALWAYS);

        Button refreshButton = new Button("刷新");
        refreshButton.setGraphic(Icons.of("icon-refresh", 14));
        refreshButton.getStyleClass().add("button-border");
        refreshButton.setOnAction(event -> refreshInstancesAsync());

        Button installButton = new Button("安装新实例");
        installButton.setGraphic(Icons.of("icon-plus", 14));
        installButton.getStyleClass().add("button-raised");
        installButton.setOnAction(event -> {
            showPage(PAGE_VERSIONS);
            if (allVersions.isEmpty()) {
                refreshVersionsAsync(true);
            }
        });

        HBox toolbar = new HBox(8, instanceSearchField, noShrink(refreshButton), noShrink(installButton));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        instanceList = new ListView<>();
        instanceList.getStyleClass().add("version-list");
        instanceList.setCellFactory(view -> new InstanceListCell());
        instanceList.setPlaceholder(new Label("当前游戏目录下还没有实例"));
        instanceList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (!syncingInstanceSelection && newValue != null) {
                onInstanceSelected(newValue);
            }
        });
        instanceList.setOnMouseClicked(event -> {
            Instance instance = instanceList.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && instance != null) {
                launchInstance(instance);
            }
        });
        VBox.setVgrow(instanceList, Priority.ALWAYS);

        VBox listColumn = new VBox(instanceList);
        listColumn.setMinWidth(300);
        HBox.setHgrow(listColumn, Priority.ALWAYS);

        ScrollPane detailScroll = new ScrollPane(buildInstanceDetailPanel());
        detailScroll.setFitToWidth(true);
        detailScroll.getStyleClass().add("detail-scroll");
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setPrefWidth(430);
        detailScroll.setMinWidth(320);

        HBox split = new HBox(14, listColumn, detailScroll);
        split.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox page = new VBox(10, pageTitle("实例"), instancesSubtitle, toolbar, split);
        page.getStyleClass().add("page");
        return page;
    }

    private VBox buildInstanceDetailPanel() {
        detailTitleLabel = new Label("未选择实例");
        detailTitleLabel.getStyleClass().add("detail-title");
        detailMetaLabel = new Label("在左侧选择一个实例，查看版本信息与内容管理入口");
        detailMetaLabel.getStyleClass().add("instance-meta");
        detailMetaLabel.setWrapText(true);
        detailPathLabel = new Label("");
        detailPathLabel.getStyleClass().add("path-value");
        detailPathLabel.setWrapText(true);

        isolationBox = new CheckBox("版本隔离（模组 / 存档 / 资源包按实例独立存放）");
        isolationBox.setDisable(true);
        isolationBox.setWrapText(true);
        isolationBox.setOnAction(event -> toggleIsolation());

        contentSummaryLabel = new Label("内容管理");
        contentSummaryLabel.getStyleClass().add("content-header");
        Button refreshContentButton = new Button("刷新");
        refreshContentButton.getStyleClass().add("button-text");
        refreshContentButton.setOnAction(event -> {
            if (selectedInstance != null) {
                renderInstanceDetail(selectedInstance);
            }
        });
        Region contentSpacer = new Region();
        HBox.setHgrow(contentSpacer, Priority.ALWAYS);
        HBox contentHeader = new HBox(8, contentSummaryLabel, contentSpacer, refreshContentButton);
        contentHeader.setAlignment(Pos.CENTER_LEFT);

        contentEntryPane = new FlowPane(8, 8);
        contentEntryPane.setPrefWrapLength(380);

        detailLaunchButton = new Button("启动此实例");
        detailLaunchButton.setGraphic(Icons.of("icon-play", 14));
        detailLaunchButton.getStyleClass().add("button-raised");
        detailLaunchButton.setDisable(true);
        detailLaunchButton.setOnAction(event -> {
            if (selectedInstance != null) {
                launchInstance(selectedInstance);
            }
        });

        Button renameButton = new Button("重命名");
        renameButton.getStyleClass().add("button-border");
        renameButton.setOnAction(event -> renameSelectedInstance());

        Button folderButton = new Button("打开实例文件夹");
        folderButton.setGraphic(Icons.of("icon-folder", 14));
        folderButton.getStyleClass().add("button-border");
        folderButton.setOnAction(event -> {
            if (selectedInstance != null) {
                openDirectory(selectedInstance.versionDir());
            }
        });

        Button deleteButton = new Button("删除实例");
        deleteButton.getStyleClass().add("button-danger");
        deleteButton.setOnAction(event -> deleteSelectedInstance());

        // 用 FlowPane 承载操作按钮：详情栏较窄时自动换行，避免按钮文字被截断
        FlowPane actions = new FlowPane(8, 8, noShrink(detailLaunchButton), noShrink(renameButton),
                noShrink(folderButton), noShrink(deleteButton));
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox panel = new VBox(10, detailTitleLabel, detailMetaLabel, detailPathLabel,
                isolationBox, contentHeader, contentEntryPane, actions);
        panel.getStyleClass().add("detail-panel");
        return panel;
    }

    /** 实例单元格：首字母头像 + 显示名 + 版本 id / 状态 / 占用空间。 */
    private static class InstanceListCell extends ListCell<Instance> {
        private final Label avatarText = new Label();
        private final Label title = new Label();
        private final Label tag = new Label();
        private final Label subtitle = new Label();
        private final HBox container;

        InstanceListCell() {
            avatarText.getStyleClass().add("list-avatar-text");
            StackPane avatar = new StackPane(avatarText);
            avatar.getStyleClass().add("list-avatar");

            title.getStyleClass().add("two-line-title");
            tag.getStyleClass().add("tag");
            subtitle.getStyleClass().add("two-line-subtitle");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox firstLine = new HBox(8, title, spacer, tag);
            firstLine.setAlignment(Pos.CENTER_LEFT);

            VBox text = new VBox(3, firstLine, subtitle);
            HBox.setHgrow(text, Priority.ALWAYS);

            container = new HBox(10, avatar, text);
            container.getStyleClass().add("two-line-list-item");
            container.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Instance instance, boolean empty) {
            super.updateItem(instance, empty);
            if (empty || instance == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            avatarText.setText(initial(instance.displayName()));
            title.setText(instance.displayName());
            String status = instance.healthy() ? "可启动" : instance.statusLabel();
            subtitle.setText(instance.id() + " · " + typeLabel(instance.type()) + " · " + status
                    + " · " + formatSize(instance.sizeBytes()));
            tag.getStyleClass().removeAll("tag-installed", "tag-local", "tag-missing", "tag-isolated");
            if (!instance.healthy()) {
                tag.setText(instance.statusLabel());
                tag.getStyleClass().add("tag-missing");
            } else if (instance.isolated()) {
                tag.setText("隔离");
                tag.getStyleClass().add("tag-isolated");
            } else {
                tag.setText("共享");
                tag.getStyleClass().add("tag-local");
            }
            setGraphic(container);
            setText(null);
        }
    }

    /** 后台扫描实例目录，避免阻塞 UI。 */
    private void refreshInstancesAsync() {
        Path root;
        try {
            root = Path.of(config.getGameDir());
        } catch (Exception e) {
            appendLog("游戏目录无效: " + e.getMessage());
            return;
        }
        final Path scanRoot = root;
        setStatus("正在扫描实例 ...");
        Thread thread = new Thread(() -> {
            List<Instance> scanned;
            try {
                scanned = InstanceManager.list(scanRoot, config.isDefaultVersionIsolation());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("实例扫描失败: " + e.getMessage());
                    setStatus("实例扫描失败");
                });
                return;
            }
            Platform.runLater(() -> {
                instances = new ArrayList<>(scanned);
                appendLog("实例扫描完成，共 " + scanned.size() + " 个（" + scanRoot + "）");
                renderInstanceList();
                renderHomeInstances();
                setStatus("就绪");
            });
        }, "mcqe-instances");
        thread.setDaemon(true);
        thread.start();
    }

    private void renderInstanceList() {
        if (instanceList == null) {
            return;
        }
        String keyword = instanceSearchField == null || instanceSearchField.getText() == null
                ? "" : instanceSearchField.getText().trim().toLowerCase(Locale.ROOT);
        List<Instance> filtered = new ArrayList<>();
        for (Instance instance : instances) {
            if (keyword.isEmpty()
                    || instance.id().toLowerCase(Locale.ROOT).contains(keyword)
                    || instance.displayName().toLowerCase(Locale.ROOT).contains(keyword)) {
                filtered.add(instance);
            }
        }
        Instance keep = instanceList.getSelectionModel().getSelectedItem();
        syncingInstanceSelection = true;
        instanceList.getItems().setAll(filtered);
        syncingInstanceSelection = false;
        if (instancesSubtitle != null) {
            int total = instances.size();
            instancesSubtitle.setText("当前目录：" + config.getGameDir() + " · 共 " + total + " 个实例"
                    + (keyword.isEmpty() ? "" : " · 匹配 " + filtered.size() + " 个"));
        }
        if (selectedInstance == null && !filtered.isEmpty()) {
            Instance target = null;
            // 优先沿用配置里记录的版本，但损坏实例只作为兜底，避免默认选中不可用实例
            for (Instance instance : filtered) {
                if (instance.id().equals(config.getVersionId()) && instance.healthy()) {
                    target = instance;
                    break;
                }
            }
            if (target == null) {
                for (Instance instance : filtered) {
                    if (instance.healthy()) {
                        target = instance;
                        break;
                    }
                }
            }
            if (target == null) {
                target = filtered.get(0);
            }
            selectInstance(target);
            return;
        }
        if (keep != null) {
            selectInstance(keep);
        }
    }

    private void selectInstance(Instance instance) {
        if (instanceList == null || instance == null) {
            return;
        }
        syncingInstanceSelection = true;
        for (Instance candidate : instanceList.getItems()) {
            if (candidate.id().equals(instance.id())) {
                instanceList.getSelectionModel().select(candidate);
                break;
            }
        }
        syncingInstanceSelection = false;
        onInstanceSelected(instance);
    }

    /** 选中实例：同步版本、写入配置，并刷新 Hero 与详情面板。 */
    private void onInstanceSelected(Instance instance) {
        if (instance == null) {
            return;
        }
        selectedInstance = instance;
        selectedVersion = findVersionEntry(instance.id());
        if (selectedVersion == null) {
            VersionManifest.VersionEntry entry = new VersionManifest.VersionEntry(instance.id());
            entry.setType(instance.type());
            entry.setInstalled(true);
            entry.setLocalOnly(true);
            entry.setDamaged(!instance.healthy());
            entry.setIncomplete(instance.missingJar());
            entry.setLocalNote(instance.statusLabel());
            selectedVersion = entry;
        }
        config.setVersionId(instance.id());
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }
        renderInstanceDetail(instance);
        updateHero();
        renderHomeInstances();
        if (versionList != null) {
            versionList.getSelectionModel().select(selectedVersion);
        }
        setStatus("当前实例：" + instance.displayName());
    }

    private VersionManifest.VersionEntry findVersionEntry(String id) {
        for (VersionManifest.VersionEntry entry : allVersions) {
            if (entry.getId().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private void renderInstanceDetail(Instance instance) {
        if (detailTitleLabel == null) {
            return;
        }
        if (instance == null) {
            detailTitleLabel.setText("未选择实例");
            detailMetaLabel.setText("在左侧选择一个实例，查看版本信息与内容管理入口");
            detailPathLabel.setText("");
            isolationBox.setDisable(true);
            isolationBox.setSelected(false);
            detailLaunchButton.setDisable(true);
            contentEntryPane.getChildren().clear();
            return;
        }
        detailTitleLabel.setText(instance.displayName());
        StringBuilder meta = new StringBuilder();
        meta.append(instance.id()).append(" · ").append(typeLabel(instance.type()));
        if (instance.inheritsFrom() != null && !instance.inheritsFrom().isBlank()) {
            meta.append(" · 继承 ").append(instance.inheritsFrom());
        }
        meta.append(" · ").append(instance.healthy() ? "可启动" : instance.statusLabel());
        meta.append(" · ").append(formatSize(instance.sizeBytes()));
        detailMetaLabel.setText(meta.toString());
        detailPathLabel.setText("内容目录：" + instance.contentRoot());
        isolationBox.setDisable(false);
        isolationBox.setSelected(instance.isolated());
        detailLaunchButton.setDisable(!instance.healthy());

        contentEntryPane.getChildren().clear();
        for (InstanceManager.ContentKind kind : InstanceManager.ContentKind.values()) {
            contentEntryPane.getChildren().add(contentEntry(kind, instance));
        }
    }

    /** 内容入口卡片：图标 + 名称 + 条目数，点击打开对应目录。 */
    private VBox contentEntry(InstanceManager.ContentKind kind, Instance instance) {
        String icon = switch (kind) {
            case MODS -> "icon-mod";
            case RESOURCEPACKS -> "icon-pack";
            case SHADERPACKS -> "icon-shader";
            case SAVES -> "icon-save";
            case SCREENSHOTS -> "icon-image";
            case LOGS -> "icon-terminal";
        };
        Label name = new Label(kind.label());
        name.getStyleClass().add("content-title");
        Label count = new Label(InstanceManager.describeCount(instance.contentDir(kind), kind));
        count.getStyleClass().add("content-subtitle");
        count.setWrapText(true);
        count.setMaxWidth(Double.MAX_VALUE);

        VBox box = new VBox(4, Icons.of(icon, 18), name, count);
        box.getStyleClass().add("content-entry");
        box.setPrefWidth(112);
        box.setOnMouseClicked(event -> openContentDir(instance, kind));
        return box;
    }

    private void openContentDir(Instance instance, InstanceManager.ContentKind kind) {
        Path dir = instance.contentDir(kind);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            appendLog("创建内容目录失败: " + e.getMessage());
            showError("无法打开目录", "创建目录失败：\n" + dir + "\n\n" + e.getMessage());
            return;
        }
        openDirectory(dir);
    }

    private void toggleIsolation() {
        if (selectedInstance == null) {
            return;
        }
        boolean value = isolationBox.isSelected();
        try {
            InstanceManager.setIsolated(selectedInstance.versionDir(), value, config.isDefaultVersionIsolation());
            config.setDefaultVersionIsolation(value);
            configManager.save(config);
            appendLog("实例 " + selectedInstance.id() + (value ? " 已开启版本隔离" : " 已关闭版本隔离（回落到游戏目录共享内容）"));
            refreshInstancesAsync();
        } catch (IOException e) {
            appendLog("保存实例设置失败: " + e.getMessage());
            showError("保存失败", "无法写入实例设置：\n" + e.getMessage());
        }
    }

    private void renameSelectedInstance() {
        Instance instance = selectedInstance;
        if (instance == null && instanceList != null) {
            instance = instanceList.getSelectionModel().getSelectedItem();
        }
        if (instance == null) {
            setStatus("请先选择一个实例");
            return;
        }
        TextField field = new TextField(instance.displayName());
        field.getStyleClass().add("search-field");
        if (!showTextInput("重命名实例", "显示名称仅保存在实例的 mclqe-instance.json，不修改版本 id", field)) {
            return;
        }
        String name = field.getText() == null ? "" : field.getText().trim();
        try {
            InstanceManager.rename(instance.versionDir(), name);
            appendLog("实例 " + instance.id() + " 已重命名为：" + (name.isEmpty() ? instance.id() : name));
            refreshInstancesAsync();
        } catch (IOException e) {
            appendLog("重命名失败：" + e.getMessage());
            showError("重命名失败", e.getMessage());
        }
    }

    private void deleteSelectedInstance() {
        Instance instance = selectedInstance;
        if (instance == null && instanceList != null) {
            instance = instanceList.getSelectionModel().getSelectedItem();
        }
        if (instance == null) {
            setStatus("请先选择一个实例");
            return;
        }
        Path versionDir = instance.versionDir();
        StringBuilder detail = new StringBuilder();
        detail.append("即将删除该实例（移入系统回收站，可从回收站还原）：\n\n")
                .append("· 显示名：").append(instance.displayName()).append("\n")
                .append("· 版本 id：").append(instance.id()).append("\n")
                .append("· 版本目录：").append(versionDir).append("\n")
                .append("· 同时清理 versions 下同名的 ").append(instance.id()).append(".json / .jar 等文件\n");
        if (instance.isolated() && Files.isDirectory(instance.contentRoot())) {
            detail.append("· 隔离内容目录：").append(instance.contentRoot()).append("（其中的模组 / 存档会一并删除）\n");
        }
        if (!confirmDelete("删除实例确认", instance.displayName(), detail.toString())) {
            setStatus("已取消删除：" + instance.displayName());
            return;
        }
        final Instance target = instance;
        setStatus("正在删除实例 " + target.displayName() + " ...");
        Thread thread = new Thread(() -> {
            GameDirManager.DeleteResult result = GameDirManager.deleteVersion(
                    Path.of(config.getGameDir()), target.id());
            Platform.runLater(() -> {
                appendLog(result.message());
                if (result.success()) {
                    if (target.id().equals(config.getVersionId())) {
                        config.setVersionId("");
                        selectedVersion = null;
                        selectedInstance = null;
                        updateHero();
                    }
                    setStatus("实例 " + target.displayName() + " 已移入回收站");
                    refreshInstancesAsync();
                    refreshVersionsAsync(false);
                } else {
                    setStatus("删除失败：" + result.message());
                    showError("删除失败", result.message());
                }
            });
        }, "mcqe-delete-instance");
        thread.setDaemon(true);
        thread.start();
    }

    private void launchInstance(Instance instance) {
        if (instance == null) {
            return;
        }
        if (!instance.healthy()) {
            showError("无法启动该实例", "实例 " + instance.id() + " 当前状态：" + instance.statusLabel()
                    + "\n\n请先修复或重新安装该版本。");
            return;
        }
        onInstanceSelected(instance);
        startGame();
    }

    // ================================================================== 账户页
    private VBox buildAccountsPage() {
        accountsSubtitle = pageSubtitle("离线账户与微软正版账户并存，可随时切换；正版令牌仅保存在本机凭据目录");

        Button addButton = new Button("添加正版账户");
        addButton.setGraphic(Icons.of("icon-plus", 14));
        addButton.getStyleClass().add("button-raised");
        addButton.setOnAction(event -> openMicrosoftLogin());

        Button offlineButton = new Button("切换到离线账户");
        offlineButton.setGraphic(Icons.of("icon-account", 14));
        offlineButton.getStyleClass().add("button-border");
        offlineButton.setOnAction(event -> switchToOffline());

        Button renameButton = new Button("修改离线用户名");
        renameButton.getStyleClass().add("button-border");
        renameButton.setOnAction(event -> renameOfflineAccount());

        Button authDirButton = new Button("打开凭据目录");
        authDirButton.getStyleClass().add("button-border");
        authDirButton.setOnAction(event -> openAuthDir());

        HBox toolbar = new HBox(8, addButton, offlineButton, renameButton, authDirButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        accountListBox = new VBox(10);

        Label hint = new Label("提示：正版登录采用设备代码流程，点「添加正版账户」后按弹窗提示访问验证网址并输入一次性验证码即可，"
                + "无需填写任何应用 ID（已内置公开的 Azure 应用 ID，仅高级用户可在「设置」中改为自建应用）。"
                + "授权完成后令牌只保存在本机凭据目录，启动游戏前会自动刷新。");
        hint.getStyleClass().add("card-subtitle");
        hint.setWrapText(true);

        VBox content = new VBox(12, pageTitle("账户"), accountsSubtitle, toolbar, accountListBox, hint);
        content.getStyleClass().add("page");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private void renderAccounts() {
        if (accountListBox == null) {
            return;
        }
        accountListBox.getChildren().clear();
        boolean offlineActive = currentMsa == null;
        String offlineName = playerName();
        List<Node> offlineActions = new ArrayList<>();
        if (!offlineActive) {
            offlineActions.add(textButton("使用此账户", this::switchToOffline));
        }
        offlineActions.add(textButton("修改用户名", this::renameOfflineAccount));
        accountListBox.getChildren().add(accountRow(offlineActive,
                offlineName, "离线账户 · " + offlineStore.getUuid(), "离线",
                offlineActions.toArray(new Node[0])));

        for (MicrosoftAccount account : accountStore.microsoftAccounts()) {
            boolean active = currentMsa != null && account.getUuid() != null
                    && account.getUuid().equalsIgnoreCase(currentMsa.getUuid());
            List<Node> actions = new ArrayList<>();
            if (!active) {
                actions.add(textButton("使用此账户", () -> useMicrosoftAccount(account)));
            }
            actions.add(textButton("移除", () -> removeMicrosoftAccount(account)));
            accountListBox.getChildren().add(accountRow(active,
                    account.getUsername(), "正版账户 · " + account.getUuid(), "正版",
                    actions.toArray(new Node[0])));
        }

        if (accountsSubtitle != null) {
            int total = 1 + accountStore.microsoftAccounts().size();
            String current = offlineActive ? "离线 · " + offlineName : "正版 · " + currentMsa.getUsername();
            accountsSubtitle.setText("共 " + total + " 个账户，当前使用：" + current);
        }
    }

    private HBox accountRow(boolean active, String name, String meta, String badge, Node... actions) {
        Label avatarText = new Label(initial(name));
        avatarText.getStyleClass().add("list-avatar-text");
        StackPane avatar = new StackPane(avatarText);
        avatar.getStyleClass().add("list-avatar");

        Label title = new Label(name == null || name.isBlank() ? "Steve" : name);
        title.getStyleClass().add("two-line-title");
        Label tag = new Label(badge);
        tag.getStyleClass().add("tag");
        if (active) {
            tag.getStyleClass().add("tag-installed");
        }
        HBox firstLine = new HBox(8, title, tag);
        firstLine.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(meta);
        subtitle.getStyleClass().add("two-line-subtitle");

        VBox text = new VBox(3, firstLine, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(12, avatar, text);
        row.getStyleClass().add("account-card");
        if (active) {
            row.getStyleClass().add("selected");
        }
        if (actions != null && actions.length > 0) {
            HBox actionBox = new HBox(8, actions);
            actionBox.setAlignment(Pos.CENTER_RIGHT);
            row.getChildren().add(actionBox);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button textButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("button-border");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void useMicrosoftAccount(MicrosoftAccount account) {
        if (account == null) {
            return;
        }
        currentMsa = account;
        accountStore.select(account);
        persistAccounts();
        updateAccountCard();
        renderAccounts();
        if (greetingLabel != null) {
            greetingLabel.setText("你好，" + playerName());
        }
        appendLog("已切换到正版账户: " + account.getUsername() + " · " + account.getUuid());
        setStatus("当前账户：" + account.getUsername());
    }

    private void removeMicrosoftAccount(MicrosoftAccount account) {
        if (account == null) {
            return;
        }
        if (!confirmAction("移除正版账户确认", account.getUsername(),
                "将从启动器移除该正版账户的本地记录（不会影响 Microsoft 账户本身，也不会删除游戏文件）：\n\n"
                        + "· 用户名：" + account.getUsername() + "\n· UUID：" + account.getUuid(),
                "移除")) {
            return;
        }
        accountStore.remove(account.getUuid());
        if (currentMsa != null && account.getUuid() != null
                && account.getUuid().equalsIgnoreCase(currentMsa.getUuid())) {
            currentMsa = null;
            accountStore.clearSelection();
        }
        persistAccounts();
        updateAccountCard();
        renderAccounts();
        appendLog("已移除正版账户记录：" + account.getUsername());
        setStatus("已移除账户：" + account.getUsername());
    }

    private void renameOfflineAccount() {
        TextField field = new TextField(config.getUsername());
        field.getStyleClass().add("search-field");
        if (!showTextInput("修改离线用户名", "仅影响离线启动时使用的玩家名（1-16 位）", field)) {
            return;
        }
        String name = field.getText() == null ? "" : field.getText().trim();
        if (name.isEmpty()) {
            showError("用户名无效", "离线用户名不能为空。");
            return;
        }
        config.setUsername(name);
        persistOfflineQuietly();
        if (usernameField != null) {
            usernameField.setText(name);
        }
        updateAccountCard();
        renderAccounts();
        if (greetingLabel != null) {
            greetingLabel.setText("你好，" + playerName());
        }
        appendLog("离线用户名已修改：" + name);
        setStatus("离线用户名：" + name);
    }

    // ================================================================== 游戏目录（多目录管理）
    private void renderHomeDirs() {
        if (homeDirList == null) {
            return;
        }
        homeDirList.getChildren().clear();
        List<String> dirs = config.getGameDirs();
        int shown = 0;
        for (String dir : dirs) {
            if (shown >= 4) {
                break;
            }
            boolean active = dir.equalsIgnoreCase(config.getGameDir());
            Label title = new Label(shortDirName(dir));
            title.getStyleClass().add("mini-title");
            Label subtitle = new Label(active ? dir : "点击切换到此目录");
            subtitle.getStyleClass().add("mini-subtitle");
            VBox text = new VBox(2, title, subtitle);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(8, Icons.of("icon-folder", 16), text);
            if (active) {
                row.getChildren().add(chip("当前", "tag"));
            }
            row.getStyleClass().add("mini-dir-row");
            if (active) {
                row.getStyleClass().add("selected");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            final String target = dir;
            row.setOnMouseClicked(event -> {
                if (!active) {
                    switchGameDir(Path.of(target));
                }
            });
            homeDirList.getChildren().add(row);
            shown++;
        }
        if (dirs.size() > shown) {
            Label more = new Label("另有 " + (dirs.size() - shown) + " 个目录，可在「设置」中管理");
            more.getStyleClass().add("card-subtitle");
            homeDirList.getChildren().add(more);
        }
    }

    private void renderGameDirs() {
        if (gameDirListBox == null) {
            return;
        }
        gameDirListBox.getChildren().clear();
        for (String dir : config.getGameDirs()) {
            boolean active = dir.equalsIgnoreCase(config.getGameDir());
            Label title = new Label(shortDirName(dir));
            title.getStyleClass().add("two-line-title");
            Label tag = new Label(active ? "当前使用" : "已添加");
            tag.getStyleClass().add("tag");
            if (active) {
                tag.getStyleClass().add("tag-installed");
            }
            HBox firstLine = new HBox(8, title, tag);
            firstLine.setAlignment(Pos.CENTER_LEFT);
            Label subtitle = new Label(dir);
            subtitle.getStyleClass().add("two-line-subtitle");

            VBox text = new VBox(3, firstLine, subtitle);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMinWidth(0);
            HBox.setHgrow(subtitle, Priority.ALWAYS);
            subtitle.setMinWidth(0);

            Button useButton = textButton(active ? "使用中" : "切换", () -> switchGameDir(Path.of(dir)));
            useButton.setDisable(active);
            Button openButton = textButton("打开", () -> openDirectory(Path.of(dir)));
            Button removeButton = textButton("移除", () -> removeGameDir(dir));
            for (Button b : List.of(useButton, openButton, removeButton)) {
                b.setMinWidth(Region.USE_PREF_SIZE);
            }

            HBox actions = new HBox(8, useButton, openButton, removeButton);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.setMinWidth(Region.USE_PREF_SIZE);

            HBox row = new HBox(12, Icons.of("icon-folder", 20), text, actions);
            row.getStyleClass().add("dir-card");
            if (active) {
                row.getStyleClass().add("selected");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            gameDirListBox.getChildren().add(row);
        }
    }

    private void switchGameDir(Path dir) {
        if (dir == null) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            showError("目录不可用", "该游戏目录不存在或已被移动：\n" + dir);
            return;
        }
        applyGameDir(dir);
        setStatus("已切换到游戏目录：" + dir);
    }

    private void addGameDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择已有的游戏目录");
        Path current = Path.of(config.getGameDir());
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        File selected = chooser.showDialog(primaryStage);
        if (selected == null) {
            return;
        }
        Path dir = selected.toPath();
        if (!Files.isDirectory(dir)) {
            showError("目录无效", "请选择已存在的目录：\n" + dir);
            return;
        }
        config.addGameDir(dir.toString());
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }
        renderGameDirs();
        renderHomeDirs();
        appendLog("已添加游戏目录：" + dir);
        setStatus("已添加游戏目录：" + dir);
    }

    private void removeGameDir(String dir) {
        List<String> dirs = config.getGameDirs();
        if (dirs.size() <= 1) {
            showError("无法移除", "至少需要保留一个游戏目录，请先添加其他目录。");
            return;
        }
        boolean active = dir.equalsIgnoreCase(config.getGameDir());
        String detail = "将从启动器列表中移除该游戏目录，不会删除磁盘上的任何文件：\n\n· " + dir
                + (active ? "\n\n该目录正在使用，移除后将自动切换到列表中的其他目录。" : "");
        if (!confirmAction("移除游戏目录确认", shortDirName(dir), detail, "移除")) {
            return;
        }
        config.removeGameDir(dir);
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }
        appendLog("已移除游戏目录：" + dir);
        if (active) {
            applyGameDir(Path.of(config.getGameDir()));
        } else {
            renderGameDirs();
            renderHomeDirs();
        }
        setStatus("已移除游戏目录：" + dir);
    }

    // ================================================================== 主页辅助

    private String playerName() {
        if (currentMsa != null && currentMsa.getUsername() != null && !currentMsa.getUsername().isBlank()) {
            return currentMsa.getUsername();
        }
        String name = config.getUsername();
        return name == null || name.isBlank() ? "Steve" : name;
    }

    private Instance currentInstance() {
        if (selectedInstance != null) {
            return selectedInstance;
        }
        String id = config.getVersionId();
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Instance instance : instances) {
            if (instance.id().equals(id)) {
                return instance;
            }
        }
        return null;
    }

    private void renderHomeInstances() {
        if (homeInstanceList == null) {
            return;
        }
        homeInstanceList.getChildren().clear();
        if (instances.isEmpty()) {
            Label empty = new Label("当前游戏目录下还没有实例，点击「安装新实例」开始。");
            empty.getStyleClass().add("card-subtitle");
            empty.setWrapText(true);
            homeInstanceList.getChildren().add(empty);
            return;
        }
        int shown = 0;
        for (Instance instance : instances) {
            if (shown >= 5) {
                break;
            }
            homeInstanceList.getChildren().add(homeInstanceRow(instance));
            shown++;
        }
        if (instances.size() > shown) {
            Label more = new Label("还有 " + (instances.size() - shown) + " 个实例，前往「实例」页查看全部");
            more.getStyleClass().add("card-subtitle");
            homeInstanceList.getChildren().add(more);
        }
    }

    private HBox homeInstanceRow(Instance instance) {
        Label avatarText = new Label(initial(instance.displayName()));
        avatarText.getStyleClass().add("mini-avatar-text");
        StackPane avatar = new StackPane(avatarText);
        avatar.getStyleClass().add("mini-avatar");

        Label name = new Label(instance.displayName());
        name.getStyleClass().add("mini-title");
        Label subtitle = new Label(instance.id() + " · "
                + (instance.healthy() ? "可启动" : instance.statusLabel())
                + (instance.isolated() ? " · 隔离" : ""));
        subtitle.getStyleClass().add("mini-subtitle");
        VBox text = new VBox(2, name, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, avatar, text, Icons.of("icon-chevron", 14));
        row.getStyleClass().add("mini-dir-row");
        row.setAlignment(Pos.CENTER_LEFT);
        if (selectedVersion != null && selectedVersion.getId().equals(instance.id())) {
            row.getStyleClass().add("selected");
        }
        row.setOnMouseClicked(event -> {
            showPage(PAGE_INSTANCES);
            selectInstance(instance);
        });
        return row;
    }

    private void openCurrentContentDir() {
        Instance instance = currentInstance();
        if (instance != null) {
            openDirectory(instance.contentRoot());
            return;
        }
        openGameDir();
    }

    private static Label chip(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String initial(String text) {
        if (text == null || text.isBlank()) {
            return "?";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String shortDirName(String dir) {
        try {
            Path fileName = Path.of(dir).getFileName();
            return fileName == null ? dir : fileName.toString();
        } catch (Exception e) {
            return dir;
        }
    }

    private boolean showTextInput(String title, String header, TextField field) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.getDialogPane().setContent(field);
        ButtonType okButton = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(okButton, cancelButton);
        styleDialog(alert.getDialogPane());
        Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == okButton;
    }

    private boolean confirmAction(String title, String header, String detail, String okText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(detail);
        ButtonType okButton = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(okButton, cancelButton);
        styleDialog(alert.getDialogPane());
        Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == okButton;
    }

    // ================================================================== 版本页
    private VBox buildVersionsPage() {
        versionsPageSubtitle = pageSubtitle("来自官方 version_manifest_v2.json");

        versionSearchField = new TextField();
        versionSearchField.setPromptText("搜索版本（如 1.20.1 / forge / fabric）");
        versionSearchField.getStyleClass().add("search-field");
        versionSearchField.textProperty().addListener((obs, oldValue, newValue) -> filterVersions(newValue));
        HBox.setHgrow(versionSearchField, Priority.ALWAYS);

        Button refreshButton = new Button("刷新");
        refreshButton.setGraphic(Icons.of("icon-refresh", 14));
        refreshButton.getStyleClass().add("button-border");
        refreshButton.setOnAction(event -> refreshVersionsAsync(true));

        installedOnlyBox = new CheckBox("仅显示已安装");
        installedOnlyBox.selectedProperty().addListener((obs, oldValue, newValue) ->
                filterVersions(versionSearchField.getText()));

        HBox toolBar = new HBox(8, versionSearchField, installedOnlyBox, refreshButton);
        toolBar.setAlignment(Pos.CENTER_LEFT);

        versionList = new ListView<>();
        versionList.getStyleClass().add("version-list");
        versionList.setCellFactory(view -> new VersionListCell());
        versionList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                selectedVersionLabel.setText("已选中：" + newValue.getId()
                        + "（" + versionStateText(newValue) + "）");
            }
            updateVersionActionState();
        });
        versionList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                useSelectedVersion();
            }
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);

        ContextMenu versionMenu = new ContextMenu();
        MenuItem useItem = new MenuItem("使用此版本");
        useItem.setOnAction(event -> useSelectedVersion());
        MenuItem openItem = new MenuItem("打开版本文件夹");
        openItem.setOnAction(event -> openSelectedVersionFolder());
        MenuItem deleteItem = new MenuItem("删除此版本（移入回收站）");
        deleteItem.setOnAction(event -> deleteSelectedVersion());
        versionMenu.getItems().addAll(useItem, openItem, new SeparatorMenuItem(), deleteItem);
        versionList.setContextMenu(versionMenu);

        selectedVersionLabel = new Label("未选择版本");
        selectedVersionLabel.getStyleClass().add("card-subtitle");

        applyVersionButton = new Button("使用此版本");
        applyVersionButton.getStyleClass().add("button-raised");
        applyVersionButton.setOnAction(event -> useSelectedVersion());

        deleteVersionButton = new Button("删除此版本");
        deleteVersionButton.setGraphic(Icons.of("icon-delete", 14));
        deleteVersionButton.getStyleClass().add("button-danger");
        deleteVersionButton.setOnAction(event -> deleteSelectedVersion());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, selectedVersionLabel, spacer, deleteVersionButton, applyVersionButton);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox page = new VBox(10, pageTitle("版本列表"), versionsPageSubtitle, toolBar, versionList, footer);
        page.getStyleClass().add("page");
        return page;
    }

    /** 列表条目状态文字：已安装 / 未安装 / 损坏。 */
    private static String versionStateText(VersionManifest.VersionEntry entry) {
        if (entry.isDamaged()) {
            return "损坏";
        }
        if (!entry.isInstalled() || entry.isIncomplete()) {
            return "未安装";
        }
        return "已安装";
    }

    private void filterVersions(String keyword) {
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean installedOnly = installedOnlyBox != null && installedOnlyBox.isSelected();
        List<VersionManifest.VersionEntry> filtered = allVersions.stream()
                .filter(entry -> !installedOnly || (entry.isInstalled() && !entry.isIncomplete()))
                .filter(entry -> key.isEmpty()
                        || entry.getId().toLowerCase(Locale.ROOT).contains(key)
                        || typeLabel(entry.getType()).toLowerCase(Locale.ROOT).contains(key))
                .collect(Collectors.toList());
        // 已安装（含损坏）版本优先展示，方便直接看到本地状态
        List<VersionManifest.VersionEntry> ordered = new ArrayList<>(filtered.size());
        filtered.stream().filter(VersionManifest.VersionEntry::isInstalled).forEach(ordered::add);
        filtered.stream().filter(entry -> !entry.isInstalled()).forEach(ordered::add);
        filtered = ordered;
        if (versionList != null) {
            versionList.getItems().setAll(filtered);
            if (selectedVersion != null && filtered.contains(selectedVersion)) {
                versionList.getSelectionModel().select(selectedVersion);
            }
            updateVersionActionState();
        }
        if (versionsPageSubtitle != null) {
            long installed = allVersions.stream()
                    .filter(entry -> entry.isInstalled() && !entry.isIncomplete() && !entry.isDamaged())
                    .count();
            long incomplete = allVersions.stream()
                    .filter(entry -> entry.isInstalled() && entry.isIncomplete())
                    .count();
            long damaged = allVersions.stream()
                    .filter(entry -> entry.isInstalled() && entry.isDamaged())
                    .count();
            String incompleteNote = incomplete > 0 ? "（其中未安装 " + incomplete + " 个）" : "";
            String damagedNote = damaged > 0 ? "（其中损坏 " + damaged + " 个）" : "";
            versionsPageSubtitle.setText("本地已安装 " + installed + " 个版本" + incompleteNote + damagedNote
                    + " / 列表共 " + allVersions.size() + " 个，当前显示 " + filtered.size() + " 个");
        }
    }

    /** 删除按钮仅在选中「已安装」版本时可用。 */
    private void updateVersionActionState() {
        if (deleteVersionButton == null) {
            return;
        }
        VersionManifest.VersionEntry entry = versionList == null ? null
                : versionList.getSelectionModel().getSelectedItem();
        deleteVersionButton.setDisable(entry == null || !entry.isInstalled());
    }

    private void useSelectedVersion() {
        VersionManifest.VersionEntry entry = versionList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            setStatus("请先在列表中选择一个版本");
            return;
        }
        selectedVersion = entry;
        config.setVersionId(entry.getId());
        updateVersionCard();
        appendLog("已选择版本: " + entry.getId());
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }
        showPage(PAGE_HOME);
    }

    /** 同步主页 Hero、实例列表与账户区（选择版本 / 实例 / 登录态变化后调用）。 */
    private void updateVersionCard() {
        updateHero();
        renderHomeInstances();
        updateAccountCard();
    }

    private void updateHero() {
        if (heroTitleLabel == null) {
            return;
        }
        if (greetingLabel != null) {
            greetingLabel.setText("你好，" + playerName());
        }
        Instance instance = currentInstance();
        String id = selectedVersion != null ? selectedVersion.getId()
                : (instance != null ? instance.id() : "");
        if (id.isEmpty()) {
            heroAvatarLabel.setText("?");
            heroTitleLabel.setText("尚未选择实例");
            heroTagRow.getChildren().clear();
            heroMetaLabel.setText("前往「实例」页选择一个实例，或安装新实例");
            heroPathLabel.setText("");
            if (heroOpenButton != null) {
                heroOpenButton.setDisable(true);
            }
            return;
        }
        heroAvatarLabel.setText(initial(id));
        heroTitleLabel.setText(instance != null && !instance.displayName().isBlank()
                ? instance.displayName() : id);

        heroTagRow.getChildren().clear();
        String type = selectedVersion != null ? selectedVersion.getType()
                : (instance != null ? instance.type() : "");
        heroTagRow.getChildren().add(chip(typeLabel(type), "tag"));
        if (instance != null) {
            heroTagRow.getChildren().add(chip(instance.isolated() ? "版本隔离" : "共享内容", "tag"));
            if (!instance.healthy()) {
                heroTagRow.getChildren().add(chip(instance.statusLabel(), "tag"));
            }
        } else if (selectedVersion != null && selectedVersion.isInstalled()) {
            heroTagRow.getChildren().add(chip("已安装", "tag"));
        }

        StringBuilder meta = new StringBuilder(id);
        if (selectedVersion != null) {
            meta.append(" · ").append(formatReleaseTime(selectedVersion.getReleaseTime()));
        }
        if (instance != null) {
            meta.append(" · ").append(formatSize(instance.sizeBytes()));
        }
        heroMetaLabel.setText(meta.toString());

        if (instance != null) {
            heroPathLabel.setText("内容目录：" + instance.contentRoot());
        } else {
            heroPathLabel.setText("版本目录：" + Path.of(config.getGameDir(), "versions", id));
        }
        if (heroOpenButton != null) {
            heroOpenButton.setDisable(false);
        }
    }

    private void updateAccountCard() {
        if (accountNameLabel == null) {
            return;
        }
        if (currentMsa != null) {
            String name = currentMsa.getUsername();
            accountNameLabel.setText(name == null || name.isBlank() ? "正版账户" : name);
            accountDetailLabel.setText("正版账户 · " + currentMsa.getUuid());
            if (accountActionButton != null) {
                accountActionButton.setText("账户管理");
            }
            if (accountLogoutButton != null) {
                accountLogoutButton.setVisible(true);
                accountLogoutButton.setManaged(true);
            }
            return;
        }
        String username = config.getUsername();
        accountNameLabel.setText(username == null || username.isBlank() ? "Steve" : username);
        String uuid = offlineStore.getUuid();
        accountDetailLabel.setText("离线账户 · " + uuid);
        if (accountActionButton != null) {
            accountActionButton.setText(authService == null ? "正版登录" : "登录中...");
        }
        if (accountLogoutButton != null) {
            accountLogoutButton.setVisible(false);
            accountLogoutButton.setManaged(false);
        }
    }

    /** 版本类型的中文文案，供列表与卡片共用。 */
    public static String typeLabel(String type) {
        if (type == null || type.isBlank()) {
            return "未知";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_beta" -> "旧 Beta";
            case "old_alpha" -> "旧 Alpha";
            default -> type;
        };
    }

    /** 把 ISO 发布时间裁剪为 yyyy-MM-dd。 */
    public static String formatReleaseTime(String releaseTime) {
        if (releaseTime == null || releaseTime.isBlank()) {
            return "时间未知";
        }
        String date = releaseTime.length() >= 10 ? releaseTime.substring(0, 10) : releaseTime;
        return date + " 发布";
    }

    // ================================================================== 设置页
    private ScrollPane buildSettingsPage() {
        VBox list = new VBox();
        list.getStyleClass().add("options-list");

        gameDirField = new TextField(config.getGameDir());
        gameDirField.setPrefWidth(340);
        Button browseButton = new Button("浏览");
        browseButton.getStyleClass().add("button-border");
        browseButton.setOnAction(event -> chooseGameDir());

        Button newDirEntryButton = new Button("新建目录 ...");
        newDirEntryButton.setGraphic(Icons.of("icon-plus", 14));
        newDirEntryButton.getStyleClass().add("button-border");
        newDirEntryButton.setOnAction(event -> {
            // 入口就放在「游戏目录」项旁：切到目录管理页并聚焦新建输入框
            showPage(PAGE_DIRS);
            if (newDirField != null) {
                Platform.runLater(newDirField::requestFocus);
            }
        });
        list.getChildren().add(optionItem("游戏目录", "存放游戏数据的根目录（versions / libraries / assets）；"
                        + LauncherPaths.defaultGameDirHint(),
                gameDirField, browseButton, newDirEntryButton));

        // 下载源：切换后立即保存并生效（无需重启）；AUTO 模式在后台探测各源可用性
        sourceBox = new ComboBox<>();
        sourceBox.getItems().addAll(DownloadSource.all());
        sourceBox.setValue(DownloadSource.of(config.getDownloadSource()));
        sourceBox.setPrefWidth(160);

        sourceStatusLabel = new Label(DownloadSource.statusText());
        sourceStatusLabel.setWrapText(true);
        sourceStatusLabel.setMaxWidth(320);
        sourceStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #8a8f98;");
        // 自动优选完成后刷新下拉与说明文案（探测线程回调，切回 UI 线程更新）
        DownloadSource.setProbeListener(result -> Platform.runLater(this::refreshSourceStatus));

        customSourceField = new TextField(config.getCustomSourceRoot());
        customSourceField.setPromptText("自定义镜像根地址，如 https://mirror.example.com");
        customSourceField.setPrefWidth(300);
        customSourceField.setDisable(sourceBox.getValue() != DownloadSource.CUSTOM);

        sourceBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || refreshingSourceBox) {
                return;
            }
            config.setDownloadSource(newValue.key());
            customSourceField.setDisable(newValue != DownloadSource.CUSTOM);
            ConfigManager.applyDownloadSource(config);
            saveConfigQuietly();
            appendLog("下载源已切换：" + newValue.displayName());
            refreshSourceStatus();
        });
        customSourceField.textProperty().addListener((obs, oldValue, newValue) -> {
            config.setCustomSourceRoot(newValue == null ? "" : newValue.trim());
            ConfigManager.applyDownloadSource(config);
            saveConfigQuietly();
        });

        list.getChildren().add(optionItem("下载源", "版本清单 / 版本 JSON / client.jar / 依赖库 / 资源文件的获取来源；默认自动优选各源",
                sourceBox, customSourceField, sourceStatusLabel));

        if (DownloadSource.isAuto()) {
            Platform.runLater(this::refreshSourceStatus);
        }

        // 下载线程数：拖动即写入配置，下次下载立即生效（无需重启）
        threadsSlider = new Slider(THREADS_MIN, THREADS_MAX, Math.max(THREADS_MIN,
                Math.min(THREADS_MAX, config.getDownloadThreads())));
        threadsSlider.setPrefWidth(240);
        threadsSlider.setBlockIncrement(1);
        threadsSlider.setMajorTickUnit(8);
        threadsSlider.setMinorTickCount(7);
        threadsSlider.setSnapToTicks(true);
        threadsValueLabel = new Label();
        threadsValueLabel.getStyleClass().add("value-label");
        threadsValueLabel.setText(config.getDownloadThreads() + " 线程");
        threadsSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int threads = alignThreads(newValue.intValue());
            config.setDownloadThreads(threads);
            threadsValueLabel.setText(threads + " 线程");
        });
        list.getChildren().add(optionItem("下载线程数",
                "同时下载文件的最大并发数（1 - 64）；带宽充足时调高可加速，网络不稳定时建议调低",
                threadsSlider, threadsValueLabel));

        javaBox = new ComboBox<>();
        javaBox.setPrefWidth(340);
        javaBox.setPromptText("正在检测本地 Java ...");
        javaBox.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                config.setJavaPath(newValue.getJavaExecutable().toString());
                appendLog("已选择 Java: " + newValue);
            }
        });
        Button detectButton = new Button("重新检测");
        detectButton.getStyleClass().add("button-border");
        detectButton.setOnAction(event -> detectJavaAsync());
        list.getChildren().add(optionItem("Java 运行时", "用于启动游戏的 JDK，版本需与游戏匹配",
                javaBox, detectButton));

        javaCheckBox = new CheckBox("启动时检测 Java 并提示下载");
        javaCheckBox.setSelected(config.isJavaCheckEnabled() && !config.isJavaCheckDontAskAgain());
        javaCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            if (javaCheckService == null) {
                return;
            }
            if (newValue) {
                javaCheckService.restorePrompts();
                appendLog("已重新启用启动 Java 检测");
                setStatus("启动 Java 检测已启用");
            } else {
                config.setJavaCheckEnabled(false);
                javaCheckService.disableForever();
                appendLog("已关闭启动 Java 检测（可在本项随时恢复）");
                setStatus("启动 Java 检测已关闭");
            }
        });
        list.getChildren().add(optionItem("启动 Java 检测",
                "启动时检测当前版本所需 Java；缺失或版本不符时提示并给出官方下载页（同一版本 24 小时内不重复提示）",
                javaCheckBox));

        memorySlider = buildMemorySlider(config.getMaxMemoryMb());
        memoryValueLabel = new Label();
        memoryValueLabel.getStyleClass().add("value-label");
        memorySlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int memory = alignMemory(newValue.intValue());
            config.setMaxMemoryMb(memory);
            memoryValueLabel.setText(memory + " MB");
        });
        memoryValueLabel.setText(alignMemory(config.getMaxMemoryMb()) + " MB");
        list.getChildren().add(optionItem("最大内存", "分配给游戏的 -Xmx 堆上限",
                memorySlider, memoryValueLabel));

        minMemorySlider = buildMemorySlider(config.getMinMemoryMb());
        minMemoryValueLabel = new Label();
        minMemoryValueLabel.getStyleClass().add("value-label");
        minMemorySlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            int memory = alignMemory(newValue.intValue());
            config.setMinMemoryMb(memory);
            minMemoryValueLabel.setText(memory + " MB");
        });
        minMemoryValueLabel.setText(alignMemory(config.getMinMemoryMb()) + " MB");
        list.getChildren().add(optionItem("最小内存", "启动时预分配的 -Xms 堆下限",
                minMemorySlider, minMemoryValueLabel));

        usernameField = new TextField(config.getUsername());
        usernameField.setPrefWidth(220);
        usernameField.textProperty().addListener((obs, oldValue, newValue) -> {
            config.setUsername(newValue == null ? "Player" : newValue.trim());
            persistOfflineQuietly();
            updateAccountCard();
        });
        list.getChildren().add(optionItem("离线用户名", "离线模式登录时使用的玩家名（保存在 "
                + AuthPaths.offlineFile() + "）",
                usernameField));

        clientIdField = new TextField(config.getClientId() == null ? "" : config.getClientId());
        clientIdField.setPromptText(MicrosoftAuthService.DEFAULT_CLIENT_ID);
        clientIdField.setPrefWidth(320);
        clientIdField.textProperty().addListener((obs, oldValue, newValue) ->
                config.setClientId(newValue == null ? "" : newValue.trim()));
        list.getChildren().add(optionItem("正版登录应用 ID",
                "Azure 应用 client_id；留空使用内置公开值 " + MicrosoftAuthService.DEFAULT_CLIENT_ID
                        + "，建议填写自己注册的 Azure 应用 ID",
                clientIdField));

        Button themeButton = new Button();
        themeButton.getStyleClass().add("button-border");
        themeButton.setOnAction(event -> {
            toggleTheme();
            themeButton.setText(darkMode ? "切换为浅色" : "切换为深色");
        });
        themeButton.setText(darkMode ? "切换为浅色" : "切换为深色");
        list.getChildren().add(optionItem("外观主题", "浅色 / 深色均基于同一套 -monet-* 变量",
                themeButton));

        defaultIsolationBox = new CheckBox("新实例默认开启版本隔离");
        defaultIsolationBox.setSelected(config.isDefaultVersionIsolation());
        defaultIsolationBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            config.setDefaultVersionIsolation(newValue);
            saveConfigQuietly();
        });
        list.getChildren().add(optionItem("版本隔离",
                "开启后新实例的模组 / 存档 / 资源包默认独立存放于实例目录，互不干扰",
                defaultIsolationBox));

        List<Node> items = list.getChildren();
        if (!items.isEmpty()) {
            items.get(0).getStyleClass().add("first");
            items.get(items.size() - 1).getStyleClass().add("last");
            if (items.size() == 1) {
                items.get(0).getStyleClass().add("last");
            }
        }

        Label hintText = new Label("提示：运行设置保存到 %USERPROFILE%\\.mclqe\\config.json，"
                + "正版账户凭证保存于 " + AuthPaths.msaFile()
                + "，非正版（离线）账户凭证保存于 " + AuthPaths.offlineFile() + "。");
        hintText.getStyleClass().add("card-subtitle");
        hintText.setWrapText(true);

        VBox page = new VBox(10, pageTitle("设置"), pageSubtitle("启动器与游戏运行参数"), list, hintText);
        page.getStyleClass().add("page");

        ScrollPane scroll = new ScrollPane(page);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFitToWidth(true);
        return scroll;
    }

    private Slider buildMemorySlider(int current) {
        Slider slider = new Slider(MEMORY_MIN_MB, MEMORY_MAX_MB, alignMemory(current));
        slider.setBlockIncrement(MEMORY_STEP_MB);
        slider.setMajorTickUnit(MEMORY_STEP_MB);
        slider.setPrefWidth(260);
        slider.setShowTickMarks(false);
        slider.setShowTickLabels(false);
        return slider;
    }

    private static int alignThreads(int value) {
        return Math.min(THREADS_MAX, Math.max(THREADS_MIN, value));
    }

    private static int alignMemory(int value) {
        int aligned = Math.round(value / (float) MEMORY_STEP_MB) * MEMORY_STEP_MB;
        return Math.min(MEMORY_MAX_MB, Math.max(MEMORY_MIN_MB, aligned));
    }

    private HBox optionItem(String title, String subtitle, Node... controls) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("option-title");
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.getStyleClass().add("option-subtitle");
        subtitleLabel.setWrapText(true);

        VBox text = new VBox(2, titleLabel, subtitleLabel);
        text.setMinWidth(200);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox item = new HBox(12, text);
        item.getStyleClass().add("options-list-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.getChildren().addAll(controls);
        return item;
    }

    // ================================================================== 状态栏

    private HBox buildStatusBar() {
        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().add("status-text");

        progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("download-progress");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label build = new Label("MCLQE 1.0.0");
        build.getStyleClass().add("status-text");

        HBox bar = new HBox(12, statusLabel, spacer, progressBar, build);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ================================================================== 启动与停止
    private void onLaunchButton() {
        if (gameLauncher != null && (gameLauncher.isRunning() || gameLauncher.isPreparing())) {
            gameLauncher.stop();
            appendLog("正在停止游戏进程 ...");
            setStatus("正在停止游戏 ...");
            return;
        }
        startGame();
    }

    private void showLaunchMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem choose = new MenuItem("选择游戏版本 ...");
        choose.setOnAction(event -> showPage(PAGE_VERSIONS));

        MenuItem refresh = new MenuItem("刷新版本列表");
        refresh.setOnAction(event -> refreshVersionsAsync(true));

        MenuItem gameDir = new MenuItem("打开游戏目录");
        gameDir.setOnAction(event -> openGameDir());

        MenuItem settings = new MenuItem("打开设置 ...");
        settings.setOnAction(event -> showPage(PAGE_SETTINGS));

        menu.getItems().addAll(choose, refresh, new SeparatorMenuItem(), gameDir, settings);
        menu.show(launchMenuButton, Side.BOTTOM, 0, 4);
    }

    private void startGame() {
        if (selectedVersion == null) {
            appendLog("请先选择游戏版本");
            setStatus("尚未选择版本");
            showPage(PAGE_VERSIONS);
            return;
        }
        if (gameLauncher != null && (gameLauncher.isRunning() || gameLauncher.isPreparing())) {
            appendLog("已有启动流程进行中");
            return;
        }

        applyConfigFromUi();
        config.setVersionId(selectedVersion.getId());
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }

        gamePaths = new GamePaths(Path.of(config.getGameDir()));
        try {
            Files.createDirectories(gamePaths.root());
        } catch (IOException e) {
            appendLog("无法创建游戏目录: " + e.getMessage());
            return;
        }

        GameLauncher launcher = new GameLauncher(gamePaths, this::appendLog,
                (done, total, current) -> Platform.runLater(() -> updateProgress(done, total, current)));
        launcher.addExitSink(code -> Platform.runLater(() -> onGameExit(code)));
        this.gameLauncher = launcher;

        setLaunching(true);
        progressBar.setProgress(0);
        setStatus("正在准备启动 " + selectedVersion.getId() + " ...");

        final MicrosoftAccount msa = currentMsa;
        Thread thread = new Thread(() -> {
            AuthAccount account;
            try {
                account = prepareLaunchAccount(msa);
            } catch (AuthException e) {
                appendLog("正版凭据刷新失败: " + e.getMessage());
                Platform.runLater(() -> {
                    setLaunching(false);
                    setStatus("登录已失效，请重新登录");
                    showError("登录已失效，请重新登录", e.getMessage());
                });
                return;
            }
            try {
                launcher.launch(config, account);
                // 游戏进程已拉起：标记会话运行中，此后一律不允许刷新正版凭据
                gameSessionActive = true;
                Platform.runLater(() -> setStatus("游戏运行中"));
            } catch (Exception e) {
                appendLog("启动失败: " + e);
                gameSessionActive = false;
                Platform.runLater(() -> {
                    setStatus("启动失败");
                    setLaunching(false);
                });
            }
        }, "mcqe-launch");
        thread.setDaemon(true);
        thread.start();
    }

    /** 切换启动按钮的「启动 / 停止」形态（停止态使用 error 配色，与 HMCL 一致）。 */
    private void setLaunching(boolean launching) {
        if (launching) {
            launchButton.setText("停止游戏");
            launchButton.setGraphic(Icons.of("icon-stop", 18));
            if (!launchButton.getStyleClass().contains("stopping")) {
                launchButton.getStyleClass().add("stopping");
            }
        } else {
            launchButton.setText("启动游戏");
            launchButton.setGraphic(Icons.of("icon-play", 18));
            launchButton.getStyleClass().remove("stopping");
        }
    }

    private void onGameExit(int exitCode) {
        // 游戏已退出，解除会话锁，后续启动才允许刷新正版凭据
        gameSessionActive = false;
        setLaunching(false);
        progressBar.setProgress(0);
        setStatus("游戏已退出（退出码 " + exitCode + "）");
    }

    // ================================================================== 正版（Microsoft）账户
    /**
     * 启动前准备账户：正版账户在过期或距到期不足 12 小时时自动 refresh。
     *
     * <p>游戏运行中（{@link #gameSessionActive}）绝不刷新，避免让正在运行的游戏会话失效；
     * 刷新失败抛 {@link AuthException}，由调用方阻断启动并提示重新登录，
     * 且失败时账户自身的 refresh_token 等字段保持不变。</p>
     */
    private AuthAccount prepareLaunchAccount(MicrosoftAccount msa) throws AuthException {
        if (msa == null) {
            return OfflineAccount.of(offlineStore.getUsername(), offlineStore.getUuid());
        }
        if (!msa.needsRefresh()) {
            return msa;
        }
        if (gameSessionActive) {
            // 会话仍在运行：沿用现有令牌，宁可本次不续期也不能中断游戏
            appendLog("游戏运行中，跳过正版凭据刷新（避免中断游戏会话）");
            return msa;
        }
        appendLog(msa.isExpired() ? "正版登录凭据已过期，正在重新获取 ..." : "正版登录凭据将在 12 小时内过期，正在刷新 ...");
        msa.refresh(new MicrosoftAuthService());
        Platform.runLater(this::persistAccounts);
        appendLog("正版登录凭据刷新完成: " + msa.getUsername());
        return msa;
    }

    /** 当前生效的 Azure 应用 ID：设置页留空时回退到内置公开值。 */
    private String currentClientId() {
        return MicrosoftAuthService.normalizeClientId(config.getClientId());
    }

    private void persistAccounts() {
        try {
            accountStore.save();
        } catch (IOException e) {
            appendLog("账户文件保存失败: " + e.getMessage());
        }
    }

    /** 把当前离线账户写回 D:\MCLQE\SA\offline.json（失败只记日志，不阻塞 UI）。 */
    private void persistOfflineQuietly() {
        try {
            // 离线 UUID 恒由官方算法按用户名派生，改名后一并刷新
            offlineStore.updateUsername(config.getUsername());
            config.setUuid(offlineStore.getUuid());
        } catch (IOException e) {
            appendLog("离线账户文件保存失败: " + e.getMessage());
        }
    }

    private void onAccountAction() {
        if (accountStore.microsoftAccounts().isEmpty()) {
            openMicrosoftLogin();
        } else {
            showAccountDialog();
        }
    }

    /** 账户管理：切换已保存的正版账户 / 添加新账户 / 切回离线。 */
    private void showAccountDialog() {
        List<MicrosoftAccount> accounts = accountStore.microsoftAccounts();
        if (accounts.isEmpty()) {
            openMicrosoftLogin();
            return;
        }
        ComboBox<String> box = new ComboBox<>();
        for (MicrosoftAccount account : accounts) {
            box.getItems().add(account.getUsername() + "  ·  " + account.getUuid());
        }
        box.setPrefWidth(360);
        MicrosoftAccount selected = currentMsa != null ? currentMsa : accountStore.selectedAccount();
        int selectedIndex = 0;
        for (int i = 0; i < accounts.size(); i++) {
            if (selected != null && selected.getUuid().equalsIgnoreCase(accounts.get(i).getUuid())) {
                selectedIndex = i;
            }
        }
        box.getSelectionModel().select(selectedIndex);

        Label hint = new Label("切换到正版账户后启动游戏将使用正版身份；切回离线不会删除已保存的正版记录。");
        hint.setWrapText(true);
        hint.getStyleClass().add("option-subtitle");
        VBox content = new VBox(8, new Label("已保存的正版账户"), box, hint);
        content.setPrefWidth(420);

        ButtonType switchType = new ButtonType("使用该账户", ButtonBar.ButtonData.OK_DONE);
        ButtonType addType = new ButtonType("添加账户", ButtonBar.ButtonData.OTHER);
        ButtonType offlineType = new ButtonType("切回离线", ButtonBar.ButtonData.OTHER);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(primaryStage);
        alert.setTitle("账户管理");
        alert.setHeaderText("账户管理");
        DialogPane pane = alert.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().setAll(switchType, addType, offlineType, cancelType);
        styleDialog(pane);

        Button addButton = (Button) pane.lookupButton(addType);
        addButton.setOnAction(event -> {
            alert.close();
            openMicrosoftLogin();
        });
        Button offlineButton = (Button) pane.lookupButton(offlineType);
        offlineButton.setOnAction(event -> {
            alert.close();
            switchToOffline();
        });

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() != switchType) {
            return;
        }
        int index = box.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= accounts.size()) {
            return;
        }
        MicrosoftAccount account = accounts.get(index);
        currentMsa = account;
        accountStore.select(account);
        persistAccounts();
        updateAccountCard();
        appendLog("已切换到正版账户: " + account.getUsername() + " · " + account.getUuid());
        setStatus("当前账户：" + account.getUsername());
    }

    /** 切回离线账户：仅取消选中，accounts.json 中的正版记录与令牌保持不变。 */
    private void switchToOffline() {
        currentMsa = null;
        accountStore.clearSelection();
        persistAccounts();
        updateAccountCard();
        appendLog("已切回离线账户（正版记录已保留）");
        setStatus("当前账户：离线（" + config.getUsername() + "）");
    }

    private void logoutMicrosoft() {
        switchToOffline();
    }

    /** 设备码登录弹窗：申请设备码 → 展示验证码 → 后台轮询 → 成功后自动切换账户。 */
    private void openMicrosoftLogin() {
        if (authService != null) {
            appendLog("已有登录流程进行中，请稍候");
            return;
        }
        final MicrosoftAuthService[] serviceRef = {mockLogin ? null : new MicrosoftAuthService()};
        authService = serviceRef[0];
        if (accountActionButton != null) {
            accountActionButton.setDisable(true);
        }

        Label instruction = new Label("1. 打开验证网址\u30002. 输入一次性验证码\u30003. 用 Microsoft 账户完成授权");
        instruction.setWrapText(true);
        instruction.getStyleClass().add("option-subtitle");

        Label codeLabel = new Label("---- ----");
        codeLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        Label uriLabel = new Label("正在申请设备码 ...");
        uriLabel.setWrapText(true);

        Hyperlink openLink = new Hyperlink("在浏览器中打开验证页面");
        openLink.setDisable(true);

        Label ttlLabel = new Label("");
        Label statusHint = new Label("正在向 Microsoft 申请设备码，请稍候 ...");
        statusHint.setWrapText(true);
        statusHint.getStyleClass().add("option-subtitle");

        VBox content = new VBox(8, instruction, codeLabel, uriLabel, openLink, ttlLabel, statusHint);
        content.setPrefWidth(440);

        ButtonType openType = new ButtonType("打开浏览器", ButtonBar.ButtonData.OTHER);
        ButtonType copyType = new ButtonType("复制验证码", ButtonBar.ButtonData.OTHER);
        ButtonType retryType = new ButtonType("重试", ButtonBar.ButtonData.OTHER);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(primaryStage);
        alert.setTitle("Microsoft 正版登录");
        alert.setHeaderText("使用 Microsoft 账户登录（设备码流程）");
        DialogPane pane = alert.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().setAll(openType, copyType, retryType, cancelType);
        styleDialog(pane);

        Button openButton = (Button) pane.lookupButton(openType);
        openButton.setDisable(true);
        Button copyButton = (Button) pane.lookupButton(copyType);
        copyButton.setDisable(true);
        Button retryButton = (Button) pane.lookupButton(retryType);
        retryButton.setDisable(true);

        final String[] verificationUri = {""};
        final boolean[] finished = {false};
        // 失败后等待用户指令："retry" 重新发起一轮设备码登录，"cancel" 结束等待
        final BlockingQueue<String> commands = new LinkedBlockingQueue<>();

        openButton.setOnAction(event -> openVerificationUri(verificationUri[0], statusHint));
        openLink.setOnAction(event -> openVerificationUri(verificationUri[0], statusHint));
        copyButton.setOnAction(event -> {
            ClipboardContent clip = new ClipboardContent();
            clip.putString(codeLabel.getText());
            Clipboard.getSystemClipboard().setContent(clip);
            statusHint.setText("验证码已复制到剪贴板");
        });
        retryButton.setOnAction(event -> {
            retryButton.setDisable(true);
            openButton.setDisable(true);
            copyButton.setDisable(true);
            commands.offer("retry");
        });
        alert.setOnHidden(event -> {
            stopLoginCountdown();
            // 通知后台线程结束等待，避免失败后就地阻塞
            commands.offer("cancel");
            if (!finished[0] && authService != null) {
                authService.cancel();
            }
        });

        if (mockLogin) {
            codeLabel.setText("MN7Q-K4TR");
            uriLabel.setText("https://microsoft.com/link");
            verificationUri[0] = "https://microsoft.com/link";
            openButton.setDisable(false);
            openLink.setDisable(false);
            copyButton.setDisable(false);
            startLoginCountdown(ttlLabel, 892);
            statusHint.setText("截图演示模式：未发起任何网络请求");
            alert.show();
            return;
        }

        final String clientId = currentClientId();
        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    MicrosoftAuthService service = serviceRef[0];
                    if (service == null) {
                        return;
                    }
                    try {
                        MicrosoftAuthService.DeviceCode deviceCode = service.requestDeviceCode(clientId);
                        verificationUri[0] = deviceCode.getVerificationUri();
                        Platform.runLater(() -> {
                            codeLabel.setText(deviceCode.getUserCode());
                            uriLabel.setText(deviceCode.getVerificationUri());
                            openButton.setDisable(false);
                            openLink.setDisable(false);
                            copyButton.setDisable(false);
                            startLoginCountdown(ttlLabel, deviceCode.getExpiresIn());
                            statusHint.setText("等待授权：输入验证码并确认后，本窗口会自动继续 ...");
                        });
                        appendLog("已获取设备码，请在 " + deviceCode.getVerificationUri()
                                + " 输入 " + deviceCode.getUserCode());
                        MicrosoftAccount account = service.login(deviceCode, clientId,
                                stage -> Platform.runLater(() -> statusHint.setText(stage)));
                        Platform.runLater(() -> finishMicrosoftLogin(account, alert, finished));
                        return;
                    } catch (AuthException e) {
                        if (service.isCancelled()) {
                            return;
                        }
                        final String reason = (e.getMessage() == null || e.getMessage().isBlank())
                                ? "未知错误" : e.getMessage();
                        Platform.runLater(() -> {
                            stopLoginCountdown();
                            statusHint.setText("登录失败：" + reason + "\n可直接点「重试」重新发起登录，或点「取消」关闭本窗口。");
                            retryButton.setDisable(false);
                            appendLog("正版登录失败: " + reason);
                            setStatus("正版登录失败：" + reason);
                        });
                        // 等待用户决定：重试或关闭窗口（关闭时 setOnHidden 会投递 cancel）
                        String command = commands.take();
                        if (!"retry".equals(command)) {
                            return;
                        }
                        // 取消标记与 service 实例绑定，重试必须换一个干净的实例
                        serviceRef[0] = new MicrosoftAuthService();
                        authService = serviceRef[0];
                        Platform.runLater(() -> {
                            stopLoginCountdown();
                            codeLabel.setText("---- ----");
                            uriLabel.setText("正在重新申请设备码 ...");
                            ttlLabel.setText("");
                            statusHint.setText("正在重新向 Microsoft 申请设备码，请稍候 ...");
                        });
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusHint.setText("登录异常：" + e.getMessage());
                    appendLog("正版登录异常: " + e.getMessage());
                    setStatus("正版登录异常：" + e.getMessage());
                });
            } finally {
                Platform.runLater(() -> {
                    if (authService == serviceRef[0]) {
                        authService = null;
                    }
                    if (accountActionButton != null) {
                        accountActionButton.setDisable(false);
                    }
                    updateAccountCard();
                });
            }
        }, "mcqe-msa-login");
        worker.setDaemon(true);
        worker.start();

        alert.showAndWait();
    }

    /**
     * 登录成功后的统一收尾：落盘 → 刷新账户列表 / 主页账户卡片 / 问候语 / 账户页副标题，
     * 并把界面切到账户页，保证用户关闭登录弹窗后立刻能看到正版账户。
     */
    private void finishMicrosoftLogin(MicrosoftAccount account, Alert alert, boolean[] finished) {
        finished[0] = true;
        stopLoginCountdown();
        accountStore.upsert(account);
        accountStore.select(account);
        persistAccounts();
        currentMsa = account;
        updateAccountCard();
        renderAccounts();
        if (greetingLabel != null) {
            greetingLabel.setText("你好，" + playerName());
        }
        try {
            showPage(PAGE_ACCOUNTS);
        } catch (RuntimeException e) {
            appendLog("切换账户页失败（账户已登录）: " + e.getMessage());
        }
        appendLog("正版登录成功: " + account.getUsername() + " · " + account.getUuid());
        setStatus("已登录正版账户 " + account.getUsername());
        alert.close();
    }

    private void startLoginCountdown(Label label, int seconds) {
        stopLoginCountdown();
        final long deadline = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            long remain = Math.max(0, (deadline - System.currentTimeMillis()) / 1000);
            if (remain <= 0) {
                label.setText("设备码已过期，请取消后重新发起登录");
            } else {
                label.setText(String.format("设备码有效期剩余 %02d:%02d", remain / 60, remain % 60));
            }
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        loginCountdown = timeline;
    }

    private void stopLoginCountdown() {
        if (loginCountdown != null) {
            loginCountdown.stop();
            loginCountdown = null;
        }
    }

    private void openVerificationUri(String uri, Label statusHint) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(uri));
            } else {
                getHostServices().showDocument(uri);
            }
        } catch (Exception e) {
            statusHint.setText("无法自动打开浏览器，请手动访问：" + uri);
        }
    }

    // ================================================================== 目录管理页
    private VBox buildDirsPage() {
        currentDirLabel = new Label();
        currentDirLabel.getStyleClass().add("path-value");
        currentDirLabel.setWrapText(true);
        currentDirLabel.setMinWidth(0);
        currentDirLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentDirLabel, Priority.ALWAYS);

        Button switchDirButton = new Button("切换目录 ...");
        switchDirButton.setMinWidth(Region.USE_PREF_SIZE);
        switchDirButton.getStyleClass().add("button-border");
        switchDirButton.setOnAction(event -> chooseGameDir());

        HBox currentCard = new HBox(10, Icons.of("icon-folder", 18), currentDirLabel, switchDirButton);
        currentCard.getStyleClass().add("card");
        currentCard.setAlignment(Pos.CENTER_LEFT);

        // 默认目录提示：与设置页保持一致（固定在启动器所在盘符的 MCLQE 目录下）
        Label currentDefaultHint = new Label(LauncherPaths.defaultGameDirHint());
        currentDefaultHint.getStyleClass().add("card-subtitle");
        currentDefaultHint.setWrapText(true);
        VBox currentCardBox = new VBox(6, currentCard, currentDefaultHint);

        // ---------------- 已添加的游戏目录（多目录：切换 / 打开 / 移除） ----------------
        Label addedDirsTitle = new Label("已添加的游戏目录");
        addedDirsTitle.getStyleClass().add("card-title");
        Button addExistingDirButton = new Button("添加已有目录 ...");
        addExistingDirButton.setGraphic(Icons.of("icon-plus", 14));
        addExistingDirButton.getStyleClass().add("button-border");
        addExistingDirButton.setOnAction(event -> addGameDir());
        Region addedDirsSpacer = new Region();
        HBox.setHgrow(addedDirsSpacer, Priority.ALWAYS);
        HBox addedDirsHeader = new HBox(8, Icons.of("icon-folder", 16), addedDirsTitle,
                addedDirsSpacer, addExistingDirButton);
        addedDirsHeader.setAlignment(Pos.CENTER_LEFT);

        gameDirListBox = new VBox(8);
        VBox addedDirsCard = new VBox(8, addedDirsHeader, gameDirListBox);
        addedDirsCard.getStyleClass().add("card");

        dirList = new ListView<>();
        dirList.getStyleClass().add("version-list");
        dirList.setCellFactory(view -> new DirListCell());
        dirList.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) ->
                updateDirActionState());
        dirList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedDir();
            }
        });
        VBox.setVgrow(dirList, Priority.ALWAYS);

        ContextMenu dirMenu = new ContextMenu();
        MenuItem openItem = new MenuItem("打开文件夹");
        openItem.setOnAction(event -> openSelectedDir());
        MenuItem deleteItem = new MenuItem("删除文件夹（移入回收站）");
        deleteItem.setOnAction(event -> deleteSelectedFolder());
        dirMenu.getItems().addAll(openItem, new SeparatorMenuItem(), deleteItem);
        dirList.setContextMenu(dirMenu);

        openFolderButton = new Button("打开当前目录");
        openFolderButton.setGraphic(Icons.of("icon-open", 14));
        openFolderButton.getStyleClass().add("button-border");
        openFolderButton.setOnAction(event -> openGameDir());

        deleteFolderButton = new Button("删除文件夹");
        deleteFolderButton.setGraphic(Icons.of("icon-delete", 14));
        deleteFolderButton.getStyleClass().add("button-danger");
        deleteFolderButton.setOnAction(event -> deleteSelectedFolder());

        Button refreshDirsButton = new Button("刷新");
        refreshDirsButton.setGraphic(Icons.of("icon-refresh", 14));
        refreshDirsButton.getStyleClass().add("button-border");
        refreshDirsButton.setOnAction(event -> refreshDirList());

        Region listSpacer = new Region();
        HBox.setHgrow(listSpacer, Priority.ALWAYS);
        HBox listFooter = new HBox(10, openFolderButton, refreshDirsButton, listSpacer, deleteFolderButton);
        listFooter.setAlignment(Pos.CENTER_LEFT);

        newDirField = new TextField();
        newDirField.setPromptText("默认 " + LauncherPaths.defaultGameDir() + "（不存在则自动创建标准目录结构）");
        newDirField.getStyleClass().add("search-field");
        HBox.setHgrow(newDirField, Priority.ALWAYS);

        Button browseNewDir = new Button("浏览 ...");
        browseNewDir.getStyleClass().add("button-border");
        browseNewDir.setOnAction(event -> chooseNewDirParent());

        Button createDirButton = new Button("新建并切换");
        createDirButton.setGraphic(Icons.of("icon-plus", 14));
        createDirButton.getStyleClass().add("button-raised");
        createDirButton.setOnAction(event -> createNewGameDir());

        HBox newDirRow = new HBox(8, newDirField, browseNewDir, createDirButton);
        newDirRow.setAlignment(Pos.CENTER_LEFT);

        Label structureHint = new Label("将创建：versions / mods / saves / resourcepacks / shaderpacks / logs");
        structureHint.getStyleClass().add("card-subtitle");

        Label newDirTitle = new Label("新建游戏目录");
        newDirTitle.getStyleClass().add("card-title");
        VBox newDirCard = new VBox(8, newDirTitle, structureHint, newDirRow);
        newDirCard.getStyleClass().add("card");

        Label dirListTitle = new Label("游戏目录内容（versions 下的已安装版本会单独列出）");
        dirListTitle.getStyleClass().add("card-title");

        VBox page = new VBox(10, pageTitle("目录管理"),
                pageSubtitle("管理当前游戏目录及子文件夹；删除操作默认移入系统回收站，可随时还原"),
                currentCardBox, addedDirsCard, dirListTitle, dirList, listFooter, newDirCard);
        page.getStyleClass().add("page");
        return page;
    }

    /** 目录条目单元格：文件夹名 + 路径 / 大小 / 文件数 / 修改时间。 */
    private static class DirListCell extends ListCell<GameDirEntry> {
        private final Label title = new Label();
        private final Label kindTag = new Label();
        private final Label subtitle = new Label();
        private final HBox container;

        DirListCell() {
            title.getStyleClass().add("two-line-title");
            kindTag.getStyleClass().add("tag");
            subtitle.getStyleClass().add("two-line-subtitle");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox firstLine = new HBox(8, title, spacer, kindTag);
            firstLine.setAlignment(Pos.CENTER_LEFT);

            VBox text = new VBox(4, firstLine, subtitle);
            HBox.setHgrow(text, Priority.ALWAYS);

            container = new HBox(10, text);
            container.getStyleClass().add("two-line-list-item");
            container.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(GameDirEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            title.setText(entry.name());
            kindTag.getStyleClass().removeAll("tag-installed", "tag-local", "tag-missing");
            if (entry.versionDir()) {
                kindTag.setText("版本");
                kindTag.getStyleClass().add("tag-installed");
            } else if (entry.standard()) {
                kindTag.setText("标准");
                kindTag.getStyleClass().add("tag-local");
            } else {
                kindTag.setText("文件夹");
                kindTag.getStyleClass().add("tag-missing");
            }
            subtitle.setText(entry.path() + " · " + formatSize(entry.sizeBytes())
                    + " · " + entry.fileCount() + " 个文件 · 修改于 " + formatModified(entry.lastModified()));
            setGraphic(container);
            setText(null);
        }
    }

    /** 刷新目录内容（后台统计大小，结果回切 UI 线程）。 */
    private void refreshDirList() {
        if (dirList == null) {
            return;
        }
        Path root = Path.of(config.getGameDir());
        if (currentDirLabel != null) {
            currentDirLabel.setText("当前游戏目录：" + root);
        }
        updateDirActionState();
        if (!Files.isDirectory(root)) {
            dirList.getItems().clear();
            appendLog("当前游戏目录不存在：" + root + "，可在下方「新建游戏目录」中创建");
            return;
        }
        Thread thread = new Thread(() -> {
            List<GameDirEntry> entries = GameDirManager.listEntries(root);
            Platform.runLater(() -> {
                dirList.getItems().setAll(entries);
                updateDirActionState();
                appendLog("游戏目录内容已刷新，共 " + entries.size() + " 个文件夹");
            });
        }, "mcqe-dirs");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateDirActionState() {
        if (deleteFolderButton == null) {
            return;
        }
        deleteFolderButton.setDisable(dirList == null || dirList.getSelectionModel().getSelectedItem() == null);
    }

    private void openSelectedDir() {
        GameDirEntry entry = dirList == null ? null : dirList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            setStatus("请先在列表中选择一个文件夹");
            return;
        }
        openDirectory(entry.path());
    }

    private void openSelectedVersionFolder() {
        VersionManifest.VersionEntry entry = versionList == null ? null
                : versionList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            setStatus("请先在列表中选择一个版本");
            return;
        }
        Path dir = Path.of(config.getGameDir()).resolve("versions").resolve(entry.getId());
        if (!Files.isDirectory(dir)) {
            showError("无法打开", "该版本未安装到本地，版本文件夹不存在：\n" + dir);
            return;
        }
        openDirectory(dir);
    }

    // ================================================================== 删除（二次确认 + 回收站）

    private void deleteSelectedFolder() {
        GameDirEntry entry = dirList == null ? null : dirList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            setStatus("请先在列表中选择要删除的文件");
            return;
        }
        Path target = entry.path();
        StringBuilder detail = new StringBuilder();
        detail.append("即将删除以下文件夹（移入系统回收站，可从回收站还原）：\n\n")
                .append("· ").append(target).append("\n")
                .append("  大小 ").append(formatSize(entry.sizeBytes()))
                .append("，共 ").append(entry.fileCount()).append(" 个文件\n");
        if (entry.versionDir()) {
            detail.append("\n该文件夹是已安装版本 ").append(entry.versionId())
                    .append("，删除时会同时清理 versions 下同名的 ")
                    .append(entry.versionId()).append(".json / .jar 等文件。\n");
        }
        if (entry.standard() && !entry.versionDir()) {
            detail.append("\n注意：这是游戏的标准子目录，其中的模组 / 存档等数据删除后将无法由启动器恢复。\n");
        }
        if (!confirmDelete("删除文件夹确认", String.valueOf(entry.name()), detail.toString())) {
            setStatus("已取消删除：" + entry.name());
            return;
        }

        Path root = Path.of(config.getGameDir());
        String versionId = entry.versionId();
        boolean versionDir = entry.versionDir();
        setStatus("正在删除 " + entry.name() + " ...");
        Thread thread = new Thread(() -> {
            GameDirManager.DeleteResult result = versionDir
                    ? GameDirManager.deleteVersion(root, versionId)
                    : GameDirManager.deleteToTrash(target);
            Platform.runLater(() -> onDeleteFinished(result, entry.name()));
        }, "mcqe-delete-dir");
        thread.setDaemon(true);
        thread.start();
    }

    private void deleteSelectedVersion() {
        VersionManifest.VersionEntry entry = versionList == null ? null
                : versionList.getSelectionModel().getSelectedItem();
        if (entry == null) {
            setStatus("请先在列表中选择要删除的版本");
            return;
        }
        if (!entry.isInstalled()) {
            showError("无法删除", "版本 " + entry.getId() + " 未安装在本地，无需删除。");
            return;
        }
        String id = entry.getId();
        Path root = Path.of(config.getGameDir());
        Path versionDir = root.resolve("versions").resolve(id);
        StringBuilder detail = new StringBuilder();
        detail.append("即将删除已安装版本（移入系统回收站，可从回收站还原）：\n\n")
                .append("· 版本 ID：").append(id).append("\n")
                .append("· 版本文件夹：").append(versionDir).append("\n")
                .append("· 同时清理：versions 下同名的 ").append(id).append(".json / ")
                .append(id).append(".jar 等文件\n");
        if (entry.isLocalOnly()) {
            detail.append("\n注意：该版本仅存在于本地（官方清单中不存在），删除后无法通过启动器重新下载。\n");
        }
        if (!confirmDelete("删除版本确认", id, detail.toString())) {
            setStatus("已取消删除：" + id);
            return;
        }

        setStatus("正在删除版本 " + id + " ...");
        Thread thread = new Thread(() -> {
            GameDirManager.DeleteResult result = GameDirManager.deleteVersion(root, id);
            Platform.runLater(() -> {
                if (result.success() && config.getVersionId() != null && config.getVersionId().equals(id)) {
                    config.setVersionId("");
                    selectedVersion = null;
                    updateVersionCard();
                }
                onDeleteFinished(result, "版本 " + id);
            });
        }, "mcqe-delete-version");
        thread.setDaemon(true);
        thread.start();
    }

    private void onDeleteFinished(GameDirManager.DeleteResult result, String label) {
        appendLog(result.message());
        if (result.success()) {
            setStatus(label + " 已移入回收站");
            refreshVersionsAsync(false);
            refreshDirList();
        } else {
            setStatus("删除失败：" + result.message());
            showError("删除失败", result.message());
        }
    }

    /** 删除二次确认对话框：默认按钮为「取消」，确认按钮标红并明确写出「移入回收站」。 */
    private boolean confirmDelete(String title, String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(detail);
        ButtonType trashButton = new ButtonType("移入回收站", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(trashButton, cancelButton);
        DialogPane pane = alert.getDialogPane();
        styleDialog(pane);
        Node trashNode = pane.lookupButton(trashButton);
        if (trashNode != null) {
            trashNode.getStyleClass().add("danger");
        }
        if (demoMode) {
            // 演示模式：只展示确认框用于截图，不执行删除
            alert.show();
            return false;
        }
        java.util.Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == trashButton;
    }

    // ================================================================== 新建游戏目录

    private void chooseNewDirParent() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择新建游戏目录的位置");
        String text = newDirField.getText();
        if (text != null && !text.isBlank()) {
            Path path = Path.of(text.trim());
            Path parent = Files.isDirectory(path) ? path : path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }
        File selected = chooser.showDialog(primaryStage);
        if (selected != null) {
            newDirField.setText(Path.of(selected.getAbsolutePath(), ".minecraft").toString());
        }
    }

    private void createNewGameDir() {
        String text = newDirField.getText() == null ? "" : newDirField.getText().trim();
        // 从资源管理器「复制文件地址」粘贴来的路径会带首尾引号，先剥掉，避免被判成非法字符
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            showError("路径无效", "请先填写或选择要创建的游戏目录路径。");
            return;
        }
        Path target;
        try {
            target = Path.of(text);
        } catch (InvalidPathException e) {
            // Path.of 会直接拒绝 Windows 非法字符，这里转成可读提示
            showError("路径无效", "路径含有 Windows 不允许的字符（< > : \" | ? *）：\n" + text
                    + "\n\n请修改后重试，例如：D:\\Minecraft");
            return;
        } catch (Exception e) {
            showError("路径无效", "无法解析该路径：" + text + "\n" + e.getMessage());
            return;
        }
        String problem = GameDirManager.validateNewGameDir(target);
        if (problem != null) {
            showError("路径无效", problem);
            return;
        }
        boolean exists = Files.exists(target);
        String detail = "将创建（或补齐）标准游戏目录并切换为当前目录：\n\n"
                + "· 目标路径：" + target + "\n"
                + "· 目录结构：versions / mods / saves / resourcepacks / shaderpacks / logs\n"
                + (exists ? "\n该目录已存在，仅补齐缺失的标准子目录，不会删除其中任何文件。\n" : "");
        if (!confirmCreate(target.getFileName() == null ? target.toString() : target.getFileName().toString(),
                detail)) {
            setStatus("已取消新建游戏目录");
            return;
        }

        setStatus("正在创建游戏目录 ...");
        Thread thread = new Thread(() -> {
            try {
                List<Path> created = GameDirManager.createStandardStructure(target);
                Platform.runLater(() -> {
                    appendLog("已创建标准目录结构，共 " + (created.size() - 1) + " 个子目录：" + target);
                    applyGameDir(target);
                    newDirField.clear();
                    setStatus("游戏目录已创建并切换为：" + target);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("创建游戏目录失败: " + e.getMessage());
                    setStatus("创建游戏目录失败");
                    showError("创建失败", "无法创建游戏目录：\n" + target + "\n\n" + e.getMessage());
                });
            }
        }, "mcqe-newdir");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean confirmCreate(String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("新建游戏目录确认");
        alert.setHeaderText(header);
        alert.setContentText(detail);
        ButtonType createButton = new ButtonType("创建并切换", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(createButton, cancelButton);
        styleDialog(alert.getDialogPane());
        if (demoMode) {
            alert.show();
            return false;
        }
        java.util.Optional<ButtonType> choice = alert.showAndWait();
        return choice.isPresent() && choice.get() == createButton;
    }

    /** 切换当前游戏目录并刷新相关界面。 */
    private void applyGameDir(Path dir) {
        config.setGameDir(dir.toString());
        gamePaths = new GamePaths(dir);
        if (gameDirField != null) {
            gameDirField.setText(dir.toString());
        }
        try {
            configManager.save(config);
        } catch (IOException e) {
            appendLog("配置保存失败: " + e.getMessage());
        }
        refreshDirList();
        renderGameDirs();
        refreshVersionsAsync(false);
        appendLog("已切换游戏目录：" + dir);
    }

    // ================================================================== 对话框与通用工具

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(primaryStage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        styleDialog(alert.getDialogPane());
        alert.showAndWait();
    }

    /** 让对话框继承当前主题，并保证长路径可完整换行显示。 */
    private void styleDialog(DialogPane pane) {
        pane.setMinWidth(480);
        pane.setMaxWidth(600);
        URL stylesheet = getClass().getResource("/app.css");
        if (stylesheet != null) {
            pane.getStylesheets().add(stylesheet.toExternalForm());
        }
        Node content = pane.lookup(".content.label");
        if (content instanceof Label label) {
            label.setWrapText(true);
        }
        if (darkMode) {
            pane.getStyleClass().add("dark");
        }
    }

    /** 刷新下载源下拉与状态说明（自动优选结果变化或切换源后调用）。 */
    private void refreshSourceStatus() {
        if (sourceStatusLabel != null) {
            sourceStatusLabel.setText(DownloadSource.statusText());
        }
        if (sourceBox == null || !DownloadSource.isAuto() || refreshingSourceBox) {
            return;
        }
        DownloadSource value = sourceBox.getValue();
        if (value == null) {
            return;
        }
        // 重新设置选中值以刷新按钮文案（自动（已选：XXX x.xs）），期间不触发切换日志
        refreshingSourceBox = true;
        try {
            sourceBox.setValue(null);
            sourceBox.setValue(value);
        } finally {
            refreshingSourceBox = false;
        }
    }

    /** 演示模式：仅用于自动化截图验证，弹出确认对话框但不执行任何删除 / 创建。 */
    private void scheduleDemoFlow() {
        String mode = System.getProperty("mclqe.ui.demo", "");
        if ("login".equalsIgnoreCase(mode.trim())) {
            // 仅用于截图验证：用假设备码渲染登录弹窗（不发起任何网络请求、不写账户文件）
            mockLogin = true;
            long delay = 0L;
            try {
                delay = Long.parseLong(System.getProperty("mclqe.ui.demo.delay", "1200").trim());
            } catch (NumberFormatException ignored) {
            }
            final long startDelay = Math.max(0L, delay);
            Thread loginDemo = new Thread(() -> {
                sleep(startDelay);
                Platform.runLater(this::openMicrosoftLogin);
            }, "mcqe-demo-login");
            loginDemo.setDaemon(true);
            loginDemo.start();
            return;
        }
        if ("msa".equalsIgnoreCase(mode.trim())) {
            // 仅用于截图验证：注入一个假的正版账户渲染账户区（不读写 accounts.json、不发网络请求）
            currentMsa = new com.mclqe.launcher.auth.MicrosoftAccount(
                    "Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                    "demo-token", "demo-refresh", "2535405271447429",
                    com.mclqe.launcher.auth.MicrosoftAuthService.DEFAULT_CLIENT_ID, System.currentTimeMillis() + 86_400_000L);
            Thread msaDemo = new Thread(() -> {
                sleep(600);
                Platform.runLater(this::updateAccountCard);
            }, "mcqe-demo-msa");
            msaDemo.setDaemon(true);
            msaDemo.start();
            return;
        }
        if ("source".equalsIgnoreCase(mode.trim())) {
            // 仅用于截图验证：打开设置页、等待自动优选探测完成（不修改任何配置值）后展开「下载源」下拉
            // -Dmclqe.ui.demo.delay=<毫秒> 可延后展开，便于窗口就位后再截图
            long delay = 0L;
            try {
                delay = Long.parseLong(System.getProperty("mclqe.ui.demo.delay", "0").trim());
            } catch (NumberFormatException ignored) {
            }
            Platform.runLater(() -> showPage(PAGE_SETTINGS));
            final long startDelay = Math.max(0L, delay);
            Thread probeWait = new Thread(() -> {
                if (startDelay > 0) {
                    sleep(startDelay);
                }
                DownloadSource.awaitProbe(8_000);
                Platform.runLater(() -> {
                    refreshSourceStatus();
                    if (sourceBox != null) {
                        sourceBox.show();
                    }
                });
            }, "mcqe-demo-probe");
            probeWait.setDaemon(true);
            probeWait.start();
            return;
        }
        Thread thread = new Thread(() -> {
            sleep(3500);
            Platform.runLater(() -> {
                if ("delete".equalsIgnoreCase(mode.trim())) {
                    showPage(PAGE_VERSIONS);
                    selectFirstInstalledVersion();
                    deleteSelectedVersion();
                } else {
                    showPage(PAGE_DIRS);
                    if (newDirField != null) {
                        newDirField.setText(Path.of(System.getProperty("java.io.tmpdir"), "MCLQE-Demo")
                                .toString());
                    }
                    createNewGameDir();
                }
            });
        }, "mcqe-demo");
        thread.setDaemon(true);
        thread.start();
    }

    private void selectFirstInstalledVersion() {
        if (versionList == null) {
            return;
        }
        for (VersionManifest.VersionEntry entry : versionList.getItems()) {
            if (entry.isInstalled()) {
                versionList.getSelectionModel().select(entry);
                versionList.scrollTo(entry);
                return;
            }
        }
        setStatus("本地没有已安装版本，无法演示删除流程");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 人类可读的文件大小。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 目录修改时间（yyyy-MM-dd HH:mm）。 */
    private static String formatModified(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        java.time.LocalDateTime time = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(millis),
                java.time.ZoneId.systemDefault());
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d",
                time.getYear(), time.getMonthValue(), time.getDayOfMonth(),
                time.getHour(), time.getMinute());
    }

    // ================================================================== 后台任务

    private void chooseGameDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择游戏目录");
        File current = new File(gameDirField.getText() == null ? "" : gameDirField.getText());
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(primaryStage);
        if (selected != null) {
            gameDirField.setText(selected.getAbsolutePath());
            config.setGameDir(selected.getAbsolutePath());
            gamePaths = new GamePaths(selected.toPath());
            appendLog("游戏目录已切换为: " + selected.getAbsolutePath());
        }
    }

    private void detectJavaAsync() {
        setStatus("正在检测本地 Java 运行时 ...");
        Thread thread = new Thread(() -> {
            List<JavaDetector.JavaInfo> list = JavaDetector.detectAll();
            Platform.runLater(() -> {
                javaBox.getItems().setAll(list);
                if (list.isEmpty()) {
                    appendLog("未检测到任何 Java 运行时，请在「设置 - Java 运行时」中手动指定");
                    setStatus("未检测到 Java");
                    return;
                }
                JavaDetector.JavaInfo matched = null;
                if (config.getJavaPath() != null && !config.getJavaPath().isBlank()) {
                    for (JavaDetector.JavaInfo info : list) {
                        if (info.getJavaExecutable().toString().equalsIgnoreCase(config.getJavaPath())) {
                            matched = info;
                            break;
                        }
                    }
                }
                javaBox.getSelectionModel().select(matched != null ? matched : list.get(0));
                appendLog("共检测到 " + list.size() + " 个 Java 运行时");
                hintMismatchedManualJava(list, matched);
                setStatus("就绪");
            });
        }, "mcqe-detect-java");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 手动指定的 Java 与当前实例要求不符时，提示启动会自动改用更合适的候选，
     * 不修改用户配置，保持手动优先策略透明可见。
     */
    private void hintMismatchedManualJava(List<JavaDetector.JavaInfo> list, JavaDetector.JavaInfo manual) {
        if (manual == null || gamePaths == null || config.getVersionId() == null
                || config.getVersionId().isBlank()) {
            return;
        }
        try {
            JavaRequirement requirement = JavaRequirement.resolve(
                    config.getVersionId(), gamePaths.versionJson(config.getVersionId()));
            int required = requirement.requiredMajor();
            if (required <= 0 || manual.getMajorVersion() == required) {
                return;
            }
            JavaDetector.JavaInfo better = JavaDetector.pickBest(list, required);
            if (better != null) {
                appendLog("提示：已选择的 Java " + manual.getVersion() + " 不满足实例要求（Java " + required
                        + "），启动时将自动改用 " + better.getVersion() + "（" + better.getHome() + "）");
            } else {
                appendLog("提示：已选择的 Java " + manual.getVersion() + " 不满足实例要求（Java " + required
                        + "），且未找到匹配版本，启动可能失败");
            }
        } catch (RuntimeException ignored) {
            // 版本 JSON 缺失等情况下不打扰用户，检测阶段会给出结论
        }
    }

    /**
     * 刷新版本列表：先在后台线程扫描本地游戏目录 versions 下已安装的版本（离线可用，
     * 解析各自版本 JSON 中含 inheritsFrom 的继承信息），再拉取官方清单并与之合并，
     * 官方清单获取失败时保留本地扫描结果。
     */
    private void refreshVersionsAsync(boolean forceDownload) {
        setStatus("正在扫描本地已安装版本 ...");
        Path root = Path.of(config.getGameDir());
        gamePaths = new GamePaths(root);
        Thread thread = new Thread(() -> {
            VersionManager versionManager = new VersionManager(gamePaths, this::appendLog);
            List<LocalVersion> locals;
            try {
                locals = versionManager.listLocalVersions();
            } catch (Exception e) {
                locals = new ArrayList<>();
                appendLog("本地版本扫描失败: " + e.getMessage());
            }
            final List<LocalVersion> localResult = locals;
            Platform.runLater(() -> {
                localVersions = new ArrayList<>(localResult);
                applyVersionEntries(buildEntriesFromLocal(localResult));
                appendLog("本地扫描完成，已安装 " + localResult.size() + " 个版本");
                setStatus("正在获取官方版本清单 ...");
            });

            try {
                VersionManifest manifest = versionManager.getManifest(forceDownload);
                List<VersionManifest.VersionEntry> remote = manifest.getVersions();
                Platform.runLater(() -> {
                    applyVersionEntries(mergeVersions(remote, localResult));
                    appendLog("版本列表加载完成，共 " + allVersions.size() + " 个版本（本地已安装 "
                            + localResult.size() + " 个）");
                    setStatus("就绪");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("获取官方版本清单失败: " + e.getMessage() + "（已保留本地扫描结果）");
                    setStatus("离线模式：仅显示本地已安装版本");
                });
            }
        }, "mcqe-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyVersionEntries(List<VersionManifest.VersionEntry> entries) {
        allVersions = new ArrayList<>(entries);
        filterVersions(versionSearchField == null ? "" : versionSearchField.getText());
        selectInitialVersion(allVersions);
    }

    private List<VersionManifest.VersionEntry> buildEntriesFromLocal(List<LocalVersion> locals) {
        List<VersionManifest.VersionEntry> list = new ArrayList<>();
        for (LocalVersion local : locals) {
            list.add(toEntry(local, true));
        }
        return list;
    }

    /** 把本地版本转换为列表条目。 */
    private static VersionManifest.VersionEntry toEntry(LocalVersion local, boolean localOnly) {
        VersionManifest.VersionEntry entry = new VersionManifest.VersionEntry(local.id());
        String type = local.type();
        entry.setType(type == null || type.isBlank() ? (localOnly ? "local" : "release") : type);
        entry.setInstalled(true);
        entry.setLocalOnly(localOnly);
        entry.setDamaged(local.damaged());
        entry.setIncomplete(local.missingJar());
        entry.setLocalNote(localNote(local));
        return entry;
    }

    /** 本地版本副标题：损坏原因 / 未安装原因 / 继承信息 / .jar 状态。 */
    private static String localNote(LocalVersion local) {
        String damage = local.damageReason();
        if (damage != null) {
            return "损坏：" + damage;
        }
        if (local.missingJar()) {
            return "未安装：缺少 " + local.id() + ".jar";
        }
        StringBuilder note = new StringBuilder("本地已安装");
        if (local.inheritsFrom() != null && !local.inheritsFrom().isBlank()) {
            note.append(" · 继承 ").append(local.inheritsFrom());
        }
        note.append(local.hasJar() ? " · 有 .jar" : " · 无 .jar");
        return note.toString();
    }

    /** 官方清单与本地扫描结果合并：官方条目带上「已安装」标记，官方没有的本地版本追加到末尾。 */
    private static List<VersionManifest.VersionEntry> mergeVersions(List<VersionManifest.VersionEntry> remote,
                                                                    List<LocalVersion> locals) {
        Map<String, LocalVersion> localMap = new LinkedHashMap<>();
        for (LocalVersion local : locals) {
            localMap.put(local.id(), local);
        }
        List<VersionManifest.VersionEntry> result = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        for (VersionManifest.VersionEntry entry : remote) {
            LocalVersion local = localMap.get(entry.getId());
            if (local != null) {
                entry.setInstalled(true);
                entry.setLocalOnly(false);
                entry.setDamaged(local.damaged());
                entry.setIncomplete(local.missingJar());
                entry.setLocalNote(localNote(local));
                matched.add(entry.getId());
            }
            result.add(entry);
        }
        for (LocalVersion local : locals) {
            if (!matched.contains(local.id())) {
                result.add(toEntry(local, true));
            }
        }
        return result;
    }

    private void selectInitialVersion(List<VersionManifest.VersionEntry> versions) {
        if (versions.isEmpty()) {
            return;
        }
        String target = config.getVersionId();
        if (target != null && !target.isBlank()) {
            for (VersionManifest.VersionEntry entry : versions) {
                if (target.equals(entry.getId())) {
                    selectedVersion = entry;
                    versionList.getSelectionModel().select(entry);
                    updateVersionCard();
                    return;
                }
            }
        }
        VersionManifest.VersionEntry first = versions.get(0);
        selectedVersion = first;
        versionList.getSelectionModel().select(first);
        config.setVersionId(first.getId());
        updateVersionCard();
    }

    private void openGameDir() {
        openDirectory(Path.of(config.getGameDir()));
    }

    private void openConfigDir() {
        openDirectory(ConfigManager.defaultConfigDir());
    }

    /** 打开凭据总目录 D:\MCLQE\SA（内含 STEV\accounts.json 与 offline.json）。 */
    private void openAuthDir() {
        openDirectory(AuthPaths.offlineDir());
    }

    private void openDirectory(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
            } else {
                appendLog("当前环境不支持打开资源管理器：" + path);
            }
        } catch (Exception e) {
            appendLog("打开目录失败: " + e.getMessage());
        }
    }

    // ================================================================== 配置 / 状态 / 日志

    private void applyConfigFromUi() {
        if (gameDirField != null && gameDirField.getText() != null) {
            config.setGameDir(gameDirField.getText().trim());
        }
        if (javaBox != null) {
            JavaDetector.JavaInfo java = javaBox.getValue();
            config.setJavaPath(java == null ? "" : java.getJavaExecutable().toString());
            config.setAutoDetectJava(java == null);
        }
        if (memorySlider != null) {
            config.setMaxMemoryMb(alignMemory((int) memorySlider.getValue()));
        }
        if (minMemorySlider != null) {
            config.setMinMemoryMb(alignMemory((int) minMemorySlider.getValue()));
        }
        if (usernameField != null && usernameField.getText() != null) {
            String name = usernameField.getText().trim();
            config.setUsername(name.isEmpty() ? "Player" : name);
            persistOfflineQuietly();
        }
        if (clientIdField != null && clientIdField.getText() != null) {
            config.setClientId(clientIdField.getText().trim());
        }
        if (sourceBox != null && sourceBox.getValue() != null) {
            config.setDownloadSource(sourceBox.getValue().key());
        }
        if (customSourceField != null && customSourceField.getText() != null) {
            config.setCustomSourceRoot(customSourceField.getText().trim());
        }
        if (selectedVersion != null) {
            config.setVersionId(selectedVersion.getId());
        }
        config.setDarkMode(darkMode);
    }

    private void updateProgress(int done, int total, String current) {
        if (total <= 0) {
            progressBar.setProgress(0);
            return;
        }
        progressBar.setProgress((double) done / (double) total);
        setStatus("下载中 " + done + "/" + total + "  " + current);
        if (done == total || done % 20 == 0) {
            appendLog("下载进度 " + done + "/" + total);
        }
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    /** 线程安全日志输出，并限制缓冲区大小。 */
    private void appendLog(String line) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> appendLog(line));
            return;
        }
        if (logArea == null) {
            return;
        }
        logArea.appendText(line + System.lineSeparator());
        int length = logArea.getLength();
        if (length > LOG_CHAR_LIMIT) {
            logArea.deleteText(0, Math.min(LOG_TRIM_SIZE, length));
        }
        logArea.positionCaret(logArea.getLength());
    }

    // ================================================================== 启动时 Java 检测

    /**
     * 启动 Java 检测（首帧之后异步执行）。
     *
     * <p>策略对标 HMCL / Prism：只在「启动器打开」和「用户主动点启动」时检查，检查本身放后台线程，
     * 判定结果只影响提示，不阻塞界面；即使检测失败也不影响启动器其余功能。</p>
     */
    private void runJavaCheck() {
        if (Boolean.getBoolean("mclqe.javacheck.skip")) {
            appendLog("启动 Java 检测：已通过 -Dmclqe.javacheck.skip 跳过");
            return;
        }
        if (javaCheckService == null) {
            javaCheckService = new JavaCheckService(config, configManager);
        }
        String versionId = config.getVersionId() == null ? "" : config.getVersionId().trim();
        if (versionId.isEmpty()) {
            appendLog("启动 Java 检测：尚未选择游戏版本，跳过本次检测");
            return;
        }
        if (!config.isJavaCheckEnabled()) {
            appendLog("启动 Java 检测：已在设置中关闭");
            return;
        }
        appendLog("启动 Java 检测：正在检查当前版本 " + versionId + " 所需的 Java 运行时 ...");
        javaCheckService.runAsync(versionId, gamePaths,
                result -> Platform.runLater(() -> handleJavaCheckResult(result, true)));
    }

    /** 处理检测结果：正常只记日志，异常按防打扰策略决定是否弹出提示卡。 */
    private void handleJavaCheckResult(JavaCheckService.Result result, boolean allowPrompt) {
        if (result == null) {
            return;
        }
        JavaRequirement requirement = result.requirement();
        String required = requirement == null ? "Java 21" : requirement.displayName();
        if (result.conclusion() == JavaCheckService.Conclusion.OK) {
            appendLog("启动 Java 检测通过：" + (result.matched() == null ? "未指定运行时" : result.matched().toString())
                    + " 满足 " + required);
            if (!result.detail().isBlank()) {
                appendLog("检测详情：" + result.detail());
            }
            return;
        }
        appendLog("启动 Java 检测未通过（" + result.conclusion() + "）：" + result.message());
        if (requirement != null) {
            appendLog("判定依据：" + requirement.source() + " · " + requirement.detail());
        }
        if (!result.detail().isBlank()) {
            appendLog("检测详情：" + result.detail());
        }
        if (!allowPrompt || !result.promptNeeded()) {
            if (!result.suppressReason().isBlank()) {
                appendLog("按防打扰策略本次不提示：" + result.suppressReason());
            }
            setStatus("Java 环境异常（已按策略跳过提示）");
            return;
        }
        // 强制模拟模式（-Dmclqe.javacheck.force）仅用于截图自验，不写入用户配置，
        // 以免自验污染真实的防打扰抑制窗口
        if (!JavaCheckService.isForcedMode()) {
            javaCheckService.markPrompted(result);
        }
        showJavaCheckPrompt(result);
    }

    /**
     * 展示 Java 检测提示卡（窗口内浮层，不使用阻塞式对话框）：
     * 缺 Java / 版本不符时给出下载页，3 秒后自动打开浏览器，并提供「取消自动打开」。
     */
    private void showJavaCheckPrompt(JavaCheckService.Result result) {
        if (sceneRoot == null) {
            return;
        }
        hideJavaCheckPrompt();
        JavaRequirement requirement = result.requirement();
        String required = requirement == null ? "Java 21" : requirement.displayName();
        String downloadUrl = result.downloadUrl();
        String backupUrl = result.backupDownloadUrl();
        String versionText = requirement == null || requirement.versionId().isBlank()
                ? "未选择版本" : requirement.versionId();

        Label badge = new Label(result.conclusion() == JavaCheckService.Conclusion.MISSING
                ? "未检测到 Java" : "Java 版本不符");
        badge.setStyle("-fx-background-color: -monet-error-container; -fx-text-fill: -monet-on-error-container;"
                + " -fx-background-radius: 999; -fx-padding: 3 12 3 12; -fx-font-size: 11px; -fx-font-weight: bold;");

        Label title = new Label(result.conclusion() == JavaCheckService.Conclusion.MISSING
                ? "需要安装 " + required : "需要 " + required + " 才能启动该版本");
        title.setStyle("-fx-font-size: 19px; -fx-font-weight: bold; -fx-text-fill: -monet-on-surface;");

        Label message = new Label(result.message());
        message.setWrapText(true);
        message.setMaxWidth(520);
        message.setStyle("-fx-font-size: 13px; -fx-text-fill: -monet-on-surface-variant;");

        Label detail = new Label("检测详情：" + (result.detail() == null || result.detail().isBlank()
                ? "—" : result.detail()));
        detail.setWrapText(true);
        detail.setMaxWidth(520);
        detail.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        Label basis = new Label("判定依据：" + (requirement == null ? "—"
                : requirement.source() + " · " + requirement.detail()));
        basis.setWrapText(true);
        basis.setMaxWidth(520);
        basis.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        Label target = new Label("版本：" + versionText + "    所需运行时：" + required
                + "    下载页：" + downloadUrl);
        target.setWrapText(true);
        target.setMaxWidth(520);
        target.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-primary;");

        Label countdown = new Label("将在 3 秒后自动打开浏览器下载页 ...");
        countdown.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        Button openButton = new Button("打开下载页");
        openButton.getStyleClass().add("button-raised");
        Button recheckButton = new Button("我已安装，重新检测");
        recheckButton.getStyleClass().add("button-border");
        Button laterButton = new Button("本次不再提示");
        laterButton.getStyleClass().add("button-text");
        Button neverButton = new Button("永久不再提示");
        neverButton.getStyleClass().add("button-text");
        Button cancelAutoButton = new Button("取消自动打开");
        cancelAutoButton.getStyleClass().add("button-text");
        Hyperlink backupLink = new Hyperlink("备用下载地址（微软 OpenJDK）");

        HBox actions = new HBox(10, openButton, recheckButton, cancelAutoButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox secondary = new HBox(8, laterButton, neverButton, backupLink);
        secondary.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox card = new VBox(12, badge, title, message, detail, basis, target, actions, secondary, countdown);
        card.setMaxWidth(600);
        card.setPrefWidth(600);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: -monet-surface-container-lowest; -fx-background-radius: 18;"
                + " -fx-padding: 24; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 28, 0.18, 0, 10);");
        VBox.setMargin(badge, new Insets(0, 0, 2, 0));

        StackPane overlay = new StackPane(card);
        StackPane.setAlignment(card, Pos.CENTER);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 12;");
        overlay.getStyleClass().add("java-check-overlay");
        this.javaCheckOverlay = overlay;
        sceneRoot.getChildren().add(overlay);

        // 取消自动打开：停止倒计时与自动跳转
        cancelAutoButton.setOnAction(event -> {
            stopJavaCheckTimers();
            countdown.setText("已取消自动打开，可点击「打开下载页」手动前往");
        });

        // 打开下载页：调用系统浏览器；-Dmclqe.javacheck.noBrowse=true 时只打印地址（自验用）
        openButton.setOnAction(event -> {
            openJavaDownloadPage(downloadUrl);
            hideJavaCheckPrompt();
        });

        backupLink.setOnAction(event -> openJavaDownloadPage(backupUrl));

        // 本次不再提示：抑制窗口已在 markPrompted 中写入
        laterButton.setOnAction(event -> {
            appendLog("已选择本次不再提示（24 小时内不再重复提醒同一版本）");
            hideJavaCheckPrompt();
        });

        // 永久不再提示：写入配置，设置页可恢复
        neverButton.setOnAction(event -> {
            javaCheckService.disableForever();
            if (javaCheckBox != null) {
                javaCheckBox.setSelected(false);
            }
            appendLog("已永久关闭 Java 检测提示，可在「设置 - 启动 Java 检测」恢复");
            setStatus("Java 检测提示已关闭");
            hideJavaCheckPrompt();
        });

        // 我已安装，重新检测：忽略强制模拟，走真实检测
        recheckButton.setOnAction(event -> {
            hideJavaCheckPrompt();
            String versionId = requirement == null ? "" : requirement.versionId();
            if (versionId.isEmpty()) {
                return;
            }
            appendLog("重新检测 Java 运行时 ...");
            setStatus("正在重新检测 Java ...");
            javaCheckService.runAsync(versionId, gamePaths, r ->
                    Platform.runLater(() -> handleJavaCheckResult(r, true)));
        });

        // 倒计时 + 自动打开（可用「取消自动打开」中断）；-Dmclqe.javacheck.autoOpenMs=0 可关闭自动跳转（自验用）
        final int autoOpenMs = Integer.getInteger("mclqe.javacheck.autoOpenMs", 3000);
        if (autoOpenMs <= 0) {
            countdown.setText("自动打开已关闭，可点击「打开下载页」手动前往");
        } else {
            final int stepMs = 1000;
            final int totalSteps = Math.max(1, autoOpenMs / stepMs);
            final int[] remain = {totalSteps};
            int seconds = Math.max(1, autoOpenMs / 1000);
            countdown.setText("将在 " + seconds + " 秒后自动打开浏览器下载页 ...");
            javaCheckCountdown = new Timeline(new KeyFrame(Duration.millis(stepMs), event -> {
                remain[0]--;
                if (remain[0] > 0) {
                    countdown.setText("将在 " + remain[0] + " 秒后自动打开浏览器下载页 ...");
                } else {
                    countdown.setText("正在打开浏览器下载页 ...");
                }
            }));
            javaCheckCountdown.setCycleCount(totalSteps);
            javaCheckCountdown.play();

            javaCheckAutoOpen = new PauseTransition(Duration.millis(autoOpenMs));
            javaCheckAutoOpen.setOnFinished(event -> {
                if (javaCheckOverlay == null) {
                    return;
                }
                openJavaDownloadPage(downloadUrl);
                hideJavaCheckPrompt();
            });
            javaCheckAutoOpen.play();
        }

        setStatus("Java 环境异常：" + result.conclusion());
        appendLog("已展示 Java 检测提示（" + required + "，" + result.conclusion() + "）");
    }

    /** 关闭 Java 检测提示层并停止定时器。 */
    private void hideJavaCheckPrompt() {
        stopJavaCheckTimers();
        if (javaCheckOverlay != null && sceneRoot != null) {
            sceneRoot.getChildren().remove(javaCheckOverlay);
        }
        javaCheckOverlay = null;
    }

    private void stopJavaCheckTimers() {
        if (javaCheckAutoOpen != null) {
            javaCheckAutoOpen.stop();
            javaCheckAutoOpen = null;
        }
        if (javaCheckCountdown != null) {
            javaCheckCountdown.stop();
            javaCheckCountdown = null;
        }
    }

    /**
     * 打开 Java 下载页：优先 JavaFX HostServices（与主程序同一浏览器会话），
     * 失败依次回退 AWT Desktop / 剪贴板，保证任何环境都能拿到地址。
     */
    private void openJavaDownloadPage(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        appendLog("Java 下载页：" + url);
        if (JavaCheckService.browseDisabled()) {
            appendLog("（已启用 -Dmclqe.javacheck.noBrowse，仅打印下载地址，不打开浏览器）");
            setStatus("Java 下载页：" + url);
            return;
        }
        try {
            getHostServices().showDocument(url);
            setStatus("已在浏览器打开 Java 下载页");
            return;
        } catch (RuntimeException ignored) {
            // 回退到 AWT Desktop
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                setStatus("已在浏览器打开 Java 下载页");
                return;
            }
        } catch (Exception e) {
            appendLog("打开浏览器失败: " + e.getMessage());
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
        appendLog("已将下载地址复制到剪贴板，请手动粘贴到浏览器打开");
        setStatus("下载地址已复制到剪贴板");
    }
}



