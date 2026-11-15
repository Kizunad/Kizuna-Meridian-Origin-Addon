package dev.kizuna.meridian.origin.fabric.client;

import dev.kizuna.meridian.origin.fabric.Origin;
import dev.kizuna.meridian.origin.fabric.Origins;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginCard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.Identifier;

/**
 * 真客户端里走一遍选出身：进单人世界后界面自己弹出三张样例出身，截一张；点第一张的「就是它」，底栏换成确认，截一张；
 * 确认以后服务端记下出身，用 /meridian body origin 再打开，只剩选定那一张、只能看，截一张。
 */
public final class OriginScreenClientTest implements ClientModInitializer {
    private static final int READY_TICKS = 60;
    private static final int TIMEOUT_TICKS = 1200;
    private static final int SETTLE_TICKS = 20;
    private static final int OFFERED = 3;
    private static final Path SHOTS = Path.of("../origin-screens", System.getProperty("meridian.origin.shots", "wide"));
    private static final Path REPORT = Path.of("../clienttest-results.txt");
    private Stage stage = Stage.WAITING;
    private int ticks;
    private int stageTick;
    private String picked;

    private enum Stage { WAITING, OFFERED, PICKED, SETTLING, REOPENED, CAPTURING, FINISHING, DONE }

    @Override
    public void onInitializeClient() {
        // 截图在客户端刻末尾发起主帧缓冲拷贝，此时其中是上一帧完整画面；等回调期间停在 CAPTURING。
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        ticks++;
        if (stage == Stage.DONE || stage == Stage.CAPTURING) {
            return;
        }
        if (ticks > TIMEOUT_TICKS) {
            finish("超时，停在 " + stage);
            return;
        }
        // 26.2 的加载遮罩挂在 client.gui 上。
        if (ticks < READY_TICKS || client.player == null || client.gui.overlay() != null
                || client.getSingleplayerServer() == null) {
            return;
        }
        client.gui.toastManager().clear();
        try {
            switch (stage) {
                case WAITING -> awaitOffer(client);
                case OFFERED -> pick(client);
                case PICKED -> settle(client);
                case SETTLING -> reopen(client);
                case REOPENED -> awaitReopened(client);
                case FINISHING -> leave(client);
                default -> throw new IllegalStateException(stage.name());
            }
        } catch (Exception failure) {
            finish(failure.toString());
        }
    }

    private void awaitOffer(Minecraft client) throws Exception {
        // 进服后服务端等身体建好就发候选，界面自己弹出来；三张都在，等画面稳定再截。
        client.options.pauseOnLostFocus = false;
        client.options.cloudStatus().set(CloudStatus.OFF);
        if (!(client.gui.screen() instanceof OriginScreen screen)) {
            return;
        }
        if (screen.offer().getCardsCount() != OFFERED || screen.offer().getChosen()) {
            throw new IllegalStateException("弹出的不是三张可选的候选：" + screen.offer());
        }
        if (settled()) {
            // 并排时每张纸都要整张露着：来历可以自己滚，天赋和麻烦不能要滚才看得见。界面开着时聊天栏要收起来。
            if (screen.sideBySide() && !screen.cardsFit()) {
                throw new IllegalStateException("并排时纸比窗口高，天赋和麻烦要滚才看得全");
            }
            if (!client.gui.hud.isHidden()) {
                throw new IllegalStateException("界面开着时抬头显示没有收起来，聊天提示会透出来");
            }
            Files.createDirectories(SHOTS);
            capture(client, "1-offer.png", Stage.OFFERED);
        }
    }

    private void pick(Minecraft client) {
        // 点第一张的「就是它」：底栏换成确认，那张纸的边变亮。
        OriginScreen screen = (OriginScreen) client.gui.screen();
        if (picked == null) {
            OriginCard first = screen.offer().getCards(0);
            picked = first.getId();
            screen.pick(first);
            stageTick = ticks;
        } else if (settled()) {
            capture(client, "2-confirm.png", Stage.PICKED);
        }
    }

    private void settle(Minecraft client) {
        // 确认：界面合上，选定包发给服务端。
        ((OriginScreen) client.gui.screen()).settle();
        if (client.gui.screen() != null) {
            throw new IllegalStateException("确认以后界面没合上");
        }
        advance(Stage.SETTLING);
    }

    private void reopen(Minecraft client) {
        // 服务端记下出身以后，玩家用指令再打开，看到的是只读的那一张。
        var server = client.getSingleplayerServer();
        var player = server.getPlayerList().getPlayer(client.player.getUUID());
        Optional<Identifier> chosen = server.submit(() -> Origins.chosen(player).map(Origin::id)).join();
        if (chosen.isEmpty()) {
            return;
        }
        if (!chosen.get().toString().equals(picked)) {
            throw new IllegalStateException("服务端记下的出身不是选的那个：" + chosen.get() + " 选的是 " + picked);
        }
        client.player.connection.sendCommand("meridian body origin");
        advance(Stage.REOPENED);
    }

    private void awaitReopened(Minecraft client) {
        if (!(client.gui.screen() instanceof OriginScreen screen)) {
            return;
        }
        if (!screen.offer().getChosen() || screen.offer().getCardsCount() != 1
                || !screen.offer().getCards(0).getId().equals(picked)) {
            throw new IllegalStateException("选过以后打开的不是选定那一张：" + screen.offer());
        }
        if (settled()) {
            capture(client, "3-chosen.png", Stage.FINISHING);
        }
    }

    private void leave(Minecraft client) {
        // 先合上界面、再过几帧才停客户端。
        if (client.gui.screen() != null) {
            client.gui.setScreen(null);
            stageTick = ticks;
        } else if (client.gui.hud.isHidden()) {
            throw new IllegalStateException("界面合上以后抬头显示没有恢复");
        } else if (settled()) {
            finish(null);
        }
    }

    private boolean settled() {
        // 进入这一步后先让界面排好版、画过几帧。
        if (stageTick == 0) {
            stageTick = ticks;
        }
        return ticks - stageTick >= SETTLE_TICKS;
    }

    private void advance(Stage next) {
        stage = next;
        stageTick = 0;
    }

    private void capture(Minecraft client, String name, Stage next) {
        // 拷贝完成后存盘再进下一步。结束不在这个回调里做：回调在显存回读的途中，这时停客户端会在退出时崩。
        stage = Stage.CAPTURING;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(SHOTS.resolve(name));
                advance(next);
            } catch (Exception failure) {
                finish(failure.toString());
            }
        });
    }

    private void finish(String failure) {
        // 独立报告让断言与截图失败传递给 Gradle。
        stage = Stage.DONE;
        try {
            Files.writeString(REPORT, "success=" + (failure == null) + "\nerror=" + failure + "\n");
        } catch (Exception reportFailure) {
            throw new IllegalStateException(reportFailure);
        }
        Minecraft.getInstance().stop();
    }
}
