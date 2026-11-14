package dev.kizuna.meridian.origin.fabric;

import com.mojang.serialization.Codec;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一种天赋：ID、出身 JSON 里 {@code params} 的解析器，以及选定出身时做一次的动作。
 *
 * <p>常驻类天赋不给动作：管那块机制的附属在自己算的时候用 {@link Origins#talents} 来问。</p>
 */
public record OriginTalentType<P>(Identifier id, Codec<P> params, Optional<Grant<P>> grant) {
    public OriginTalentType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(grant, "grant");
    }

    public static <P> OriginTalentType<P> standing(Identifier id, Codec<P> params) {
        // 常驻类：没有一次性动作。
        return new OriginTalentType<>(id, params, Optional.empty());
    }

    public static <P> OriginTalentType<P> oneShot(Identifier id, Codec<P> params, Grant<P> grant) {
        // 一次性：选定出身时发一次。
        return new OriginTalentType<>(id, params, Optional.of(grant));
    }

    /**
     * 选定出身时发这份天赋。发成以后记在玩家记录里，不会再调；抛异常就不记，下次进服再试，
     * 所以实现要能重复执行而不出错（例如「已经会这一卷」不算失败）。
     */
    @FunctionalInterface
    public interface Grant<P> {
        void grant(ServerPlayer player, P params);
    }
}
