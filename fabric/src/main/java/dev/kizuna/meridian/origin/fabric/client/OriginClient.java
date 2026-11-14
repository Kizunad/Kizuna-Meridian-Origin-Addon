package dev.kizuna.meridian.origin.fabric.client;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.meridian.origin.fabric.OriginNetworking;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginChoice;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** 出身的客户端入口：收到候选就打开出身界面，玩家选定后把出身 ID 发回服务端。 */
public final class OriginClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 26.2 的收包回调已在客户端主线程上，界面直接打开；解不开的包丢弃。
        ClientPlayNetworking.registerGlobalReceiver(OriginNetworking.OFFER.type(), (payload, context) -> {
            try {
                OriginOffer offer = OriginOffer.parseFrom(payload.bytes());
                if (offer.getCardsCount() > 0) {
                    Minecraft.getInstance().gui.setScreen(new OriginScreen(offer, OriginClient::choose));
                }
            } catch (InvalidProtocolBufferException invalid) {
                // 协议不对的包不开界面。
            }
        });
    }

    private static void choose(String origin) {
        // 选定只发出身 ID，合不合规矩由服务端判断。
        ClientPlayNetworking.send(OriginNetworking.CHOOSE.payload(
                OriginChoice.newBuilder().setId(origin).build().toByteArray()));
    }
}
