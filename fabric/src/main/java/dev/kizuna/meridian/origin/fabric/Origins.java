package dev.kizuna.meridian.origin.fabric;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** 出身对别的附属公开的查询口：这个玩家是什么出身、带着哪些天赋和威胁参数。只在服务端用。 */
public final class Origins {
    /** 玩家选定出身、一次性天赋发过以后触发；调试指令直接定出身时也触发。 */
    public static final Event<Chosen> CHOSEN = EventFactory.createArrayBacked(Chosen.class,
            listeners -> (player, origin) -> {
                for (Chosen listener : listeners) {
                    listener.onChosen(player, origin);
                }
            });

    /**
     * 玩家进服、身体读到以后触发一次：这时 {@link #chosen} 才查得到。没选出身的玩家也触发，出身为空。
     * 要给已经选过出身的老玩家接上常驻的东西，听这个，不用自己等。
     */
    public static final Event<Ready> READY = EventFactory.createArrayBacked(Ready.class,
            listeners -> (player, origin) -> {
                for (Ready listener : listeners) {
                    listener.onReady(player, origin);
                }
            });

    private Origins() {
        // 只有静态查询。
    }

    /** 这个在线玩家选定的出身；没选、身体还没读到、或那个出身已不在数据包里时为空。 */
    public static Optional<Origin> chosen(ServerPlayer player) {
        return OriginFabric.service().chosen(player);
    }

    /** 常驻类天赋：管那块机制的附属在自己算的时候来问。读的是内存里的缓存，可以每 tick 调。 */
    public static <P> List<P> talents(ServerPlayer player, OriginTalentType<P> type) {
        return chosen(player).map(origin -> origin.talents(type)).orElse(List.of());
    }

    /** 这个玩家的出身带着的某一类威胁参数。 */
    public static <P> List<P> threats(ServerPlayer player, OriginThreatType<P> type) {
        return chosen(player).map(origin -> origin.threats(type)).orElse(List.of());
    }

    /** 数据包里所有出身的某一类威胁参数，按出身 ID；没有这类威胁的出身不出现。服务器启动以后才有内容。 */
    public static <P> Map<Identifier, List<P>> threatsByOrigin(OriginThreatType<P> type) {
        Map<Identifier, List<P>> found = new TreeMap<>();
        all().forEach((id, origin) -> {
            List<P> params = origin.threats(type);
            if (!params.isEmpty()) {
                found.put(id, params);
            }
        });
        return found;
    }

    /** 数据包里的全部出身，按 ID。服务器启动以后才有内容。 */
    public static Map<Identifier, Origin> all() {
        return OriginFabric.definitions().all();
    }

    /** 出身显示名的语言键。 */
    public static String nameKey(Identifier origin) {
        return "origin." + origin.getNamespace() + "." + origin.getPath() + ".name";
    }

    /** 听 {@link #READY}。 */
    @FunctionalInterface
    public interface Ready {
        void onReady(ServerPlayer player, Optional<Origin> origin);
    }

    /** 听 {@link #CHOSEN}。 */
    @FunctionalInterface
    public interface Chosen {
        void onChosen(ServerPlayer player, Origin origin);
    }
}
