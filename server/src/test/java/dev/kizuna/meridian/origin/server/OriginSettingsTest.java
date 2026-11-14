package dev.kizuna.meridian.origin.server;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 出身配置：默认值、首次导出、已有文件不覆盖、写错拒绝。 */
public final class OriginSettingsTest {
    private static final String VALID = "selection = false\noffer_count = 5\ndisabled = [\"t:orphan\"]\n";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void firstLoadExportsDefaultsAndLaterLoadsKeepEdits() throws IOException {
        // 第一次写出默认文件：弹界面、抽三个、不禁用任何出身；改过的文件照改过的读，不被覆盖。
        Path path = folder.getRoot().toPath().resolve("config/meridian-origin.toml");
        assertEquals(new OriginSettings(true, 3, Set.of()), OriginSettings.load(path));
        assertTrue(Files.exists(path));
        Files.writeString(path, VALID);
        assertEquals(new OriginSettings(false, 5, Set.of("t:orphan")), OriginSettings.load(path));
        assertEquals(VALID, Files.readString(path));
    }

    @Test
    public void wrongValuesAndUnknownKeysAreRejected() {
        // 不认识的键、缺键、张数越界、禁用表里不是出身 ID，都拒绝启动。
        String count = "offer_count = 5";
        for (String broken : new String[]{VALID + "offers = 3\n", "offer_count = 3\ndisabled = []\n",
                VALID.replace(count, "offer_count = 0"), VALID.replace(count, "offer_count = 9"),
                VALID.replace(count, "offer_count = 2.5"), VALID.replace("\"t:orphan\"", "\"orphan\""),
                VALID.replace("[\"t:orphan\"]", "\"t:orphan\""), VALID.replace("selection = false", "selection = 1"),
                "selection = "}) {
            assertThrows(broken, IllegalArgumentException.class, () -> OriginSettings.parse(broken));
        }
        assertFalse(OriginSettings.parse(VALID).selection());
    }
}
