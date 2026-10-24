package com.mclqe.launcher.ui;

import com.mclqe.launcher.model.VersionManifest;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 版本列表单元格（两行形态，对应 HMCL 的 two-line-list-item）：
 * 第一行为版本 ID 与「已安装 / 未安装」状态标签，第二行为类型标签 + 发布时间
 * （本地已安装版本额外显示继承信息）。
 */
public class VersionListCell extends ListCell<VersionManifest.VersionEntry> {

    private final Label title = new Label();
    private final Label stateTag = new Label();
    private final Label tag = new Label();
    private final Label subtitle = new Label();
    private final HBox container;

    public VersionListCell() {
        title.getStyleClass().add("two-line-title");
        stateTag.getStyleClass().add("tag");
        tag.getStyleClass().add("tag");
        subtitle.getStyleClass().add("two-line-subtitle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox firstLine = new HBox(8, title, spacer, stateTag);
        firstLine.setAlignment(Pos.CENTER_LEFT);

        HBox secondLine = new HBox(8, tag, subtitle);
        secondLine.setAlignment(Pos.CENTER_LEFT);

        VBox text = new VBox(4, firstLine, secondLine);
        HBox.setHgrow(text, Priority.ALWAYS);

        container = new HBox(10, text);
        container.getStyleClass().add("two-line-list-item");
        container.setAlignment(Pos.CENTER_LEFT);

        getStyleClass().add("version-cell");
    }

    @Override
    protected void updateItem(VersionManifest.VersionEntry entry, boolean empty) {
        super.updateItem(entry, empty);
        if (empty || entry == null) {
            setGraphic(null);
            setText(null);
            return;
        }
        title.setText(entry.getId());
        tag.setText(LauncherApp.typeLabel(entry.getType()));

        stateTag.getStyleClass().removeAll("tag-installed", "tag-missing", "tag-local", "tag-damaged");
        if (entry.isInstalled() && entry.isDamaged()) {
            // 损坏版本单独标注，避免与完好的已安装版本混淆
            stateTag.setText("损坏");
            stateTag.getStyleClass().add("tag-damaged");
        } else if (entry.isInstalled() && entry.isIncomplete()) {
            // 只有版本描述、缺本体 jar，属于未安装而非损坏
            stateTag.setText("未安装");
            stateTag.getStyleClass().add("tag-missing");
        } else if (entry.isInstalled()) {
            stateTag.setText(entry.isLocalOnly() ? "本地" : "已安装");
            stateTag.getStyleClass().add(entry.isLocalOnly() ? "tag-local" : "tag-installed");
        } else {
            stateTag.setText("未安装");
            stateTag.getStyleClass().add("tag-missing");
        }

        String base = LauncherApp.formatReleaseTime(entry.getReleaseTime());
        String note = entry.getLocalNote();
        subtitle.setText(note == null || note.isBlank() ? base : base + " · " + note);

        setGraphic(container);
        setText(null);
    }
}
