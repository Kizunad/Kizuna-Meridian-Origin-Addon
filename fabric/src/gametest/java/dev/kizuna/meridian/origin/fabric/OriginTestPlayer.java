package dev.kizuna.meridian.origin.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import com.mojang.authlib.GameProfile;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginChoice;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.technique.fabric.testing.RecordingConnection;
import dev.kizuna.meridian.technique.fabric.testing.TestPlayers;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** 经过真实登录与频道协商的测试玩家，网络输出记录在内存中；同一个 UUID 再建一个就是退出重进。 */
final class OriginTestPlayer implements AutoCloseable {
    private static final int NAME_ID_LENGTH = 8;
    final ServerPlayer player;
    final RecordingConnection connection;

    OriginTestPlayer(GameTestHelper context, UUID id) {
        // 只替换传输层，玩家列表和各附属的进服事件都用正式实现；玩家身体由完整装配里的 Human 建。
        var world = context.getLevel();
        var profile = new GameProfile(id, "origin_" + id.toString().substring(0, NAME_ID_LENGTH));
        player = new ServerPlayer(world.getServer(), world, profile, ClientInformation.createDefault());
        // Human 每 tick 检查必装 UI 接收通道，缺少时会暂停身体；出身界面的通道也要声明，服务端才会发候选。
        connection = new RecordingConnection(List.of(Identifier.parse("meridian:body_view"),
                Identifier.parse("meridian:gathering_response"), Identifier.parse("meridian:opening_response"),
                Identifier.parse("meridian_progression:event"), Identifier.parse("kizuna_players_animation:play"),
                Identifier.parse("kizuna_players_animation:stop"), OriginNetworking.OFFER.type().id()));
        TestPlayers.logIn(player, connection);
        player.setGameMode(GameType.CREATIVE);
        player.setNoGravity(true /* noGravity */);
    }

    /** 真正发给这名玩家的出身候选包，按发出的先后排。 */
    List<OriginOffer> offers() {
        return connection.packets().stream()
                .filter(ClientboundCustomPayloadPacket.class::isInstance)
                .map(packet -> ((ClientboundCustomPayloadPacket) packet).payload())
                .filter(payload -> payload.type().equals(OriginNetworking.OFFER.type()))
                .map(payload -> offer(((ProtobufChannel.Payload) payload).bytes()))
                .toList();
    }

    /** 身体上现在的出身记录；身体还读不到时抛异常。 */
    OriginRecord record() {
        return OriginFabric.service().record(player).orElseThrow();
    }

    /** 像客户端那样发一个「选定」包，经正式的游戏阶段网络处理器，返回前同步处理完。 */
    void choose(String origin) {
        send(OriginChoice.newBuilder().setId(origin).build().toByteArray());
    }

    void send(byte[] choice) {
        var payload = OriginNetworking.CHOOSE.payload(choice);
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    private static OriginOffer offer(byte[] bytes) {
        try {
            return OriginOffer.parseFrom(bytes);
        } catch (InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("候选包解不开", invalid);
        }
    }

    @Override
    public void close() {
        // 真实断线入口让身体宿主撤销认领，并从在线列表移除玩家；已被服务端断开时不再重复处理。
        connection.disconnectFromClient();
    }
}
