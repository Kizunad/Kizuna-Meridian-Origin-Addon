package dev.kizuna.meridian.origin.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * 把一份出身 JSON 解析成 {@link Origin}：
 * {@code { "weight": 10, "icon": "<物品 ID>", "group": "<可省>", "talents": [{ "type", "params" }], "threats": [...] }}。
 *
 * <p>每个 {@code type} 都要已经登记，{@code params} 交给登记方的解析器；有一处不对就抛异常，报错里写明是哪一项。</p>
 */
final class OriginParser {
    static final int MAX_LINES = 8;
    private static final Set<String> KEYS = Set.of("weight", "icon", "group", "talents", "threats");
    private static final Set<String> ENTRY_KEYS = Set.of("type", "params");
    private final OriginTypes types;

    OriginParser(OriginTypes types) {
        this.types = types;
    }

    Origin parse(Identifier id, JsonElement json) {
        // 不认识的键拒绝，免得写错键名还以为生效了。
        JsonObject object = object(json, "出身");
        requireKeys(object, KEYS, "出身");
        int weight = weight(object);
        Identifier icon = identifier(string(object, "icon"), "icon");
        Optional<String> group = object.has("group") ? Optional.of(string(object, "group")) : Optional.empty();
        List<Origin.Talent<?>> talents = new ArrayList<>();
        for (Entry entry : entries(object, "talents")) {
            OriginTalentType<?> type = types.talent(entry.type())
                    .orElseThrow(() -> new IllegalArgumentException(entry.where() + " 的天赋类型没有登记：" + entry.type()));
            talents.add(talent(type, entry));
        }
        List<Origin.Threat<?>> threats = new ArrayList<>();
        for (Entry entry : entries(object, "threats")) {
            OriginThreatType<?> type = types.threat(entry.type())
                    .orElseThrow(() -> new IllegalArgumentException(entry.where() + " 的威胁类型没有登记：" + entry.type()));
            threats.add(threat(type, entry));
        }
        return new Origin(id, weight, icon, group, talents, threats);
    }

    private static <P> Origin.Talent<P> talent(OriginTalentType<P> type, Entry entry) {
        return new Origin.Talent<>(type, params(type.params(), entry));
    }

    private static <P> Origin.Threat<P> threat(OriginThreatType<P> type, Entry entry) {
        return new Origin.Threat<>(type, params(type.params(), entry));
    }

    private static <P> P params(Codec<P> codec, Entry entry) {
        // 参数的形状和范围由登记方的解析器核对。
        return codec.parse(JsonOps.INSTANCE, entry.params()).getOrThrow(
                message -> new IllegalArgumentException(entry.where() + " 的参数不对：" + message));
    }

    private static int weight(JsonObject object) {
        // 权重是正整数。
        JsonElement value = object.get("weight");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || value.getAsDouble() != Math.rint(value.getAsDouble()) || value.getAsDouble() < 1
                || value.getAsDouble() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("weight 必须是正整数");
        }
        return value.getAsInt();
    }

    private static List<Entry> entries(JsonObject object, String key) {
        // 天赋、威胁各是一个数组，可以为空；每项写 type，params 可省（按空对象解析）。
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " 必须是数组");
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() > MAX_LINES) {
            throw new IllegalArgumentException(key + " 至多 " + MAX_LINES + " 项");
        }
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String where = key + "[" + index + "]";
            JsonObject entry = object(array.get(index), where);
            requireKeys(entry, ENTRY_KEYS, where);
            JsonElement params = entry.has("params") ? entry.get("params") : new JsonObject();
            entries.add(new Entry(where, identifier(string(entry, "type"), where + ".type"), params));
        }
        return entries;
    }

    private static JsonObject object(JsonElement json, String where) {
        if (json == null || !json.isJsonObject()) {
            throw new IllegalArgumentException(where + " 必须是 JSON 对象");
        }
        return json.getAsJsonObject();
    }

    private static void requireKeys(JsonObject object, Set<String> allowed, String where) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(where + " 里有不认识的键：" + key);
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " 必须是非空字符串");
        }
        return value.getAsString();
    }

    private static Identifier identifier(String text, String where) {
        // 类型和图标都要带命名空间，不默认成 minecraft。
        Identifier id = text.contains(":") ? Identifier.tryParse(text) : null;
        if (id == null) {
            throw new IllegalArgumentException(where + " 必须是带命名空间的 ID：" + text);
        }
        return id;
    }

    /** 数组里的一项：在哪、什么类型、参数原文。 */
    private record Entry(String where, Identifier type, JsonElement params) { }
}
