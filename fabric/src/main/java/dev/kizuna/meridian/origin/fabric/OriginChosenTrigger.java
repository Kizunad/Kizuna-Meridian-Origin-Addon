package dev.kizuna.meridian.origin.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 进度触发器 {@code meridian_origin:chosen}：玩家定下出身时触发。
 *
 * <p>条件写 {@code "origin": "<出身 ID>"} 只认这一个出身，省略就是任意出身。</p>
 */
public final class OriginChosenTrigger extends SimpleCriterionTrigger<OriginChosenTrigger.Instance> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("meridian_origin", "chosen");
    static final OriginChosenTrigger TRIGGER = new OriginChosenTrigger();

    private OriginChosenTrigger() {
        // 全局只有一个实例，由注册表持有。
    }

    static void register() {
        // 触发器类型在注册表冻结前登记，进度文件按 ID 引用。
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, ID, TRIGGER);
    }

    @Override
    public Codec<Instance> codec() {
        // 进度文件里的条件格式。
        return Instance.CODEC;
    }

    void trigger(ServerPlayer player, Identifier origin) {
        // 出身写进记录以后调用；原版只检查这名玩家还没完成的进度。
        trigger(player, instance -> instance.matches(origin));
    }

    /** 一条进度条件：可选的玩家谓词和出身 ID。 */
    public record Instance(Optional<ContextAwarePredicate> player, Optional<Identifier> origin)
            implements SimpleCriterionTrigger.SimpleInstance {
        static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                Identifier.CODEC.optionalFieldOf("origin").forGetter(Instance::origin)).apply(instance, Instance::new));

        boolean matches(Identifier chosen) {
            // 没写出身时任意出身都算。
            return origin.map(chosen::equals).orElse(true);
        }
    }
}
