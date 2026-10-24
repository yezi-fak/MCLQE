package com.mclqe.launcher.ui;

import javafx.scene.layout.Region;

/**
 * 图标工厂。
 * <p>所有图标均为「CSS 驱动的纯色 Region」：几何形状由 app.css 中的 {@code -fx-shape} 描述，
 * 填充色使用 {@code -monet-*} 变量，因此不需要任何字体、图片或 SVG 资源文件，
 * 并且能随主题（浅色 / 深色）自动换色。HMCL 使用 SVGPath 实现同样的效果。</p>
 */
public final class Icons {

    private Icons() {
    }

    /** 创建 16px 图标。 */
    public static Region of(String name) {
        return of(name, 16);
    }

    /**
     * 创建指定尺寸的图标。
     *
     * @param name 图标名（对应 app.css 中的 {@code .icon-<name>}）
     * @param size 边长（像素）
     */
    public static Region of(String name, double size) {
        Region region = new Region();
        region.getStyleClass().add("icon");
        region.getStyleClass().add(name.startsWith("icon-") ? name : "icon-" + name);
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
        region.setMouseTransparent(true);
        return region;
    }
}
