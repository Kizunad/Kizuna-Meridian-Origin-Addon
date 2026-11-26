package dev.kizuna.meridian.origin.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

/**
 * 数据包里的全部出身：{@code data/<命名空间>/meridian_origin/origin/<名字>.json}，出身 ID 是「命名空间:名字」。
 *
 * <p>服务器启动时读一遍，有一份不对就启动失败；{@code /reload} 成功后重读，重读失败时保留上一份并打错误日志。</p>
 */
final class OriginDefinitions {
    private static final String DIRECTORY = "meridian_origin/origin";
    private static final String RETIRED = "meridian_origin/retired_origin";
    private static final String JSON_SUFFIX = ".json";
    private static final Logger LOGGER = System.getLogger("meridian_origin");
    private final OriginParser parser;
    private record Catalog(Map<Identifier, Origin> origins, Set<String> retired) { }

    private Catalog loaded = new Catalog(Map.of(), Set.of());

    OriginDefinitions(OriginTypes types) {
        this.parser = new OriginParser(types);
    }

    void start(MinecraftServer server) {
        // 开服时出身数据不对直接失败，不带着半份数据跑。
        loaded = load(server);
    }

    void reload(MinecraftServer server) {
        // 运行中重载：新数据不对就留着旧的，玩家已有的出身不会突然失效。
        try {
            loaded = load(server);
        } catch (RuntimeException invalid) {
            LOGGER.log(Level.ERROR, "出身数据重载失败，沿用重载前的出身：" + invalid.getMessage(), invalid);
        }
    }

    void stop() {
        loaded = new Catalog(Map.of(), Set.of());
    }

    Map<Identifier, Origin> all() {
        // 按出身 ID 排序，补全和调试输出的顺序固定。
        return loaded.origins();
    }

    boolean retired(String chosen) {
        return loaded.retired().contains(chosen);
    }

    private Catalog load(MinecraftServer server) {
        // 逐份解析；哪一份不对，报错里带它的文件 ID。
        Map<Identifier, Resource> files = server.getResourceManager().listResources(DIRECTORY,
                id -> id.getPath().endsWith(JSON_SUFFIX));
        Map<Identifier, Origin> origins = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            Identifier id = originId(DIRECTORY, file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Origin origin = parser.parse(id, json);
                if (!BuiltInRegistries.ITEM.containsKey(origin.icon())) {
                    throw new IllegalArgumentException("icon 不是已登记的物品：" + origin.icon());
                }
                origins.put(id, origin);
            } catch (IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("出身 " + id + "（" + file.getKey() + "）：" + invalid.getMessage(),
                        invalid);
            }
        }
        return new Catalog(Map.copyOf(origins), retired(server, origins));
    }

    private static Set<String> retired(MinecraftServer server, Map<Identifier, Origin> origins) {
        // 每个空对象按资源路径声明一个退役 ID，同一 ID 不能同时作为候选。
        var files = server.getResourceManager().listResources(RETIRED, id -> id.getPath().endsWith(JSON_SUFFIX));
        Set<String> ids = new HashSet<>();
        for (var file : files.entrySet()) {
            Identifier id = originId(RETIRED, file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                var marker = JsonParser.parseReader(reader);
                if (!marker.isJsonObject() || marker.getAsJsonObject().size() != 0 || origins.containsKey(id)) {
                    throw new IllegalArgumentException("声明须为空对象且出身不能同时存在：" + id);
                }
                ids.add(id.toString());
            } catch (IOException | RuntimeException invalid) {
                throw new IllegalArgumentException("退役出身 " + file.getKey(), invalid);
            }
        }
        return Set.copyOf(ids);
    }

    private static Identifier originId(String directory, Identifier file) {
        // 资源相对于出身或退役目录的路径就是出身名。
        String path = file.getPath();
        String name = path.substring(directory.length() + 1, path.length() - JSON_SUFFIX.length());
        return Identifier.fromNamespaceAndPath(file.getNamespace(), name);
    }
}
