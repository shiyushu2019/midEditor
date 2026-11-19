package com.example.mideditor.ui.frame;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 帮助 → 快捷键：把打包进 jar 的 {@code SHORTCUTS.md} 解到临时目录，再交给系统默认程序打开。
 *
 * <p>
 * 不直接读工作目录下的 {@code docs/SHORTCUTS.md}：程序可能被拷到任何地方、以任何工作目录启动，
 * 只有打进 jar 的那份才保证跟当前运行的版本一致。
 * </p>
 */
public final class ShortcutDoc {

    /** pom 把 {@code docs/SHORTCUTS.md} 复制到 class 路径根目录。 */
    static final String RESOURCE = "/SHORTCUTS.md";
    static final String FILE_NAME = "midEditor-快捷键.md";

    private ShortcutDoc() {
    }

    /** 解出到临时目录（每次覆盖同一份），返回文件路径。 */
    public static Path materialize() throws IOException {
        return materialize(directory());
    }

    /** 指定目录的版本，测试用。 */
    static Path materialize(Path dir) throws IOException {
        try (InputStream in = ShortcutDoc.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("程序里没有找到 SHORTCUTS.md");
            }
            Files.createDirectories(dir);
            Path target = dir.resolve(FILE_NAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** 默认解出位置（打开失败时提示给用户）。 */
    public static Path location() {
        return directory().resolve(FILE_NAME);
    }

    private static Path directory() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "midEditor");
    }
}
