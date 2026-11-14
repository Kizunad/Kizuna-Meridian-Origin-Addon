package dev.kizuna.meridian.origin.fabric;

/** 收各附属的类型登记；同一个 ID 登记两次直接失败。 */
public interface OriginRegistrar {
    <P> void talent(OriginTalentType<P> type);

    <P> void threat(OriginThreatType<P> type);
}
