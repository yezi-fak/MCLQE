package com.mclqe.launcher.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 自绘窗口标题栏（对应 HMCL 的 Decorator / jfx-tool-bar）。
 * <p>高度 40px：左侧应用标识与标题，右侧最小化 / 最大化 / 关闭三个窗口按钮；
 * 支持按住标题栏拖动窗口、双击切换最大化。</p>
 */
public class TitleBar extends HBox {

    private final Stage stage;
    private final Runnable onClose;

    private double dragOffsetX;
    private double dragOffsetY;

    private double restoreX;
    private double restoreY;
    private double restoreWidth;
    private double restoreHeight;
    private boolean maximized;

    public TitleBar(Stage stage, String title, Runnable onClose) {
        this.stage = stage;
        this.onClose = onClose;

        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        Region logo = new Region();
        logo.getStyleClass().add("app-logo");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("title-bar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton("window-min", "icon-minimize", () -> stage.setIconified(true));
        Button maximize = windowButton("window-max", "icon-maximize", this::toggleMaximize);
        Button close = windowButton("window-close", "icon-close", this::requestClose);

        getChildren().addAll(logo, titleLabel, spacer, minimize, maximize, close);

        setOnMousePressed(event -> {
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        setOnMouseDragged(event -> {
            if (maximized || stage.isIconified()) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleMaximize();
            }
        });
    }

    private Button windowButton(String styleClass, String iconClass, Runnable action) {
        Button button = new Button();
        button.getStyleClass().addAll("window-button", styleClass);
        button.setGraphic(Icons.of(iconClass, 12));
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }

    /** 无边框窗口下手动实现最大化 / 还原。 */
    private void toggleMaximize() {
        if (maximized) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
            return;
        }
        restoreX = stage.getX();
        restoreY = stage.getY();
        restoreWidth = stage.getWidth();
        restoreHeight = stage.getHeight();

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        maximized = true;
    }

    private void requestClose() {
        if (onClose != null) {
            onClose.run();
        }
    }
}
