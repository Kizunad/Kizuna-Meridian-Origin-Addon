package dev.kizuna.meridian.origin.server;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** meridian-origin.toml：要不要弹选择界面、一次抽几个、哪些出身不参与抽选。 */
public record OriginSettings(boolean selection, int offerCount, Set<String> disabled) {
    public static final String DEFAULT_RESOURCE = "/meridian-origin.toml";
    public static final int MAX_OFFER_COUNT = 8;
    private static final Set<String> KEYS = Set.of("selection", "offer_count", "disabled");

    public OriginSettings {
        // 配置加载后只读。
        disabled = Set.copyOf(disabled);
    }

    public static OriginSettings load(Path path) {
        // 首次启动导出可编辑默认值；以后读取已有配置，不重写运营方的调整。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                try (InputStream input = OriginSettings.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (input == null) {
                        throw OriginErrors.invalid(OriginErrors.SETTINGS_RESOURCE, DEFAULT_RESOURCE);
                    }
                    Files.copy(input, path);
                }
            }
            return parse(Files.readString(path));
        } catch (IOException failure) {
            throw new IllegalStateException(OriginErrors.SETTINGS_READ + ": " + path, failure);
        }
    }

    public static OriginSettings parse(String text) {
        // 三个键都要写；不认识的键拒绝，免得写错键名还以为生效了。
        TomlParseResult config = Toml.parse(text);
        if (config.hasErrors()) {
            throw OriginErrors.invalid(OriginErrors.SETTINGS_FORMAT, config.errors().toString());
        }
        for (String key : config.keySet()) {
            if (!KEYS.contains(key)) {
                throw OriginErrors.invalid(OriginErrors.SETTINGS_KEY, key);
            }
        }
        if (!config.isBoolean("selection")) {
            throw OriginErrors.invalid(OriginErrors.SETTINGS_SELECTION, String.valueOf(config.get("selection")));
        }
        return new OriginSettings(config.getBoolean("selection"), offerCount(config), disabled(config));
    }

    private static int offerCount(TomlParseResult config) {
        // 一屏放得下的张数以内。
        if (!config.isLong("offer_count")) {
            throw OriginErrors.invalid(OriginErrors.SETTINGS_OFFER_COUNT, String.valueOf(config.get("offer_count")));
        }
        long count = config.getLong("offer_count");
        if (count < 1 || count > MAX_OFFER_COUNT) {
            throw OriginErrors.invalid(OriginErrors.SETTINGS_OFFER_COUNT, String.valueOf(count));
        }
        return (int) count;
    }

    private static Set<String> disabled(TomlParseResult config) {
        // 每项都是带命名空间的出身 ID；数据包里有没有这个出身不在这里查。
        if (!config.isArray("disabled")) {
            throw OriginErrors.invalid(OriginErrors.SETTINGS_DISABLED, String.valueOf(config.get("disabled")));
        }
        TomlArray array = config.getArray("disabled");
        var ids = new LinkedHashSet<String>();
        for (int index = 0; index < array.size(); index++) {
            if (!(array.get(index) instanceof String id)) {
                throw OriginErrors.invalid(OriginErrors.SETTINGS_DISABLED, String.valueOf(array.get(index)));
            }
            ids.add(OriginIds.require(id));
        }
        return ids;
    }
}
