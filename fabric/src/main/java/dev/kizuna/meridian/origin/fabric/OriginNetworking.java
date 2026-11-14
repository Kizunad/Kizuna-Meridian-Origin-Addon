package dev.kizuna.meridian.origin.fabric;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.fabric.ProtobufChannel;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginChoice;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;

/** 两条通道：服务端发候选让客户端开界面，客户端回选定的出身。 */
public final class OriginNetworking {
    private static final int MAX_OFFER_BYTES = 4096;
    private static final int MAX_CHOICE_BYTES = 512;
    public static final ProtobufChannel OFFER = new ProtobufChannel(
            Identifier.fromNamespaceAndPath("meridian_origin", "offer"), MAX_OFFER_BYTES);
    public static final ProtobufChannel CHOOSE = new ProtobufChannel(
            Identifier.fromNamespaceAndPath("meridian_origin", "choose"), MAX_CHOICE_BYTES);

    private OriginNetworking() {
        // 只有通道定义和服务端收包。
    }

    static void register(OriginService service) {
        // 通道两端都要登记；选定包只带出身 ID，合不合规矩由服务端的记录规则判断。
        OFFER.registerClientbound();
        CHOOSE.registerServerbound();
        ServerPlayNetworking.registerGlobalReceiver(CHOOSE.type(), (payload, context) -> {
            Identifier origin;
            try {
                origin = Identifier.tryParse(OriginChoice.parseFrom(payload.bytes()).getId());
            } catch (InvalidProtocolBufferException invalid) {
                return;
            }
            if (origin != null) {
                service.choose(context.player(), origin);
            }
        });
    }
}
