package dev.kizuna.meridian.origin.fabric;

import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginCard;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.origin.server.OriginDraw;
import dev.kizuna.meridian.origin.server.OriginRecords;
import dev.kizuna.meridian.origin.server.OriginSettings;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 出身的服务端流程：进服后等到身体可读就抽候选、弹界面；玩家选定后写记录、发一次性天赋、通知各附属。
 *
 * <p>记录只存在玩家身体里。这里只为在线玩家留一份「选了哪个」的缓存，供常驻类天赋每 tick 查询。</p>
 */
final class OriginService {
    private static final Logger LOGGER = System.getLogger("meridian_origin");
    private static final long MILLIS_PER_TICK = 50;
    private final OriginSettings settings;
    private final OriginDefinitions definitions;
    private final OriginBodies bodies;
    /** 进了服、身体还没读到的玩家。 */
    private final Set<UUID> waiting = new LinkedHashSet<>();
    /** 身体已读到的在线玩家选定的出身；没选的是空。 */
    private final Map<UUID, Optional<Identifier>> chosen = new HashMap<>();

    OriginService(OriginSettings settings, OriginDefinitions definitions, OriginBodies bodies) {
        this.settings = settings;
        this.definitions = definitions;
        this.bodies = bodies;
    }

    void join(ServerPlayer player) {
        // 身体由别的模组在进服时建，回调先后没有保证；先排上，tick 里读到身体再处理。
        chosen.remove(player.getUUID());
        waiting.add(player.getUUID());
    }

    void leave(ServerPlayer player) {
        waiting.remove(player.getUUID());
        chosen.remove(player.getUUID());
    }

    void tick(MinecraftServer server) {
        // 每 tick 试一次还在等的玩家，读到身体为止。
        for (UUID id : List.copyOf(waiting)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || settle(player)) {
                waiting.remove(id);
            }
        }
    }

    Optional<Origin> chosen(ServerPlayer player) {
        // 没读到身体、没选、或那个出身已不在数据包里，都是空。
        return chosen.getOrDefault(player.getUUID(), Optional.empty())
                .map(id -> definitions.all().get(id));
    }

    Optional<OriginRecord> record(ServerPlayer player) {
        // 调试指令直接读身体上的记录。
        return bodies.read(player).map(OriginRecords::read);
    }

    boolean choose(ServerPlayer player, Identifier origin) {
        // 玩家在界面上选定：只能选一次、只能选抽到的、出身还得在数据包里；不合规矩就不动记录。
        if (!settings.selection() || !chosen.containsKey(player.getUUID()) || !definitions.all().containsKey(origin)) {
            return false;
        }
        long now = now(player);
        boolean[] accepted = new boolean[1];
        update(player, record -> {
            Optional<OriginRecord> next = OriginRecords.chosen(record, origin.toString(), now);
            accepted[0] = next.isPresent();
            return next.orElse(record);
        });
        if (accepted[0]) {
            settled(player, origin);
        }
        return accepted[0];
    }

    boolean assign(ServerPlayer player, Identifier origin) {
        // 调试指令直接定出身，不看候选；照常发一次性天赋、通知各附属。
        if (!definitions.all().containsKey(origin)) {
            return false;
        }
        long now = now(player);
        if (update(player, record -> OriginRecords.assigned(record, origin.toString(), now)).isEmpty()) {
            return false;
        }
        settled(player, origin);
        return true;
    }

    boolean redraw(ServerPlayer player) {
        // 调试指令：清掉候选和选定，换一轮种子重抽，再弹界面。
        if (update(player, OriginRecords::redrawn).isEmpty()) {
            return false;
        }
        join(player);
        return true;
    }

    boolean open(ServerPlayer player) {
        // 玩家自己要看：选过就给只读的那一张，没选就把候选再发一遍。
        Optional<OriginRecord> record = record(player);
        return record.isPresent() && offer(player, record.get());
    }

    private boolean settle(ServerPlayer player) {
        // 身体读到了：没选的补齐候选并弹界面，选过的补发没发成的一次性天赋。
        Optional<OriginRecord> record;
        try {
            record = update(player, current -> {
                if (definitions.retired(current.getChosen())) {
                    return offered(player, OriginRecords.redrawn(current));
                }
                return offered(player, current);
            });
        } catch (RuntimeException failure) {
            LOGGER.log(Level.ERROR, "玩家 " + player.getScoreboardName() + " 的出身记录读写失败，这次进服不处理出身", failure);
            return true;
        }
        if (record.isEmpty()) {
            return false;
        }
        Optional<Identifier> origin = Optional.of(record.get().getChosen()).filter(id -> !id.isEmpty())
                .map(Identifier::parse);
        chosen.put(player.getUUID(), origin);
        if (origin.isEmpty()) {
            if (settings.selection()) {
                offer(player, record.get());
            }
        } else if (definitions.all().containsKey(origin.get())) {
            grant(player, definitions.all().get(origin.get()));
        } else {
            LOGGER.log(Level.WARNING, "玩家 " + player.getScoreboardName() + " 选定的出身 " + origin.get()
                    + " 已不在数据包里，它的天赋和威胁不生效");
        }
        Origins.READY.invoker().onReady(player, chosen(player));
        return true;
    }

    private OriginRecord offered(ServerPlayer player, OriginRecord record) {
        // 没选的玩家：候选里还在的留着，缺的按固定种子补齐；选过的、或配置关了选择的，不动。
        if (!settings.selection() || !record.getChosen().isEmpty()) {
            return record;
        }
        List<OriginDraw.Candidate> candidates = definitions.all().values().stream()
                .filter(origin -> !settings.disabled().contains(origin.id().toString()))
                .map(origin -> new OriginDraw.Candidate(origin.id().toString(), origin.weight(), origin.group()))
                .toList();
        long seed = OriginDraw.seed(player.level().getServer().overworld().getSeed(), player.getUUID(),
                record.getDrawRound());
        return OriginRecords.offered(record,
                OriginDraw.refill(record.getOfferedList(), candidates, settings.offerCount(), seed));
    }

    private void settled(ServerPlayer player, Identifier id) {
        // 出身已经写进记录：记缓存、发一次性天赋，然后通知进度和各附属。
        Origin origin = definitions.all().get(id);
        chosen.put(player.getUUID(), Optional.of(id));
        grant(player, origin);
        OriginChosenTrigger.TRIGGER.trigger(player, id);
        Origins.CHOSEN.invoker().onChosen(player, origin);
    }

    private void grant(ServerPlayer player, Origin origin) {
        // 一次性天赋逐条发：发成的记下来，抛异常的留到下次进服再试。天赋自己可能改身体，所以发完再读身体记账。
        OriginRecord record = record(player).orElseThrow();
        List<String> done = new ArrayList<>();
        for (int index = 0; index < origin.talents().size(); index++) {
            String key = OriginRecords.grantKey(origin.id().toString(), index);
            if (!record.getGrantedList().contains(key) && granted(player, origin.talents().get(index), key)) {
                done.add(key);
            }
        }
        if (!done.isEmpty()) {
            update(player, current -> {
                OriginRecord next = current;
                for (String key : done) {
                    next = OriginRecords.granted(next, key);
                }
                return next;
            });
        }
    }

    private <P> boolean granted(ServerPlayer player, Origin.Talent<P> talent, String key) {
        // 常驻类天赋没有动作，不用记账。
        if (talent.type().grant().isEmpty()) {
            return false;
        }
        try {
            talent.type().grant().get().grant(player, talent.params());
            return true;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.ERROR, "玩家 " + player.getScoreboardName() + " 的一次性天赋 " + key
                    + " 没发成，下次进服再试", failure);
            return false;
        }
    }

    private boolean offer(ServerPlayer player, OriginRecord record) {
        // 选过的只发选定那一张（只读），没选的发候选；数据包里已经没有的出身不发。
        boolean decided = !record.getChosen().isEmpty();
        List<String> ids = decided ? List.of(record.getChosen()) : record.getOfferedList();
        var offer = OriginOffer.newBuilder().setChosen(decided);
        for (String id : ids) {
            Origin origin = definitions.all().get(Identifier.parse(id));
            if (origin != null) {
                offer.addCards(OriginCard.newBuilder().setId(id).setIcon(origin.icon().toString())
                        .setTalentLines(origin.talents().size()).setThreatLines(origin.threats().size()));
            }
        }
        if (offer.getCardsCount() == 0 || !ServerPlayNetworking.canSend(player, OriginNetworking.OFFER.type())) {
            return false;
        }
        ServerPlayNetworking.send(player, OriginNetworking.OFFER.payload(offer.build().toByteArray()));
        return true;
    }

    private Optional<OriginRecord> update(ServerPlayer player, UnaryOperator<OriginRecord> change) {
        // 读身体、改记录、有变化才写回；身体读不到或已死时什么都不做，返回空。
        Optional<BodySnapshot> body = bodies.read(player).filter(snapshot -> !snapshot.getVitality().getDead());
        if (body.isEmpty()) {
            return Optional.empty();
        }
        OriginRecord record = OriginRecords.read(body.get());
        OriginRecord next = change.apply(record);
        if (!next.equals(record)) {
            bodies.write(player, body.get(), next);
        }
        return Optional.of(next);
    }

    private static long now(ServerPlayer player) {
        // 和身体时钟同一口径：主世界游戏时间换成毫秒。
        return Math.multiplyExact(player.level().getServer().overworld().getGameTime(), MILLIS_PER_TICK);
    }
}
