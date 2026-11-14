package dev.kizuna.meridian.origin.fabric.client;

import com.mojang.serialization.MapCodec;
import dev.kizuna.meridian.origin.fabric.OriginEntrypoint;
import dev.kizuna.meridian.origin.fabric.OriginRegistrar;
import dev.kizuna.meridian.origin.fabric.OriginTalentType;
import dev.kizuna.meridian.origin.fabric.OriginThreatType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;

/** 截图用的三张样例出身引用的类型：不带参数、什么都不做，只为让数据包过得了类型核对。 */
public final class OriginSampleTypes implements OriginEntrypoint {
    private static final String NAMESPACE = "meridian_origin_sample";

    @Override
    public void register(OriginRegistrar registrar) {
        registrar.talent(OriginTalentType.standing(Identifier.fromNamespaceAndPath(NAMESPACE, "note"),
                MapCodec.unit(Unit.INSTANCE).codec()));
        registrar.threat(new OriginThreatType<>(Identifier.fromNamespaceAndPath(NAMESPACE, "trouble"),
                MapCodec.unit(Unit.INSTANCE).codec()));
    }
}
