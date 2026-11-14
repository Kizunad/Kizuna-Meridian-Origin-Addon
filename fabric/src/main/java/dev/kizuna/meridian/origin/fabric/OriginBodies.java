package dev.kizuna.meridian.origin.fabric;

import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.origin.server.OriginRecords;
import dev.kizuna.meridian.protocol.ActivityHostProtobuf;
import dev.kizuna.meridian.protocol.HealthHostProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.ActivityHostMessages.ActivityHostRequest;
import dev.kizuna.meridian.protocol.pb.ActivityMessages.BodyActivity;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostRequest;
import dev.kizuna.meridian.protocol.pb.HealthHostMessages.HealthHostResponse;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.BodyCommand;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.RequestEnvelope;
import dev.kizuna.meridian.server.BodyActivityHost;
import dev.kizuna.meridian.server.BodyHealthHost;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * 出身记录经 Framework 的两个身体宿主入口读写：健康宿主读身体，活动宿主原子地改附属进度。
 * 本仓因此只依赖 Framework，谁提供玩家身体（现在是 Human）不用写进依赖。
 */
final class OriginBodies {
    static final String SOURCE = "meridian_origin:record";
    private final List<BodyHealthHost> healthHosts;
    private final List<BodyActivityHost> activityHosts;

    OriginBodies() {
        // 身体宿主是必装的；没有就启动失败，不退回别的存法。
        healthHosts = FabricLoader.getInstance().getEntrypoints("meridian_health_host", BodyHealthHost.class);
        activityHosts = FabricLoader.getInstance().getEntrypoints("meridian_activity_host", BodyActivityHost.class);
        if (healthHosts.isEmpty() || activityHosts.isEmpty()) {
            throw new IllegalStateException("出身缺少必需的身体宿主");
        }
    }

    Optional<BodySnapshot> read(ServerPlayer player) {
        // 身体还没建好、宿主暂停、宿主拒绝，都当成「这一刻读不到」，调用方下一 tick 再来。
        String requestId = UUID.randomUUID().toString();
        byte[] payload = HealthHostProtobuf.encodeRequest(HealthHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(requestId)
                .setSubjectId(player.getStringUUID())
                .setInspect(true /* inspect */)
                .build());
        for (BodyHealthHost host : healthHosts) {
            HealthHostResponse response = HealthHostProtobuf.decodeResponse(host.exchange(payload));
            if (response.getRequestId().equals(requestId) && !response.hasFailure() && response.hasBody()) {
                return Optional.of(response.getBody());
            }
        }
        return Optional.empty();
    }

    BodySnapshot write(ServerPlayer player, BodySnapshot body, OriginRecord record) {
        // 带上读到的修订号提交：中间身体被别人改过就失败，不拿旧记录盖新身体。
        var activity = BodyActivity.newBuilder();
        OriginRecords.write(activity, record);
        RequestEnvelope envelope = RequestEnvelope.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(UUID.randomUUID().toString())
                .setBodyCommand(BodyCommand.newBuilder()
                        .setBodyId(body.getId())
                        .setExpectedRevision(body.getRevision())
                        .setExecuteActivity(activity))
                .build();
        byte[] payload = ActivityHostProtobuf.encodeRequest(ActivityHostRequest.newBuilder()
                .setProtocolVersion(MeridianProtocol.VERSION)
                .setRequestId(envelope.getRequestId())
                .setSubjectId(player.getStringUUID())
                .setActivity(envelope)
                .build());
        for (BodyActivityHost host : activityHosts) {
            HealthHostResponse response = HealthHostProtobuf.decodeResponse(host.exchange(SOURCE, payload));
            if (!response.getRequestId().equals(envelope.getRequestId())) {
                throw new IllegalStateException("身体宿主响应身份不匹配");
            }
            if (response.hasFailure()) {
                throw new IllegalStateException("身体宿主拒绝写出身记录：" + response.getFailure().getCode());
            }
            if (response.hasBody()) {
                return response.getBody();
            }
        }
        throw new IllegalStateException("已绑定身体没有对应的活动宿主");
    }
}
