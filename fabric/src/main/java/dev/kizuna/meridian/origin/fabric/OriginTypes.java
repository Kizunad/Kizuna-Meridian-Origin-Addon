package dev.kizuna.meridian.origin.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/** 各附属登记的天赋类型和威胁类型；模组初始化时从入口收一遍，之后只读。 */
final class OriginTypes implements OriginRegistrar {
    private final Map<Identifier, OriginTalentType<?>> talents = new LinkedHashMap<>();
    private final Map<Identifier, OriginThreatType<?>> threats = new LinkedHashMap<>();

    static OriginTypes collect() {
        // 入口由 Fabric 按清单收齐，和各模组初始化的先后无关；登记出错带上是哪个模组。
        var types = new OriginTypes();
        for (var container : FabricLoader.getInstance().getEntrypointContainers(OriginEntrypoint.KEY,
                OriginEntrypoint.class)) {
            try {
                container.getEntrypoint().register(types);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("出身类型登记失败：" + container.getProvider().getMetadata().getId()
                        + "：" + failure.getMessage(), failure);
            }
        }
        return types;
    }

    @Override
    public <P> void talent(OriginTalentType<P> type) {
        // 同一个 ID 只能有一个归属。
        if (talents.putIfAbsent(type.id(), type) != null) {
            throw new IllegalArgumentException("天赋类型已登记：" + type.id());
        }
    }

    @Override
    public <P> void threat(OriginThreatType<P> type) {
        if (threats.putIfAbsent(type.id(), type) != null) {
            throw new IllegalArgumentException("威胁类型已登记：" + type.id());
        }
    }

    Optional<OriginTalentType<?>> talent(Identifier id) {
        return Optional.ofNullable(talents.get(id));
    }

    Optional<OriginThreatType<?>> threat(Identifier id) {
        return Optional.ofNullable(threats.get(id));
    }
}
