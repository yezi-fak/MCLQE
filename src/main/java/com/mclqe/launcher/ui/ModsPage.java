package com.mclqe.launcher.ui;

import com.mclqe.launcher.config.ConfigManager;
import com.mclqe.launcher.config.LauncherConfig;
import com.mclqe.launcher.game.Instance;
import com.mclqe.launcher.game.InstanceManager;
import com.mclqe.launcher.mod.ModDependency;
import com.mclqe.launcher.mod.ModFile;
import com.mclqe.launcher.mod.ModHit;
import com.mclqe.launcher.mod.ModInstaller;
import com.mclqe.launcher.mod.ModProject;
import com.mclqe.launcher.mod.ModTarget;
import com.mclqe.launcher.mod.ModVersion;
import com.mclqe.launcher.mod.ModrinthClient;
import com.mclqe.launcher.mod.SearchResult;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 「模组下载」页面（HMCL / Modrinth App 风格布局）。
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 标题 + 副标题（目标实例 / 安装目录）                   打开目录 │
 * │ [搜索框........................] [搜索] [排序▾] [中国镜像]      │
 * ├───────────────┬──────────────────────────────────────────────┤
 * │ 筛选侧栏 224px │ 内容区：空态 / 加载态 / 结果列表 / 全宽详情     │
 * │ 目标实例▾      │                                              │
 * │ MC 版本▾       │                                              │
 * │ 加载器▾        │                                              │
 * │ 分类 chips     │                                              │
 * ├───────────────┴──────────────────────────────────────────────┤
 * │ 日志条（可折叠）：进度 + 状态 + 展开日志                        │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>所有网络请求与文件写入均在后台线程执行，结果通过 {@link Platform#runLater(Runnable)} 回到 UI 线程。</p>
 */
public class ModsPage extends VBox {

    private static final String ALL = "全部";
    private static final List<String> LOADER_LABELS = List.of(ALL, "Fabric", "Forge", "NeoForge", "Quilt");
    private static final List<String> SORT_LABELS = List.of("相关度", "下载量", "最新");
    private static final int PAGE_SIZE = 20;
    private static final int MAX_CATEGORIES = 8;
    private static final int BODY_PREVIEW_LIMIT = 2200;
    private static final String[] AVATAR_COLORS = {
            "#6750A4", "#0061A4", "#006E1C", "#8B5000", "#984061", "#006876", "#7D5260", "#4A5C92"
    };

    /** 右侧内容区四种状态（互斥）。 */
    private enum View { EMPTY, LOADING, RESULTS, DETAIL }

    private final LauncherConfig config;
    private final ConfigManager configManager;
    private final Supplier<Instance> currentInstance;
    private final Consumer<String> logSink;
    private final Consumer<String> statusSink;
    private final ModrinthClient client;
    private final ModInstaller installer;

    // ---------------- 顶部 ----------------
    private Label subtitleLabel;
    private TextField searchField;
    private ComboBox<String> sortBox;
    private CheckBox mirrorBox;
    private Button openModsButton;
    private PauseTransition debounce;

    // ---------------- 左侧筛选栏 ----------------
    private ComboBox<Instance> instanceBox;
    private ComboBox<String> versionBox;
    private ComboBox<String> loaderBox;
    private FlowPane categoryChips;
    private CheckBox onlyUninstalledBox;
    private Label sidebarHint;

    // ---------------- 右侧内容区 ----------------
    private StackPane contentStack;
    private VBox emptyView;
    private VBox loadingView;
    private Label loadingTitle;
    private VBox resultsView;
    private VBox resultList;
    private ScrollPane resultScroll;
    private Label resultsCountLabel;
    private Label filterSummary;
    private Button prevButton;
    private Button nextButton;
    private Label pageLabel;
    private VBox detailView;
    private VBox detailContent;

    // ---------------- 日志 ----------------
    private ProgressBar progressBar;
    private Label logStatus;
    private Button logToggle;
    private VBox logLines;
    private ScrollPane logScroll;

    // ---------------- 遮罩 / 确认框 ----------------
    private StackPane overlayHost;
    private Region veil;
    private VBox confirmDialog;
    private VBox confirmBody;
    private VBox depBox;
    private Label confirmSubtitle;

    // ---------------- 状态 ----------------
    private View view = View.EMPTY;
    private final List<ModHit> loadedHits = new ArrayList<>();
    private int totalHits;
    private int currentPage;
    private int searchSeq;
    /** 程序内部改写筛选控件时置位，避免 onAction 触发重复检索。 */
    private boolean suppressSearchTrigger = false;
    private String lastSearchFingerprint = "";
    private long lastSearchAt = 0L;
    private int detailSeq;
    private boolean searching;
    private boolean searchedOnce;
    private boolean autoBrowseStarted;
    private boolean logExpanded = true;
    private boolean syncingInstance;
    private String lastQuery = "";
    private String lastGameVersion = ALL;
    private String lastLoader = "all";
    private String lastSort = "relevance";
    private final Set<String> selectedCategories = new LinkedHashSet<>();
    private final Set<String> installedFiles = new LinkedHashSet<>();

    private ModHit selectedHit;
    private ModProject currentProject;
    private List<ModVersion> currentVersions = new ArrayList<>();
    private boolean detailShowAll;
    private ComboBox<Instance> detailInstanceBox;
    private Button detailInstallButton;
    private Label detailStatus;
    private ModVersion confirmVersion;
    private List<CheckBox> depChecks = new ArrayList<>();

    public ModsPage(LauncherConfig config, ConfigManager configManager, Supplier<Instance> currentInstance,
                    Consumer<String> logSink, Consumer<String> statusSink) {
        this.config = config;
        this.configManager = configManager;
        this.currentInstance = currentInstance;
        this.logSink = logSink == null ? msg -> { } : logSink;
        this.statusSink = statusSink == null ? msg -> { } : statusSink;
        Consumer<String> modLogger = msg -> Platform.runLater(() -> appendLog(msg));
        this.client = new ModrinthClient(modLogger);
        this.client.setMirrorEnabled(config != null && config.isModrinthMirror());
        this.installer = new ModInstaller(client, modLogger);

        getStyleClass().add("page");
        setSpacing(8);

        VBox mainColumn = new VBox(8,
                buildHeader(),
                buildToolbar(),
                buildBody(),
                buildLogPanel());
        VBox.setVgrow(mainColumn.getChildren().get(2), Priority.ALWAYS);

        veil = new Region();
        veil.getStyleClass().add("mods-veil");
        veil.setVisible(false);
        veil.setManaged(false);
        veil.setOnMouseClicked(e -> closeConfirm());

        confirmDialog = buildConfirmDialog();
        confirmDialog.setVisible(false);
        confirmDialog.setManaged(false);

        StackPane dialogLayer = new StackPane(confirmDialog);
        dialogLayer.setPickOnBounds(false);
        dialogLayer.setAlignment(Pos.CENTER);

        overlayHost = new StackPane(mainColumn, veil, dialogLayer);
        VBox.setVgrow(overlayHost, Priority.ALWAYS);
        getChildren().add(overlayHost);

        updateTargetLabel();
        refreshInstances();
        refreshInstalledFiles();
        loadGameVersionsAsync();
        loadCategoriesAsync();
        setView(View.EMPTY);
        appendLog(nowText() + " [模组] 就绪，数据源 " + (client.isMirrorEnabled() ? "中国镜像优先" : "官方优先（失败自动切镜像）"));
    }

    // ==================================================================== 布局：顶部

    private Node buildHeader() {
        Label title = new Label("模组下载");
        title.getStyleClass().add("page-title");
        subtitleLabel = new Label("选择左侧目标实例与筛选条件，右侧浏览 / 安装 Modrinth 模组");
        subtitleLabel.getStyleClass().add("page-subtitle");
        subtitleLabel.setWrapText(true);

        openModsButton = new Button("打开 mods 目录");
        openModsButton.getStyleClass().add("button-border");
        openModsButton.setOnAction(e -> openModsDir());

        VBox texts = new VBox(2, title, subtitleLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(10, texts, openModsButton);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node buildToolbar() {
        searchField = new TextField();
        searchField.setPromptText("搜索模组名称或描述，例如 sodium / jei / 小地图");
        searchField.getStyleClass().add("search-field");
        searchField.setOnAction(e -> runSearch(0));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setMaxWidth(Double.MAX_VALUE);

        // 输入防抖：停止输入 400ms 后自动检索，避免每敲一个字都发请求
        debounce = new PauseTransition(Duration.millis(400));
        debounce.setOnFinished(e -> runSearch(0));
        searchField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                debounce.playFromStart();
            }
        });

        Button searchButton = new Button("搜索");
        searchButton.getStyleClass().add("button-raised");
        searchButton.setMinWidth(72);
        searchButton.setOnAction(e -> {
            debounce.stop();
            runSearch(0);
        });

        Label sortTag = new Label("排序");
        sortTag.getStyleClass().add("mods-filter-label");
        sortBox = new ComboBox<>();
        sortBox.getItems().addAll(SORT_LABELS);
        sortBox.getSelectionModel().selectFirst();
        sortBox.setPrefWidth(96);
        sortBox.setMinWidth(96);
        sortBox.setOnAction(e -> {
            if (!suppressSearchTrigger) {
                runSearch(0);
            }
        });

        mirrorBox = new CheckBox("中国镜像");
        mirrorBox.setSelected(config != null && config.isModrinthMirror());
        mirrorBox.setOnAction(e -> toggleMirror());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, searchField, searchButton, spacer, sortTag, sortBox, mirrorBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ==================================================================== 布局：主体

    private Node buildBody() {
        HBox body = new HBox(10, buildSidebar(), buildContent());
        HBox.setHgrow(body.getChildren().get(1), Priority.ALWAYS);
        body.setFillHeight(true);
        return body;
    }

    private Node buildSidebar() {
        VBox sidebar = new VBox(6);
        sidebar.getStyleClass().add("mods-sidebar");
        sidebar.setPrefWidth(224);
        sidebar.setMinWidth(210);
        sidebar.setMaxWidth(240);

        Label head = new Label("筛选");
        head.getStyleClass().add("card-title");

        instanceBox = new ComboBox<>();
        instanceBox.setMaxWidth(Double.MAX_VALUE);
        instanceBox.setCellFactory(list -> instanceCell());
        instanceBox.setButtonCell(instanceCell());
        instanceBox.setOnAction(e -> {
            if (syncingInstance) {
                return;
            }
            refreshInstalledFiles();
            applyInstanceDefaults();
            updateTargetLabel();
            syncDetailInstanceBox();
            runSearch(0);
        });

        versionBox = new ComboBox<>();
        versionBox.getItems().add(ALL);
        versionBox.getSelectionModel().selectFirst();
        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setOnAction(e -> {
            if (!syncingInstance && !suppressSearchTrigger) {
                runSearch(0);
            }
        });

        loaderBox = new ComboBox<>();
        loaderBox.getItems().addAll(LOADER_LABELS);
        loaderBox.getSelectionModel().selectFirst();
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.setOnAction(e -> {
            if (!syncingInstance && !suppressSearchTrigger) {
                runSearch(0);
            }
        });

        categoryChips = new FlowPane(4, 4);
        categoryChips.setPrefWrapLength(196);
        Label categoryLoading = new Label("分类加载中 ...");
        categoryLoading.getStyleClass().add("page-subtitle");
        categoryChips.getChildren().add(categoryLoading);

        onlyUninstalledBox = new CheckBox("仅显示未安装");
        onlyUninstalledBox.setOnAction(e -> renderResults());

        sidebarHint = new Label();
        sidebarHint.getStyleClass().add("page-subtitle");
        sidebarHint.setWrapText(true);

        Button resetButton = new Button("重置筛选");
        resetButton.getStyleClass().add("button-border");
        resetButton.setMaxWidth(Double.MAX_VALUE);
        resetButton.setOnAction(e -> resetFilters());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        sidebar.getChildren().addAll(
                head,
                filterLabel("目标实例"), instanceBox,
                filterLabel("MC 版本"), versionBox,
                filterLabel("加载器"), loaderBox,
                filterLabel("分类"), categoryChips,
                onlyUninstalledBox,
                sidebarHint,
                spacer,
                resetButton);
        return sidebar;
    }

    private Node buildContent() {
        contentStack = new StackPane();
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        contentStack.setAlignment(Pos.TOP_LEFT);

        emptyView = buildEmptyView();
        loadingView = buildLoadingView();
        resultsView = buildResultsView();
        detailView = buildDetailView();

        contentStack.getChildren().addAll(emptyView, loadingView, resultsView, detailView);
        return contentStack;
    }

    private VBox buildEmptyView() {
        emptyView = new VBox();
        emptyView.setAlignment(Pos.CENTER);
        setEmptyState("搜索模组，或直接浏览热门",
                "左侧先选好目标实例：MC 版本与加载器会自动跟随实例，也可以手动改成其它条件；"
                        + "输入关键词后右侧会实时给出结果。",
                "浏览热门模组", this::browsePopular);
        return emptyView;
    }

    /** 重建空态内容（唯一入口，避免一次性替换后结构失效）。 */
    private void setEmptyState(String titleText, String hintText, String actionText, Runnable action) {
        Label badge = new Label("M");
        badge.getStyleClass().add("mods-empty-badge");

        Label title = new Label(titleText);
        title.getStyleClass().add("mods-empty-title");
        title.setWrapText(true);

        Label hint = new Label(hintText);
        hint.getStyleClass().add("page-subtitle");
        hint.setWrapText(true);
        hint.setMaxWidth(440);

        Button primary = new Button(actionText);
        primary.getStyleClass().add("button-raised");
        primary.setOnAction(e -> action.run());

        Button clear = new Button("清空筛选");
        clear.getStyleClass().add("button-border");
        clear.setOnAction(e -> resetFilters());

        HBox actions = new HBox(8, primary, clear);
        actions.setAlignment(Pos.CENTER);

        VBox box = new VBox(10, badge, title, hint, actions);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(36, 20, 36, 20));
        box.setMaxWidth(560);
        emptyView.getChildren().setAll(box);
    }

    private VBox buildLoadingView() {
        loadingTitle = new Label("正在检索 Modrinth ...");
        loadingTitle.getStyleClass().add("mods-content-title");

        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("mods-loading-bar");

        VBox skeleton = new VBox(8);
        skeleton.setPadding(new Insets(10, 2, 10, 2));
        for (int i = 0; i < 6; i++) {
            Region block = new Region();
            block.getStyleClass().add("mods-skeleton");
            block.setPrefHeight(i == 0 ? 64 : 52);
            block.setMaxWidth(Double.MAX_VALUE);
            skeleton.getChildren().add(block);
        }
        VBox.setVgrow(skeleton, Priority.ALWAYS);

        VBox box = new VBox(8, loadingTitle, bar, skeleton);
        box.setFillWidth(true);
        return box;
    }

    private VBox buildResultsView() {
        resultsCountLabel = new Label("共 0 个模组");
        resultsCountLabel.getStyleClass().add("mods-content-title");

        filterSummary = new Label();
        filterSummary.getStyleClass().add("page-subtitle");
        filterSummary.setWrapText(true);

        Label viewMode = new Label("列表视图");
        viewMode.getStyleClass().add("mods-view-mode");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox head = new HBox(8, resultsCountLabel, spacer, viewMode);
        head.setAlignment(Pos.CENTER_LEFT);

        resultList = new VBox(6);
        resultList.setPadding(new Insets(2, 6, 6, 0));
        resultScroll = new ScrollPane(resultList);
        resultScroll.getStyleClass().add("page-scroll");
        resultScroll.setFitToWidth(true);
        resultScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(resultScroll, Priority.ALWAYS);

        prevButton = new Button("上一页");
        prevButton.getStyleClass().add("button-border");
        prevButton.setOnAction(e -> {
            if (currentPage > 0) {
                runSearch(currentPage - 1);
            }
        });
        nextButton = new Button("下一页");
        nextButton.getStyleClass().add("button-border");
        nextButton.setOnAction(e -> {
            int pages = pageCount();
            if (currentPage + 1 < pages) {
                runSearch(currentPage + 1);
            }
        });
        pageLabel = new Label("第 1 / 1 页");
        pageLabel.getStyleClass().add("page-subtitle");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        HBox pager = new HBox(8, prevButton, nextButton, spacer2, pageLabel);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(4, 0, 0, 0));

        VBox box = new VBox(6, head, filterSummary, resultScroll, pager);
        box.setFillWidth(true);
        return box;
    }

    private VBox buildDetailView() {
        detailContent = new VBox(10);
        detailContent.setPadding(new Insets(0, 6, 6, 0));

        ScrollPane scroll = new ScrollPane(detailContent);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox box = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
    }

    private Node buildLogPanel() {
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(140);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.getStyleClass().add("mods-log-progress");

        logStatus = new Label("就绪");
        logStatus.getStyleClass().add("mods-log-status");
        logStatus.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(logStatus, Priority.ALWAYS);

        logToggle = new Button("收起日志");
        logToggle.getStyleClass().add("button-text");
        logToggle.setOnAction(e -> setLogExpanded(!logExpanded));

        HBox bar = new HBox(8, progressBar, logStatus, logToggle);
        bar.getStyleClass().add("mods-log-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        logLines = new VBox(1);
        logLines.setPadding(new Insets(6, 10, 6, 10));
        logScroll = new ScrollPane(logLines);
        logScroll.getStyleClass().addAll("page-scroll", "mods-log-scroll");
        logScroll.setFitToWidth(true);
        logScroll.setPrefHeight(104);
        logScroll.setMinHeight(104);

        VBox panel = new VBox(bar, logScroll);
        return panel;
    }

    // ==================================================================== 确认框

    private VBox buildConfirmDialog() {
        Label title = new Label("确认安装");
        title.getStyleClass().add("mods-dialog-title");

        confirmSubtitle = new Label();
        confirmSubtitle.getStyleClass().add("mods-dialog-subtitle");
        confirmSubtitle.setWrapText(true);
        confirmSubtitle.setMaxWidth(Double.MAX_VALUE);

        confirmBody = new VBox(4);

        Label depTitle = new Label("前置依赖");
        depTitle.getStyleClass().add("mods-dialog-section");

        depBox = new VBox(2);

        Label note = new Label("已存在的同名文件会按 SHA1 校验跳过，不会重复下载；安装完成后可在 mods 目录中管理。");
        note.getStyleClass().add("page-subtitle");
        note.setWrapText(true);

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("button-border");
        cancel.setOnAction(e -> closeConfirm());

        Button confirm = new Button("确认安装");
        confirm.getStyleClass().add("button-raised");
        confirm.setOnAction(e -> {
            ModVersion version = confirmVersion;
            List<ModDependency> chosen = selectedDependencies();
            closeConfirm();
            if (version != null) {
                startInstall(version, chosen);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, confirm);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox dialog = new VBox(8, title, confirmSubtitle, new Separator(), confirmBody,
                depTitle, depBox, note, actions);
        dialog.getStyleClass().add("mods-dialog");
        dialog.setMaxWidth(520);
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        return dialog;
    }

    private void openConfirm(ModVersion version) {
        ModTarget target = currentTarget();
        if (target == null || version == null) {
            return;
        }
        confirmVersion = version;
        ModFile file = version.primaryFile();
        String title = (currentProject == null ? "" : currentProject.title() + "  ")
                + version.versionNumber() + "  [" + version.typeLabel() + "]";
        confirmSubtitle.setText(title);

        confirmBody.getChildren().clear();
        confirmBody.getChildren().addAll(
                dialogLine("文件", file == null ? "（无可用文件）" : file.filename() + "  ·  " + formatSize(file.size())),
                dialogLine("目标实例", target.instance().displayName()),
                dialogLine("安装目录", String.valueOf(target.modsDir())));

        depBox.getChildren().clear();
        depChecks = new ArrayList<>();
        List<ModDependency> required = version.requiredDependencies();
        if (required.isEmpty()) {
            Label none = new Label("无必需前置依赖");
            none.getStyleClass().add("page-subtitle");
            depBox.getChildren().add(none);
        } else {
            for (ModDependency dependency : required) {
                String id = dependency.projectId() == null || dependency.projectId().isBlank()
                        ? "未知项目" : dependency.projectId();
                CheckBox box = new CheckBox(id + "（必需）");
                box.setSelected(true);
                box.setUserData(dependency);
                depChecks.add(box);
                depBox.getChildren().add(box);
            }
        }

        veil.setVisible(true);
        veil.setManaged(true);
        confirmDialog.setVisible(true);
        confirmDialog.setManaged(true);
        trace("confirm-open version=" + version.versionNumber() + " deps=" + required.size());
    }

    private List<ModDependency> selectedDependencies() {
        List<ModDependency> list = new ArrayList<>();
        for (CheckBox box : depChecks) {
            if (box.isSelected() && box.getUserData() instanceof ModDependency dependency) {
                list.add(dependency);
            }
        }
        return list;
    }

    private void closeConfirm() {
        veil.setVisible(false);
        veil.setManaged(false);
        confirmDialog.setVisible(false);
        confirmDialog.setManaged(false);
        confirmVersion = null;
    }

    private Node dialogLine(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("mods-dialog-key");
        k.setMinWidth(64);
        Label v = new Label(value);
        v.getStyleClass().add("mods-dialog-value");
        v.setWrapText(true);
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, k, v);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    // ==================================================================== 状态切换

    private void setView(View next) {
        view = next;
        emptyView.setVisible(next == View.EMPTY);
        emptyView.setManaged(next == View.EMPTY);
        loadingView.setVisible(next == View.LOADING);
        loadingView.setManaged(next == View.LOADING);
        resultsView.setVisible(next == View.RESULTS);
        resultsView.setManaged(next == View.RESULTS);
        detailView.setVisible(next == View.DETAIL);
        detailView.setManaged(next == View.DETAIL);
        trace("view=" + next);
    }

    // ==================================================================== 实例 / 目标

    /** 从磁盘重新加载实例列表，并尽量保留当前选中项。 */
    public void refreshInstances() {
        Path gameDir = config == null ? null : config.gameDirOrNull();
        boolean isolation = config == null || config.isDefaultVersionIsolation();
        List<Instance> instances = InstanceManager.list(gameDir, isolation);

        Instance keep = instanceBox.getValue();
        Instance preferred = keep != null ? keep : currentInstance == null ? null : currentInstance.get();
        syncingInstance = true;
        instanceBox.getItems().setAll(instances);
        if (instances.isEmpty()) {
            instanceBox.setPromptText("游戏目录下无实例");
            syncingInstance = false;
            updateTargetLabel();
            return;
        }
        Instance match = null;
        if (preferred != null) {
            for (Instance instance : instances) {
                if (instance.id().equals(preferred.id())) {
                    match = instance;
                    break;
                }
            }
        }
        instanceBox.getSelectionModel().select(match != null ? match : instances.get(0));
        syncingInstance = false;
        applyInstanceDefaults();
        updateTargetLabel();
        refreshInstalledFiles();
    }

    /** 目标实例的 MC 版本 / 加载器作为筛选默认值。 */
    private void applyInstanceDefaults() {
        ModTarget target = currentTarget();
        if (target == null) {
            return;
        }
        syncingInstance = true;
        if (target.hasGameVersion()) {
            if (!versionBox.getItems().contains(target.gameVersion())) {
                versionBox.getItems().add(0, target.gameVersion());
            }
            versionBox.getSelectionModel().select(target.gameVersion());
        } else {
            versionBox.getSelectionModel().selectFirst();
        }
        if (target.hasLoader()) {
            for (String item : loaderBox.getItems()) {
                if (item.equalsIgnoreCase(target.loader())) {
                    loaderBox.getSelectionModel().select(item);
                    break;
                }
            }
        } else {
            loaderBox.getSelectionModel().selectFirst();
        }
        syncingInstance = false;
    }

    private ModTarget currentTarget() {
        Instance instance = instanceBox == null ? null : instanceBox.getValue();
        return instance == null ? null : ModTarget.of(instance);
    }

    private void updateTargetLabel() {
        ModTarget target = currentTarget();
        if (openModsButton != null) {
            openModsButton.setDisable(target == null);
        }
        if (target == null) {
            subtitleLabel.setText("尚未选择实例：请先在「实例管理」创建或导入版本");
            if (sidebarHint != null) {
                sidebarHint.setText("无目标实例");
            }
            return;
        }
        String path = String.valueOf(target.modsDir());
        subtitleLabel.setText("目标实例：" + target.label() + " · 安装目录：" + path);
        subtitleLabel.setTooltip(new Tooltip(path));
        if (sidebarHint != null) {
            String hint = target.missingHint();
            sidebarHint.setText(hint == null ? "已识别实例版本与加载器" : hint);
        }
        refreshInstalledFiles();
        if (detailInstallButton != null) {
            detailInstallButton.setDisable(false);
        }
    }

    private void syncDetailInstanceBox() {
        if (detailInstanceBox == null) {
            return;
        }
        detailInstanceBox.getSelectionModel().select(instanceBox.getValue());
    }

    /** 读取目标实例 mods 目录中的文件名（小写），用于「已安装」判定。 */
    private void refreshInstalledFiles() {
        installedFiles.clear();
        ModTarget target = currentTarget();
        if (target == null) {
            return;
        }
        Path dir = target.modsDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .forEach(installedFiles::add);
        } catch (IOException ignored) {
            // 目录不可读时按「全未安装」处理
        }
    }

    private boolean versionInstalled(ModVersion version) {
        ModFile file = version == null ? null : version.primaryFile();
        return file != null && file.filename() != null
                && installedFiles.contains(file.filename().toLowerCase(Locale.ROOT));
    }

    private boolean hitInstalled(ModHit hit) {
        if (hit == null) {
            return false;
        }
        String slug = hit.slug() == null ? "" : hit.slug().toLowerCase(Locale.ROOT);
        if (slug.length() >= 4) {
            for (String file : installedFiles) {
                if (file.contains(slug)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadGameVersionsAsync() {
        Thread thread = new Thread(() -> {
            try {
                List<String> versions = client.gameVersions();
                Platform.runLater(() -> {
                    String selected = versionBox.getValue();
                    versionBox.getItems().setAll(ALL);
                    versionBox.getItems().addAll(versions);
                    // 标签异步返回时不能把「目标实例的 MC 版本」冲掉，否则搜索与详情会退化成「全部」
                    ModTarget target = currentTarget();
                    String prefer = target != null && target.hasGameVersion() ? target.gameVersion() : selected;
                    if (prefer != null && versionBox.getItems().contains(prefer)) {
                        versionBox.getSelectionModel().select(prefer);
                    } else {
                        versionBox.getSelectionModel().selectFirst();
                    }
                    trace("gameVersions=" + versions.size() + " selected=" + versionBox.getValue());
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendLog(nowText() + " [模组] 加载 MC 版本标签失败：" + shortMessage(e)));
            }
        }, "mclqe-mods-tags");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadCategoriesAsync() {
        Thread thread = new Thread(() -> {
            try {
                List<String> categories = client.categories();
                Platform.runLater(() -> {
                    categoryChips.getChildren().clear();
                    if (categories.isEmpty()) {
                        Label none = new Label("无可用分类");
                        none.getStyleClass().add("page-subtitle");
                        categoryChips.getChildren().add(none);
                        return;
                    }
                    for (String category : categories) {
                        ToggleButton chip = new ToggleButton(displayCategory(category));
                        chip.getStyleClass().add("mods-chip");
                        chip.setUserData(category);
                        chip.setOnAction(e -> onCategoryToggled(chip, category));
                        categoryChips.getChildren().add(chip);
                    }
                    trace("categories=" + categories.size());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    categoryChips.getChildren().clear();
                    Label failed = new Label("分类加载失败");
                    failed.getStyleClass().add("page-subtitle");
                    categoryChips.getChildren().add(failed);
                    appendLog(nowText() + " [模组] 加载分类失败：" + shortMessage(e));
                });
            }
        }, "mclqe-mods-categories");
        thread.setDaemon(true);
        thread.start();
    }

    private void onCategoryToggled(ToggleButton chip, String category) {
        if (chip.isSelected()) {
            if (selectedCategories.size() >= MAX_CATEGORIES) {
                chip.setSelected(false);
                appendLog(nowText() + " [模组] 分类最多同时选择 " + MAX_CATEGORIES + " 个");
                return;
            }
            selectedCategories.add(category);
        } else {
            selectedCategories.remove(category);
        }
        runSearch(0);
    }

    private void resetFilters() {
        for (Node node : categoryChips.getChildren()) {
            if (node instanceof ToggleButton chip) {
                chip.setSelected(false);
            }
        }
        selectedCategories.clear();
        onlyUninstalledBox.setSelected(false);
        searchField.clear();
        sortBox.getSelectionModel().selectFirst();
        applyInstanceDefaults();
        runSearch(0);
    }

    // ==================================================================== 搜索

    private void browsePopular() {
        suppressSearchTrigger = true;
        try {
            searchField.clear();
            sortBox.getSelectionModel().select("下载量");
        } finally {
            suppressSearchTrigger = false;
        }
        runSearch(0);
    }

    private void runSearch(int page) {
        String query = searchField.getText() == null ? "" : searchField.getText().trim();
        String fingerprint = query + "|" + page + "|" + selectedGameVersion() + "|" + selectedLoader() + "|"
                + selectedSort() + "|" + String.join(",", selectedCategories);
        long now = System.currentTimeMillis();
        if (fingerprint.equals(lastSearchFingerprint) && now - lastSearchAt < 800) {
            return;
        }
        lastSearchFingerprint = fingerprint;
        lastSearchAt = now;
        lastQuery = query;
        lastGameVersion = selectedGameVersion();
        lastLoader = selectedLoader();
        lastSort = selectedSort();
        currentPage = Math.max(0, page);
        int offset = currentPage * PAGE_SIZE;

        searching = true;
        int seq = ++searchSeq;
        setView(View.LOADING);
        loadingTitle.setText(query.isEmpty()
                ? "正在加载热门模组 ..." : "正在检索「" + query + "」...");
        logStatus.setText("正在检索 Modrinth ...");
        statusSink.accept("正在检索 Modrinth 模组：" + (query.isEmpty() ? "（热门）" : query));

        List<String> categories = new ArrayList<>(selectedCategories);
        Thread thread = new Thread(() -> {
            try {
                SearchResult result = client.search(query, lastGameVersion, lastLoader, lastSort, offset,
                        PAGE_SIZE, categories);
                Platform.runLater(() -> {
                    if (seq != searchSeq) {
                        return;
                    }
                    searching = false;
                    renderResults(result);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (seq != searchSeq) {
                        return;
                    }
                    searching = false;
                    renderSearchError(e);
                });
            }
        }, "mclqe-mods-search");
        thread.setDaemon(true);
        thread.start();
    }

    private void renderResults(SearchResult result) {
        loadedHits.clear();
        if (result.hits() != null) {
            loadedHits.addAll(result.hits());
        }
        totalHits = result.totalHits();
        searchedOnce = true;
        renderResults();
        logStatus.setText("检索完成：共 " + totalHits + " 个结果");
        statusSink.accept("Modrinth 检索完成：共 " + totalHits + " 个结果");
        appendLog(nowText() + " [模组] 检索完成（" + (lastQuery.isEmpty() ? "热门" : lastQuery) + "），共 "
                + totalHits + " 个结果"
                + (ALL.equals(lastGameVersion) ? "" : "，MC " + lastGameVersion)
                + ("all".equals(lastLoader) ? "" : "，加载器 " + lastLoader)
                + (selectedCategories.isEmpty() ? "" : "，分类 " + String.join("/", selectedCategories))
                + mirrorHintSuffix());
        trace("search query=" + lastQuery + " mc=" + lastGameVersion + " loader=" + lastLoader
                + " sort=" + lastSort + " categories=" + selectedCategories
                + " total=" + totalHits + " shown=" + loadedHits.size() + " page=" + (currentPage + 1)
                + " mirror=" + client.isMirrorEnabled() + " mirrorFallback=" + client.mirrorFallbackUsed());
        maybeAutoSelect();
    }

    /** 按当前加载的数据重建列表（分页 / 仅显示未安装 / 空态都走这里）。 */
    private void renderResults() {
        if (loadedHits.isEmpty()) {
            setEmptyState("没有匹配的模组",
                    "试试更换关键词，或把 MC 版本 / 加载器 / 分类改宽松一些（MC 版本与加载器选「全部」可看全部结果）。",
                    "浏览热门模组", this::browsePopular);
            setView(View.EMPTY);
            return;
        }

        resultList.getChildren().clear();
        int shown = 0;
        for (ModHit hit : loadedHits) {
            if (onlyUninstalledBox.isSelected() && hitInstalled(hit)) {
                continue;
            }
            resultList.getChildren().add(resultRow(hit));
            shown++;
        }
        if (shown == 0) {
            Label none = new Label("当前页的模组都已安装，可取消「仅显示未安装」或翻页查看");
            none.getStyleClass().add("page-subtitle");
            none.setWrapText(true);
            resultList.getChildren().add(none);
        }

        resultsCountLabel.setText("共 " + totalHits + " 个模组"
                + (shown != loadedHits.size() ? "（本页显示 " + shown + " 个）" : ""));
        filterSummary.setText(filterText());
        updatePager();
        setView(View.RESULTS);
    }

    private String filterText() {
        List<String> parts = new ArrayList<>();
        parts.add(ALL.equals(lastGameVersion) ? "MC 全部" : "MC " + lastGameVersion);
        parts.add("all".equals(lastLoader) ? "加载器全部" : lastLoader);
        if (!selectedCategories.isEmpty()) {
            parts.add("分类 " + String.join(" / ", selectedCategories));
        }
        if (onlyUninstalledBox.isSelected()) {
            parts.add("仅未安装");
        }
        parts.add("排序 " + sortLabel(lastSort));
        return "当前筛选：" + String.join(" · ", parts) + mirrorHintSuffix();
    }

    private static String sortLabel(String sort) {
        return switch (sort) {
            case "downloads" -> "下载量";
            case "newest" -> "最新";
            default -> "相关度";
        };
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil(totalHits / (double) PAGE_SIZE));
    }

    private void updatePager() {
        int pages = pageCount();
        pageLabel.setText("第 " + (Math.min(currentPage + 1, pages)) + " / " + pages + " 页");
        prevButton.setDisable(currentPage <= 0);
        nextButton.setDisable(currentPage + 1 >= pages);
    }

    private String mirrorHintSuffix() {
        return client.mirrorFallbackUsed() ? "（已自动切换到中国镜像）" : "";
    }

    private void renderSearchError(Exception e) {
        setEmptyState("Modrinth 连接失败",
                shortMessage(e) + "\n可勾选顶部「中国镜像」后重试（官方 api.modrinth.com 失败会自动降级到 "
                        + "mod.mcimirror.top），或稍后再试。",
                "重试", () -> runSearch(currentPage));
        setView(View.EMPTY);
        logStatus.setText("检索失败：" + shortMessage(e));
        statusSink.accept("Modrinth 检索失败：" + shortMessage(e));
        appendLog(nowText() + " [模组] 检索失败：" + shortMessage(e));
    }

    private Node resultRow(ModHit hit) {
        VBox texts = new VBox(2);
        HBox titleRow = new HBox(6);
        Label title = new Label(hit.title());
        title.getStyleClass().add("mods-row-title");
        titleRow.getChildren().add(title);
        if (hitInstalled(hit)) {
            Label installed = new Label("已安装");
            installed.getStyleClass().addAll("mods-badge", "mods-badge-installed");
            titleRow.getChildren().add(installed);
        }
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label desc = new Label(hit.description());
        desc.getStyleClass().add("page-subtitle");
        desc.setWrapText(true);
        desc.setMaxHeight(36);

        Label meta = new Label(categoriesText(hit)
                + " · 下载 " + formatCount(hit.downloads())
                + " · 关注 " + formatCount(hit.follows())
                + " · 作者 " + (hit.author() == null || hit.author().isBlank() ? "未知" : hit.author()));
        meta.getStyleClass().add("mods-row-meta");

        texts.getChildren().addAll(titleRow, desc, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);

        HBox row = new HBox(10, avatarNode(hit.title(), 40), texts, versionsTags(hit));
        row.getStyleClass().add("mods-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOnMouseClicked(e -> selectHit(hit));
        return row;
    }

    /** 列表行右侧的版本标签。 */
    private Node versionsTags(ModHit hit) {
        FlowPane chips = new FlowPane(4, 4);
        chips.setPrefWrapLength(140);
        chips.setMaxWidth(150);
        chips.setAlignment(Pos.TOP_RIGHT);
        List<String> tags = new ArrayList<>();
        if (hit.versions() != null) {
            for (String version : hit.versions()) {
                if (tags.size() >= 2) {
                    break;
                }
                tags.add(version);
            }
        }
        for (String tag : tags) {
            Label chip = new Label(tag);
            chip.getStyleClass().add("tag");
            chips.getChildren().add(chip);
        }
        return chips;
    }

    private String categoriesText(ModHit hit) {
        if (hit.categories() == null || hit.categories().isEmpty()) {
            return "模组";
        }
        List<String> list = hit.categories().stream().limit(3).map(ModsPage::displayCategory).toList();
        return String.join(" / ", list);
    }

    // ==================================================================== 详情

    private void selectHit(ModHit hit) {
        selectedHit = hit;
        int seq = ++detailSeq;
        setView(View.LOADING);
        loadingTitle.setText("正在加载「" + hit.title() + "」的详情与版本 ...");

        String gameVersion = selectedGameVersion();
        String loader = selectedLoader();
        detailShowAll = false;
        Thread thread = new Thread(() -> {
            try {
                ModProject project = client.project(hit.identifier());
                List<ModVersion> versions = client.versions(hit.identifier(), gameVersion, loader);
                Platform.runLater(() -> {
                    if (seq != detailSeq) {
                        return;
                    }
                    currentProject = project;
                    currentVersions = versions;
                    renderDetail();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (seq != detailSeq) {
                        return;
                    }
                    appendLog(nowText() + " [模组] 加载 " + hit.title() + " 详情失败：" + shortMessage(e));
                    setView(View.EMPTY);
                    loadingTitle.setText("加载详情失败");
                });
            }
        }, "mclqe-mods-detail");
        thread.setDaemon(true);
        thread.start();
    }

    private void reloadVersions(boolean showAll) {
        if (selectedHit == null) {
            return;
        }
        int seq = ++detailSeq;
        detailShowAll = showAll;
        String gameVersion = showAll ? ALL : selectedGameVersion();
        String loader = showAll ? "all" : selectedLoader();
        setView(View.LOADING);
        loadingTitle.setText(showAll ? "正在加载全部版本 ..." : "正在加载匹配版本 ...");
        Thread thread = new Thread(() -> {
            try {
                List<ModVersion> versions = client.versions(selectedHit.identifier(), gameVersion, loader);
                Platform.runLater(() -> {
                    if (seq != detailSeq) {
                        return;
                    }
                    currentVersions = versions;
                    renderDetail();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (seq != detailSeq) {
                        return;
                    }
                    appendLog(nowText() + " [模组] 加载版本失败：" + shortMessage(e));
                    renderDetail();
                });
            }
        }, "mclqe-mods-versions");
        thread.setDaemon(true);
        thread.start();
    }

    private void renderDetail() {
        if (selectedHit == null || currentProject == null) {
            setView(View.EMPTY);
            return;
        }
        detailContent.getChildren().clear();

        Button back = new Button("← 返回列表");
        back.getStyleClass().add("button-text");
        back.setOnAction(e -> {
            setView(View.RESULTS);
            currentProject = null;
        });

        // 横幅
        Label title = new Label(textOr(currentProject.title(), selectedHit.title()));
        title.getStyleClass().add("mods-detail-title");
        title.setWrapText(true);
        Label meta = new Label("下载 " + formatCount(currentProject.downloads())
                + " · 关注 " + formatCount(currentProject.follows())
                + " · 分类 " + (currentProject.categories() == null || currentProject.categories().isEmpty()
                        ? "模组" : String.join(" / ", currentProject.categories().stream()
                                .limit(5).map(ModsPage::displayCategory).toList())));
        meta.getStyleClass().add("mods-row-meta");
        meta.setWrapText(true);
        Label desc = new Label(textOr(currentProject.description(), "（作者未提供一句话简介）"));
        desc.getStyleClass().add("page-subtitle");
        desc.setWrapText(true);

        VBox bannerTexts = new VBox(3, title, meta, desc);
        HBox.setHgrow(bannerTexts, Priority.ALWAYS);
        HBox banner = new HBox(12, avatarNode(title.getText(), 56), bannerTexts);
        banner.getStyleClass().add("mods-banner");
        banner.setAlignment(Pos.CENTER_LEFT);

        // 操作条：安装到 + 按钮 + 状态
        detailInstanceBox = new ComboBox<>();
        detailInstanceBox.setCellFactory(list -> instanceCell());
        detailInstanceBox.setButtonCell(instanceCell());
        detailInstanceBox.setPrefWidth(196);
        detailInstanceBox.setMaxWidth(196);
        detailInstanceBox.getItems().setAll(instanceBox.getItems());
        if (instanceBox.getValue() != null) {
            detailInstanceBox.getSelectionModel().select(instanceBox.getValue());
        }
        detailInstanceBox.setOnAction(e -> {
            Instance instance = detailInstanceBox.getValue();
            if (instance == null || instance.equals(instanceBox.getValue())) {
                return;
            }
            syncingInstance = true;
            instanceBox.getSelectionModel().select(instance);
            syncingInstance = false;
            refreshInstalledFiles();
            applyInstanceDefaults();
            updateTargetLabel();
            reloadVersions(detailShowAll);
        });

        detailInstallButton = new Button("安装");
        detailInstallButton.getStyleClass().add("button-raised");
        detailInstallButton.setMinWidth(96);
        detailInstallButton.setOnAction(e -> {
            ModVersion version = firstInstallableVersion();
            if (version == null) {
                detailStatus.setText("没有可安装的版本，可勾选「显示全部版本」");
                return;
            }
            openConfirm(version);
        });

        detailStatus = new Label();
        detailStatus.getStyleClass().add("page-subtitle");
        detailStatus.setWrapText(true);
        HBox.setHgrow(detailStatus, Priority.ALWAYS);

        Label installTo = new Label("安装到");
        installTo.getStyleClass().add("mods-filter-label");

        HBox ops = new HBox(8, installTo, detailInstanceBox, detailInstallButton, detailStatus);
        ops.getStyleClass().add("mods-ops");
        ops.setAlignment(Pos.CENTER_LEFT);

        // 简介 + 版本列表（左右分栏）
        Label bodyTitle = new Label("模组简介");
        bodyTitle.getStyleClass().add("card-title");
        Label body = new Label(preview(currentProject.body()));
        body.getStyleClass().add("mods-desc");
        body.setWrapText(true);
        ScrollPane bodyScroll = new ScrollPane(body);
        bodyScroll.getStyleClass().add("page-scroll");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);
        VBox left = new VBox(6, bodyTitle, bodyScroll);
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);

        VBox versionsBox = buildVersionPanel();

        HBox columns = new HBox(12, left, versionsBox);
        columns.setFillHeight(true);
        VBox.setVgrow(columns, Priority.ALWAYS);

        detailContent.getChildren().addAll(back, banner, ops, columns);
        VBox.setVgrow(columns, Priority.ALWAYS);

        setView(View.DETAIL);
        detailStatus.setText(currentVersions.isEmpty()
                ? "当前筛选下没有匹配版本，可勾选右侧「显示全部版本」"
                : "共 " + currentVersions.size() + " 个匹配版本");
        updateTargetLabel();

        trace("detail hit=" + selectedHit.identifier() + " versions=" + currentVersions.size()
                + " mc=" + (detailShowAll ? ALL : selectedGameVersion())
                + " loader=" + (detailShowAll ? "all" : selectedLoader())
                + " target=" + (currentTarget() == null ? "null" : currentTarget().modsDir()));
        if (Boolean.getBoolean("mclqe.mods.autoInstall")) {
            maybeAutoInstall();
        } else {
            maybeAutoConfirm();
        }
    }

    private VBox buildVersionPanel() {
        Label header = new Label("选择版本");
        header.getStyleClass().add("card-title");

        CheckBox showAll = new CheckBox("显示全部版本");
        showAll.setSelected(detailShowAll);
        showAll.setOnAction(e -> reloadVersions(showAll.isSelected()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, header, spacer, showAll);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox(6);
        list.setPadding(new Insets(2, 6, 4, 0));
        if (currentVersions.isEmpty()) {
            Label none = new Label("没有匹配版本，可勾选上方「显示全部版本」，或到左侧把 MC 版本 / 加载器改为「全部」。");
            none.getStyleClass().add("page-subtitle");
            none.setWrapText(true);
            list.getChildren().add(none);
        } else {
            int limit = Math.min(currentVersions.size(), 60);
            for (int i = 0; i < limit; i++) {
                list.getChildren().add(versionRow(currentVersions.get(i)));
            }
            if (currentVersions.size() > limit) {
                Label more = new Label("仅展示前 " + limit + " 个版本，请用筛选缩小范围");
                more.getStyleClass().add("page-subtitle");
                list.getChildren().add(more);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox panel = new VBox(6, head, scroll);
        panel.setPrefWidth(330);
        panel.setMinWidth(300);
        panel.setMaxWidth(360);
        panel.setFillWidth(true);
        return panel;
    }

    private Node versionRow(ModVersion version) {
        Label name = new Label(version.versionNumber() + "  " + version.typeLabel());
        name.getStyleClass().add("mods-version-title");
        Label badge = new Label(version.typeLabel());
        badge.getStyleClass().addAll("mods-badge", badgeClass(version));

        HBox titleRow = new HBox(6, name, badge);
        if (versionInstalled(version)) {
            Label installed = new Label("已安装");
            installed.getStyleClass().addAll("mods-badge", "mods-badge-installed");
            titleRow.getChildren().add(installed);
        }
        titleRow.setAlignment(Pos.CENTER_LEFT);

        String mc = version.gameVersions() == null || version.gameVersions().isEmpty()
                ? "" : version.gameVersions().get(0);
        String loaders = version.loaders() == null || version.loaders().isEmpty()
                ? "" : String.join("/", version.loaders());
        Label meta = new Label((mc.isEmpty() ? "" : "MC " + mc + " · ")
                + (loaders.isEmpty() ? "" : loaders + " · ")
                + "下载 " + formatCount(version.downloads())
                + " · " + shortDate(version.datePublished()));
        meta.getStyleClass().add("mods-row-meta");
        meta.setWrapText(true);

        Button install = new Button(versionInstalled(version) ? "重新安装" : "安装");
        install.getStyleClass().add("button-border");
        install.setMinWidth(72);
        install.setOnAction(e -> openConfirm(version));

        VBox texts = new VBox(2, titleRow, meta);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox row = new HBox(8, texts, install);
        row.getStyleClass().add("mods-version-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String badgeClass(ModVersion version) {
        if (version == null || version.versionType() == null) {
            return "mods-badge-unknown";
        }
        return switch (version.versionType().toLowerCase(Locale.ROOT)) {
            case "release" -> "mods-badge-release";
            case "beta" -> "mods-badge-beta";
            case "alpha" -> "mods-badge-alpha";
            default -> "mods-badge-unknown";
        };
    }

    private ModVersion firstInstallableVersion() {
        for (ModVersion version : currentVersions) {
            if (version.release()) {
                return version;
            }
        }
        return currentVersions.isEmpty() ? null : currentVersions.get(0);
    }

    // ==================================================================== 安装

    /** 打开确认框（供自验脚本自动触发）。 */
    private void maybeAutoConfirm() {
        if (!Boolean.getBoolean("mclqe.mods.confirm")) {
            return;
        }
        ModVersion version = firstInstallableVersion();
        if (version != null) {
            openConfirm(version);
        }
    }

    private void maybeAutoInstall() {
        if (!Boolean.getBoolean("mclqe.mods.autoInstall")) {
            return;
        }
        ModVersion version = firstInstallableVersion();
        if (version != null) {
            startInstall(version, version.requiredDependencies());
        }
    }

    private void maybeAutoSelect() {
        if (!Boolean.getBoolean("mclqe.mods.selectFirst") || loadedHits.isEmpty() || selectedHit != null) {
            return;
        }
        selectHit(loadedHits.get(0));
    }

    private void startInstall(ModVersion version, List<ModDependency> selectedDeps) {
        ModTarget target = currentTarget();
        if (target == null) {
            appendLog(nowText() + " [模组] 未选择目标实例，无法安装");
            return;
        }
        if (version == null) {
            return;
        }
        ModVersion toInstall = withDependencies(version, selectedDeps);

        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(0);
        logStatus.setText("准备安装 " + version.versionNumber() + " ...");
        statusSink.accept("正在安装模组：" + version.displayName());
        if (detailInstallButton != null) {
            detailInstallButton.setDisable(true);
        }
        appendLog(nowText() + " [模组] 开始安装 " + version.versionNumber() + " → " + target.modsDir());
        trace("install-start version=" + version.versionNumber()
                + " file=" + (version.primaryFile() == null ? "null" : version.primaryFile().filename())
                + " deps=" + selectedDeps.size());

        Thread thread = new Thread(() -> {
            try {
                ModInstaller.Result result = installer.install(target.modsDir(), toInstall,
                        target.gameVersion(), target.loader(),
                        (done, total, current) -> Platform.runLater(() -> {
                            progressBar.setProgress(total <= 0 ? 0 : (double) done / total);
                            logStatus.setText("安装中 " + done + "/" + total + " · " + current);
                        }));
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    logStatus.setText(result.summary());
                    if (detailInstallButton != null) {
                        detailInstallButton.setDisable(false);
                    }
                    statusSink.accept(result.summary());
                    appendLog(nowText() + " [模组] " + result.summary());
                    for (String file : result.files()) {
                        appendLog("           " + file);
                    }
                    refreshInstalledFiles();
                    if (view == View.DETAIL && selectedHit != null) {
                        renderDetail();
                    } else if (view == View.RESULTS) {
                        renderResults();
                    }
                    trace("install result installed=" + result.installed() + " skipped=" + result.skipped()
                            + " failed=" + result.failed() + " files=" + result.files());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    progressBar.setManaged(false);
                    logStatus.setText("安装失败：" + shortMessage(e));
                    if (detailInstallButton != null) {
                        detailInstallButton.setDisable(false);
                    }
                    statusSink.accept("模组安装失败：" + shortMessage(e));
                    appendLog(nowText() + " [模组] 安装失败：" + shortMessage(e));
                });
            }
        }, "mclqe-mods-install");
        thread.setDaemon(true);
        thread.start();
    }

    /** 复制 ModVersion，只保留用户勾选的前置依赖（安装器据此决定是否连带安装前置）。 */
    private static ModVersion withDependencies(ModVersion version, List<ModDependency> deps) {
        List<ModDependency> required = version.requiredDependencies();
        if (deps.size() >= required.size()) {
            return version;
        }
        return new ModVersion(version.id(), version.projectId(), version.name(), version.versionNumber(),
                version.versionType(), version.gameVersions(), version.loaders(), version.datePublished(),
                version.downloads(), version.files(), new ArrayList<>(deps));
    }

    private void openModsDir() {
        ModTarget target = currentTarget();
        if (target == null) {
            return;
        }
        try {
            Files.createDirectories(target.modsDir());
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target.modsDir().toFile());
            } else {
                appendLog("当前环境不支持打开资源管理器：" + target.modsDir());
            }
        } catch (Exception e) {
            appendLog("打开 mods 目录失败：" + e.getMessage());
        }
    }

    // ==================================================================== 日志

    private void setLogExpanded(boolean expanded) {
        logExpanded = expanded;
        logScroll.setVisible(expanded);
        logScroll.setManaged(expanded);
        logToggle.setText(expanded ? "收起日志" : "展开日志");
    }

    private void appendLog(String line) {
        Label item = new Label(line);
        item.getStyleClass().add("log-line");
        item.setWrapText(true);
        item.setMaxWidth(Double.MAX_VALUE);
        logLines.getChildren().add(item);
        if (logLines.getChildren().size() > 300) {
            logLines.getChildren().remove(0, logLines.getChildren().size() - 300);
        }
        logSink.accept(line);
        Platform.runLater(() -> logScroll.setVvalue(1.0));
    }

    // ==================================================================== 其他

    private void toggleMirror() {
        boolean enabled = mirrorBox.isSelected();
        client.setMirrorEnabled(enabled);
        if (config != null) {
            config.setModrinthMirror(enabled);
            if (configManager != null) {
                try {
                    configManager.save(config);
                } catch (IOException e) {
                    appendLog("保存镜像开关失败：" + e.getMessage());
                }
            }
        }
        appendLog(nowText() + " [模组] 已" + (enabled ? "启用" : "关闭") + "中国镜像（api.modrinth.com ↔ mod.mcimirror.top）");
        statusSink.accept(enabled ? "Modrinth 已切换为中国镜像优先" : "Modrinth 已切回官方优先");
        if (searchedOnce) {
            runSearch(currentPage);
        }
    }

    /** 页面切换到时调用（刷新实例 + 首屏自动浏览热门 + 执行自动化查询参数）。 */
    public void onShown() {
        refreshInstances();
        if (autoBrowseStarted) {
            return;
        }
        autoBrowseStarted = true;
        String autoQuery = System.getProperty("mclqe.mods.query", "").trim();
        if (!autoQuery.isEmpty()) {
            searchField.setText(autoQuery);
            runSearch(0);
            return;
        }
        // 首屏不再空白：直接浏览热门（按下载量），并记入日志
        browsePopular();
        appendLog(nowText() + " [模组] 首屏自动浏览热门模组（按下载量排序）");
    }

    private String selectedGameVersion() {
        String value = versionBox == null ? ALL : versionBox.getValue();
        return value == null || value.isBlank() ? ALL : value;
    }

    private String selectedLoader() {
        String value = loaderBox == null ? ALL : loaderBox.getValue();
        if (value == null || value.isBlank() || ALL.equals(value)) {
            return "all";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String selectedSort() {
        String value = sortBox == null ? SORT_LABELS.get(0) : sortBox.getValue();
        if (value == null) {
            return "relevance";
        }
        return switch (value) {
            case "下载量" -> "downloads";
            case "最新" -> "newest";
            default -> "relevance";
        };
    }

    private ListCell<Instance> instanceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Instance item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                    setTooltip(null);
                    return;
                }
                setText(item.displayName());
                Tooltip tooltip = new Tooltip(item.displayName() + "（" + item.id() + "）");
                tooltip.setWrapText(true);
                setTooltip(tooltip);
            }
        };
    }

    private Label filterLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mods-filter-label");
        return label;
    }

    private Node avatarNode(String text, double size) {
        StackPane pane = new StackPane();
        pane.setPrefSize(size, size);
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        String letter = text == null || text.isBlank() ? "?" : text.substring(0, 1).toUpperCase(Locale.ROOT);
        int color = Math.floorMod(text == null ? 0 : text.hashCode(), AVATAR_COLORS.length);
        pane.setStyle("-fx-background-color: " + AVATAR_COLORS[color] + "; -fx-background-radius: "
                + Math.round(size / 5.0) + ";");
        Label label = new Label(letter);
        label.setStyle("-fx-text-fill: white; -fx-font-size: " + Math.round(size / 2.2)
                + "px; -fx-font-weight: bold;");
        pane.getChildren().add(label);
        return pane;
    }

    private static String displayCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw) {
            case "adventure" -> "冒险";
            case "cursed" -> "整活";
            case "decoration" -> "装饰";
            case "economy" -> "经济";
            case "equipment" -> "装备";
            case "food" -> "食物";
            case "game-mechanics" -> "机制";
            case "library" -> "前置";
            case "magic" -> "魔法";
            case "management" -> "管理";
            case "minigame" -> "小游戏";
            case "mobs" -> "生物";
            case "optimization" -> "优化";
            case "storage" -> "存储";
            case "technology" -> "科技";
            case "transportation" -> "交通";
            case "utility" -> "实用";
            case "worldgen" -> "世界生成";
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "quilt" -> "Quilt";
            default -> raw;
        };
    }

    private static String preview(String body) {
        String text = plainText(body);
        if (text.isEmpty()) {
            return "（作者未提供简介）";
        }
        if (text.length() > BODY_PREVIEW_LIMIT) {
            return text.substring(0, BODY_PREVIEW_LIMIT) + " ……（已截断，完整内容请到 Modrinth 项目页查看）";
        }
        return text;
    }

    private static String plainText(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String text = markdown;
        text = text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        text = text.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "· ");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("__(.+?)__", "$1");
        text = text.replaceAll("`([^`]*)`", "$1");
        text = text.replace('|', ' ');
        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        return iso.substring(0, 10);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String formatCount(long value) {
        if (value >= 100_000_000L) {
            return String.format(Locale.ROOT, "%.2f 亿", value / 100_000_000.0);
        }
        if (value >= 10_000L) {
            return String.format(Locale.ROOT, "%.1f 万", value / 10_000.0);
        }
        return Long.toString(value);
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private String nowText() {
        LocalDateTime now = LocalDateTime.now();
        return String.format(Locale.ROOT, "%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());
    }

    /** 自验用诊断输出：仅当 -Dmclqe.mods.trace=true 时打到 stdout。 */
    private static void trace(String message) {
        if (Boolean.getBoolean("mclqe.mods.trace")) {
            System.out.println("[MODS-TRACE] " + message);
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }
}
