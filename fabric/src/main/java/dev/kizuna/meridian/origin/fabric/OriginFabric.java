package dev.kizuna.meridian.origin.fabric;

import dev.kizuna.meridian.origin.server.OriginSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/** 出身附属的服务端接线：配置、类型登记、数据包、进服流程、网络、指令和进度触发器。 */
public final class OriginFabric implements ModInitializer {
    private static OriginDefinitions definitions;
    private static OriginService service;

    @Override
    public void onInitialize() {
        // 类型从各附属的入口收齐以后才读得了出身数据包；数据包在服务器启动时读，重载成功后重读。
        var settings = OriginSettings.load(FabricLoader.getInstance().getConfigDir().resolve("meridian-origin.toml"));
        definitions = new OriginDefinitions(OriginTypes.collect());
        service = new OriginService(settings, definitions, new OriginBodies());
        OriginChosenTrigger.register();
        OriginNetworking.register(service);
        OriginCommands.register(service);
        ServerLifecycleEvents.SERVER_STARTING.register(definitions::start);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) {
                definitions.reload(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> definitions.stop());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> service.join(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> service.leave(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(service::tick);
    }

    static OriginService service() {
        // 模组初始化以后才有；公开查询口都经这里。
        return service;
    }

    static OriginDefinitions definitions() {
        return definitions;
    }
}
