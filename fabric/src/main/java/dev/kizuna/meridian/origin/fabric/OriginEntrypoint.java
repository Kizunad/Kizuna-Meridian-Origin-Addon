package dev.kizuna.meridian.origin.fabric;

/**
 * 各附属登记自己实现的天赋类型和威胁类型：在 {@code fabric.mod.json} 里声明入口 {@value #KEY}。
 *
 * <p>用入口而不是初始化时调静态方法：读出身数据包之前类型必须收齐，而各模组初始化的先后没有保证。</p>
 */
@FunctionalInterface
public interface OriginEntrypoint {
    String KEY = "meridian_origin";

    void register(OriginRegistrar registrar);
}
