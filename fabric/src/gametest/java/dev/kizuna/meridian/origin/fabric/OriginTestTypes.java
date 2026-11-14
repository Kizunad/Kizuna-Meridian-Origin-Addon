package dev.kizuna.meridian.origin.fabric;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/** 测试用的类型：一种一次性天赋（记下发了什么）、一种常驻天赋、一种威胁；框架本身不带任何具体天赋。 */
public final class OriginTestTypes implements OriginEntrypoint {
    static final String NAMESPACE = "meridian_origin_test";
    static final String FLAKY_TOKEN = "flaky";
    static final int MAX_STAGES = 9;
    /** 每个玩家收到过的礼物记号，按发的先后。 */
    static final Map<UUID, List<String>> GIFTS = new ConcurrentHashMap<>();
    /** 每个玩家收到过的「选定」通知里的出身 ID。 */
    static final Map<UUID, List<Identifier>> NOTICES = new ConcurrentHashMap<>();
    /** 每个玩家每次进服收到的「出身已知」通知：出身 ID，没选时是空串。 */
    static final Map<UUID, List<String>> READY = new ConcurrentHashMap<>();
    private static final Set<UUID> FAILED_ONCE = new HashSet<>();

    static final OriginTalentType<Gift> GIFT = OriginTalentType.oneShot(id("gift"), Gift.CODEC, (player, gift) -> {
        // 记号是 flaky 的礼物对每个玩家第一次发不成，用来测「没发成下次进服再试」。
        if (gift.token().equals(FLAKY_TOKEN) && FAILED_ONCE.add(player.getUUID())) {
            throw new IllegalStateException("测试：这份礼物第一次发不成");
        }
        GIFTS.computeIfAbsent(player.getUUID(), absent -> new ArrayList<>()).add(gift.token());
    });
    static final OriginTalentType<Mark> MARK = OriginTalentType.standing(id("mark"), Mark.CODEC);
    static final OriginThreatType<Feud> FEUD = new OriginThreatType<>(id("feud"), Feud.CODEC);

    static {
        Origins.CHOSEN.register((player, origin) -> NOTICES
                .computeIfAbsent(player.getUUID(), absent -> new ArrayList<>()).add(origin.id()));
        Origins.READY.register((player, origin) -> READY.computeIfAbsent(player.getUUID(), absent -> new ArrayList<>())
                .add(origin.map(known -> known.id().toString()).orElse("")));
    }

    @Override
    public void register(OriginRegistrar registrar) {
        registrar.talent(GIFT);
        registrar.talent(MARK);
        registrar.threat(FEUD);
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    /** 一次性天赋的参数：一个记号。 */
    record Gift(String token) {
        static final Codec<Gift> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("token").forGetter(Gift::token)).apply(instance, Gift::new));
    }

    /** 常驻天赋的参数：一个数，省略时是 0。 */
    record Mark(int value) {
        static final Codec<Mark> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("value", 0).forGetter(Mark::value)).apply(instance, Mark::new));
    }

    /** 威胁的参数：拨数，0～9。 */
    record Feud(int stages) {
        static final Codec<Feud> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, MAX_STAGES).fieldOf("stages").forGetter(Feud::stages)).apply(instance, Feud::new));
    }
}
