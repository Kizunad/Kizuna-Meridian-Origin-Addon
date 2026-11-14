package dev.kizuna.meridian.origin.fabric;

import com.mojang.serialization.Codec;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** 一种天生的威胁：ID 和出身 JSON 里 {@code params} 的解析器；怎么兑现由登记它的附属自己管。 */
public record OriginThreatType<P>(Identifier id, Codec<P> params) {
    public OriginThreatType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(params, "params");
    }
}
