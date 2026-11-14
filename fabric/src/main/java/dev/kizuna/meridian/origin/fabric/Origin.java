package dev.kizuna.meridian.origin.fabric;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** 数据包里的一个出身：抽选权重、界面图标、互斥组，以及它带的天赋和威胁。 */
public record Origin(Identifier id, int weight, Identifier icon, Optional<String> group,
                     List<Talent<?>> talents, List<Threat<?>> threats) {
    public Origin {
        // 加载后只读。
        talents = List.copyOf(talents);
        threats = List.copyOf(threats);
    }

    /** 一份天赋：类型和按类型解析好的参数。 */
    public record Talent<P>(OriginTalentType<P> type, P params) { }

    /** 一份威胁：类型和按类型解析好的参数。 */
    public record Threat<P>(OriginThreatType<P> type, P params) { }

    public <P> List<P> talents(OriginTalentType<P> type) {
        // 这个出身里属于这一类型的天赋参数，按 JSON 里写的先后。
        return talents.stream().filter(talent -> talent.type().id().equals(type.id()))
                .map(talent -> Origin.<P>cast(talent.params()))
                .toList();
    }

    public <P> List<P> threats(OriginThreatType<P> type) {
        // 这个出身里属于这一类型的威胁参数。
        return threats.stream().filter(threat -> threat.type().id().equals(type.id()))
                .map(threat -> Origin.<P>cast(threat.params()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static <P> P cast(Object params) {
        // 同一个 ID 只登记一个类型，按 ID 认出来的参数必然是这个类型解析器的产物。
        return (P) params;
    }
}
