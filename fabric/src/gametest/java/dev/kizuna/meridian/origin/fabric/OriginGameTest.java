package dev.kizuna.meridian.origin.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginCard;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginOffer;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import dev.kizuna.meridian.origin.server.OriginRecords;
import dev.kizuna.meridian.origin.server.OriginSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;

/**
 * 出身框架整条链：进服抽候选、退出重进还是那一组、选定只能一次且只能选抽到的、一次性天赋只发一次、
 * 常驻天赋和威胁参数查得到、指令、关掉选择。出身和类型都是测试数据，框架本身不带具体天赋。
 */
public final class OriginGameTest {
    private static final int TIMEOUT = 300;
    private static final int WAIT_TICKS = 100;
    private static final int RELOG_GAP_TICKS = 3;
    private static final int QUIET_TICKS = 10;
    private static final int OFFER_COUNT = 3;
    private static final String PREFIX = OriginTestTypes.NAMESPACE + ":";
    private static final Identifier ORPHAN = OriginTestTypes.id("orphan");
    private static final Identifier FARMER = OriginTestTypes.id("farmer");
    private static final Identifier FLAKY = OriginTestTypes.id("flaky");
    private static final Identifier CHOSEN_ADVANCEMENT = OriginTestTypes.id("chosen");
    private static final Identifier ORPHAN_ADVANCEMENT = OriginTestTypes.id("chosen_orphan");
    private static final String DEBUG = "meridian debug origin ";

    @GameTest(maxTicks = TIMEOUT)
    public void joiningPlayerIsOfferedThreeOriginsAndTheSameThreeAfterRelog(GameTestHelper context) {
        // 进服收到恰好一份候选：三个不同的出身、同组至多一个，和身体上的记录一致；退出重进还是这三个。
        UUID id = UUID.randomUUID();
        var first = new OriginTestPlayer(context, id);
        await(context, WAIT_TICKS, () -> !first.offers().isEmpty(), "进服没有收到出身候选", () -> {
            List<String> offered = ids(first.offers().getFirst());
            try (first) {
                context.assertTrue(first.offers().size() == 1 && !first.offers().getFirst().getChosen(),
                        "应当只发一份可选的候选：" + first.offers());
                context.assertTrue(new HashSet<>(offered).size() == OFFER_COUNT, "候选不是三个不同的出身：" + offered);
                long spirits = offered.stream().filter(origin -> origin.startsWith(PREFIX + "spirit_")).count();
                context.assertTrue(spirits <= 1, "同组的出身一次出了不止一个：" + offered);
                OriginRecord record = first.record();
                context.assertTrue(record.getOfferedList().equals(offered) && record.getChosen().isEmpty(),
                        "记录和发出去的候选不一致：" + record);
                first.offers().getFirst().getCardsList().forEach(card -> verifyCard(context, card));
            }
            context.runAfterDelay(RELOG_GAP_TICKS, () -> {
                var second = new OriginTestPlayer(context, id);
                await(context, WAIT_TICKS, () -> !second.offers().isEmpty(), "重进没有再收到候选", () -> {
                    try (second) {
                        context.assertTrue(ids(second.offers().getFirst()).equals(offered),
                                "重进抽到了另一组：" + ids(second.offers().getFirst()) + " 原来是 " + offered);
                    }
                    context.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void choosingWritesTheRecordGrantsOnceAndNotifies(GameTestHelper context) {
        // 选定：记录写入、一次性天赋发一次、通知一次、进度拿到；退出重进不再弹界面、不重发、不重复通知。
        UUID id = UUID.randomUUID();
        var first = new OriginTestPlayer(context, id);
        await(context, WAIT_TICKS, () -> !first.offers().isEmpty(), "进服没有收到出身候选", () -> {
            String pick = steady(ids(first.offers().getFirst())).getFirst();
            try (first) {
                first.choose(pick);
                verifyChosen(context, first, pick);
            }
            context.runAfterDelay(RELOG_GAP_TICKS, () -> {
                var second = new OriginTestPlayer(context, id);
                await(context, WAIT_TICKS, () -> Origins.chosen(second.player).isPresent(), "重进后查不到出身",
                        () -> context.runAfterDelay(QUIET_TICKS, () -> {
                            try (second) {
                                verifyRejoined(context, second, pick);
                            }
                            context.succeed();
                        }));
            });
        });
    }

    private static void verifyChosen(GameTestHelper context, OriginTestPlayer fixture, String pick) {
        // 刚选定：记录、查询、一次性天赋、两种通知和进度都对；测试出身的礼物记号就是出身的名字。
        UUID id = fixture.player.getUUID();
        Identifier origin = Identifier.parse(pick);
        OriginRecord record = fixture.record();
        context.assertTrue(record.getChosen().equals(pick) && record.getChosenAtMillis() > 0,
                "选定没有写进记录：" + record);
        context.assertTrue(record.getGrantedList().equals(List.of(OriginRecords.grantKey(pick, 0))),
                "一次性天赋的记账不对：" + record.getGrantedList());
        context.assertTrue(Origins.chosen(fixture.player).map(Origin::id).equals(Optional.of(origin)),
                "查不到刚选定的出身");
        context.assertTrue(List.of(pick.substring(PREFIX.length())).equals(OriginTestTypes.GIFTS.get(id)),
                "一次性天赋没有恰好发一次：" + OriginTestTypes.GIFTS.get(id));
        context.assertTrue(List.of(origin).equals(OriginTestTypes.NOTICES.get(id)),
                "选定通知不是恰好一次：" + OriginTestTypes.NOTICES.get(id));
        context.assertTrue(done(fixture.player, CHOSEN_ADVANCEMENT), "任意出身的进度没拿到");
        context.assertTrue(done(fixture.player, ORPHAN_ADVANCEMENT) == origin.equals(ORPHAN),
                "只认遗孤的进度拿错了：选的是 " + pick);
        context.assertTrue(List.of("").equals(OriginTestTypes.READY.get(id)),
                "进服时应当通知一次「还没选」：" + OriginTestTypes.READY.get(id));
    }

    private static void verifyRejoined(GameTestHelper context, OriginTestPlayer fixture, String pick) {
        // 选过以后重进：不弹界面，不重发天赋，不重复通知「选定」，只通知一次「出身已知」。
        UUID id = fixture.player.getUUID();
        context.assertTrue(fixture.offers().isEmpty(), "选过了重进还弹界面：" + fixture.offers());
        context.assertTrue(OriginTestTypes.GIFTS.get(id).size() == 1 && OriginTestTypes.NOTICES.get(id).size() == 1,
                "重进又发了天赋或通知");
        context.assertTrue(List.of("", pick).equals(OriginTestTypes.READY.get(id)),
                "重进时应当通知一次选定的出身：" + OriginTestTypes.READY.get(id));
    }

    @GameTest(maxTicks = TIMEOUT)
    public void onlyOneChoiceFromTheOfferIsAccepted(GameTestHelper context) {
        // 候选以外的、不存在的、写错的、解不开的包都不动记录；选定以后再选另一个候选也不行。
        UUID id = UUID.randomUUID();
        var fixture = new OriginTestPlayer(context, id);
        await(context, WAIT_TICKS, () -> !fixture.offers().isEmpty(), "进服没有收到出身候选", () -> {
            try (fixture) {
                List<String> offered = steady(ids(fixture.offers().getFirst()));
                List<String> drawn = fixture.record().getOfferedList();
                String foreign = Origins.all().keySet().stream().map(Identifier::toString)
                        .filter(origin -> !drawn.contains(origin)).findFirst().orElseThrow();
                fixture.choose(foreign);
                fixture.choose(PREFIX + "nobody");
                fixture.choose("不是 ID");
                fixture.choose("");
                fixture.send(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff});
                context.assertTrue(fixture.record().getChosen().isEmpty() && OriginTestTypes.GIFTS.get(id) == null,
                        "不合规矩的选定被接受了：" + fixture.record());
                fixture.choose(offered.get(0));
                fixture.choose(offered.get(1));
                context.assertTrue(fixture.record().getChosen().equals(offered.get(0)),
                        "选定以后又被改了：" + fixture.record());
                int gifts = OriginTestTypes.GIFTS.get(id).size();
                int notices = OriginTestTypes.NOTICES.get(id).size();
                context.assertTrue(gifts == 1 && notices == 1, "第二次选定又发了天赋或通知");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void standingTalentsAndThreatsCanBeQueried(GameTestHelper context) {
        // 没选时查到空；定下遗孤以后，常驻天赋和威胁的参数就是 JSON 里写的；按出身汇总只列带这类威胁的出身。
        var fixture = new OriginTestPlayer(context, UUID.randomUUID());
        await(context, WAIT_TICKS, () -> !fixture.offers().isEmpty(), "进服没有收到出身候选", () -> {
            try (fixture) {
                context.assertTrue(Origins.chosen(fixture.player).isEmpty()
                        && Origins.talents(fixture.player, OriginTestTypes.MARK).isEmpty()
                        && Origins.threats(fixture.player, OriginTestTypes.FEUD).isEmpty(), "没选出身却查到了东西");
                context.assertTrue(OriginFabric.service().assign(fixture.player, ORPHAN), "直接定出身没成");
                context.assertTrue(Origins.talents(fixture.player, OriginTestTypes.MARK)
                        .equals(List.of(new OriginTestTypes.Mark(2))), "常驻天赋参数不对");
                context.assertTrue(Origins.threats(fixture.player, OriginTestTypes.FEUD)
                        .equals(List.of(new OriginTestTypes.Feud(3))), "威胁参数不对");
                var feuds = Origins.threatsByOrigin(OriginTestTypes.FEUD);
                context.assertTrue(List.of(new OriginTestTypes.Feud(3)).equals(feuds.get(ORPHAN))
                        && !feuds.containsKey(FARMER) && !feuds.containsKey(FLAKY), "按出身汇总的威胁不对：" + feuds);
                context.assertTrue(Origins.nameKey(ORPHAN).equals("origin.meridian_origin_test.orphan.name"),
                        "出身显示名的语言键不对");
            }
            context.succeed();
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void failedOneShotTalentIsRetriedOnTheNextJoin(GameTestHelper context) {
        // 两份一次性天赋里第一份这次发不成：只记发成的那份；下次进服补发，补上以后两份都记了账，各只发过一次。
        UUID id = UUID.randomUUID();
        String flaky = OriginRecords.grantKey(FLAKY.toString(), 0);
        String steady = OriginRecords.grantKey(FLAKY.toString(), 1);
        var first = new OriginTestPlayer(context, id);
        await(context, WAIT_TICKS, () -> !first.offers().isEmpty(), "进服没有收到出身候选", () -> {
            try (first) {
                context.assertTrue(OriginFabric.service().assign(first.player, FLAKY), "直接定出身没成");
                context.assertTrue(first.record().getGrantedList().equals(List.of(steady))
                        && List.of("steady").equals(OriginTestTypes.GIFTS.get(id)),
                        "没发成的那份不该记账：" + first.record().getGrantedList() + " " + OriginTestTypes.GIFTS.get(id));
            }
            context.runAfterDelay(RELOG_GAP_TICKS, () -> {
                var second = new OriginTestPlayer(context, id);
                await(context, WAIT_TICKS, () -> second.record().getGrantedCount() == 2, "重进没有补发", () -> {
                    try (second) {
                        context.assertTrue(second.record().getGrantedList().equals(List.of(steady, flaky))
                                && List.of("steady", OriginTestTypes.FLAKY_TOKEN).equals(OriginTestTypes.GIFTS.get(id)),
                                "补发以后的记账不对：" + second.record().getGrantedList() + " "
                                        + OriginTestTypes.GIFTS.get(id));
                    }
                    context.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = TIMEOUT)
    public void debugAndPlayerCommandsWork(GameTestHelper context) {
        // op 直接定出身、查看、让玩家重抽；玩家自己能重新打开界面；出身 ID 能补全；没权限的人用不了调试指令。
        var fixture = new OriginTestPlayer(context, UUID.randomUUID());
        await(context, WAIT_TICKS, () -> !fixture.offers().isEmpty(), "进服没有收到出身候选", () -> {
            try {
                runCommands(context, fixture);
            } catch (CommandSyntaxException failure) {
                fixture.close();
                throw context.assertionException(Component.literal("指令没执行：" + failure.getMessage()));
            }
            await(context, WAIT_TICKS, () -> fixture.offers().size() == 3, "重抽以后没有再弹界面", () -> {
                try (fixture) {
                    OriginOffer redrawn = fixture.offers().getLast();
                    context.assertTrue(!redrawn.getChosen() && redrawn.getCardsCount() == OFFER_COUNT
                            && fixture.record().getOfferedList().equals(ids(redrawn)), "重抽的候选不对：" + redrawn);
                }
                context.succeed();
            });
        });
    }

    private static void runCommands(GameTestHelper context, OriginTestPlayer fixture) throws CommandSyntaxException {
        // 依次：没权限被拒、补全、set、show、set 不存在的出身、玩家自己打开、offer。
        CommandDispatcher<CommandSourceStack> dispatcher = context.getLevel().getServer().getCommands().getDispatcher();
        CommandSourceStack source = fixture.player.createCommandSourceStack();
        CommandSourceStack player = source.withPermission(PermissionSet.NO_PERMISSIONS);
        CommandSourceStack operator = source.withPermission(LevelBasedPermissionSet.GAMEMASTER);
        String name = fixture.player.getScoreboardName();
        String set = DEBUG + "set " + name + " ";
        refused(context, dispatcher, set + ORPHAN, player);
        Set<String> origins = suggestions(dispatcher, operator, set);
        context.assertTrue(origins.contains(ORPHAN.toString()), "set 后面没有补全出身 ID：" + origins);
        context.assertTrue(suggestions(dispatcher, operator, DEBUG).containsAll(Set.of("show", "set", "offer")),
                "调试指令的字面量没有补全");
        context.assertTrue(dispatcher.execute(set + ORPHAN, operator) == 1, "set 没成");
        context.assertTrue(fixture.record().getChosen().equals(ORPHAN.toString()), "set 没有写进记录");
        context.assertTrue(dispatcher.execute(DEBUG + "show " + name, operator) == 1, "show 没成");
        context.assertTrue(dispatcher.execute(set + PREFIX + "nobody", operator) == 0, "set 不存在的出身应当失败");
        context.assertTrue(dispatcher.execute("meridian body origin", player) == 1, "玩家打不开自己的出身");
        OriginOffer shown = fixture.offers().getLast();
        context.assertTrue(fixture.offers().size() == 2 && shown.getChosen()
                && ids(shown).equals(List.of(ORPHAN.toString())), "选过以后应当只看到选定的那一张：" + shown);
        context.assertTrue(dispatcher.execute(DEBUG + "offer " + name, operator) == 1, "offer 没成");
        context.assertTrue(fixture.record().getChosen().isEmpty() && fixture.record().getDrawRound() == 1,
                "offer 没有清掉选定、换一轮：" + fixture.record());
    }

    @GameTest(maxTicks = TIMEOUT)
    public void selectionSwitchedOffNeitherOffersNorAccepts(GameTestHelper context) {
        // 配置关掉选择的服务：玩家进服不发候选包，发来的选定也不接受。（正式服务照默认配置跑，先让它把候选抽好。）
        var fixture = new OriginTestPlayer(context, UUID.randomUUID());
        var off = new OriginService(new OriginSettings(false /* selection */, OFFER_COUNT, Set.of()),
                OriginFabric.definitions(), new OriginBodies());
        await(context, WAIT_TICKS, () -> !fixture.offers().isEmpty(), "进服没有收到出身候选", () -> {
            try (fixture) {
                String offered = fixture.record().getOffered(0);
                off.join(fixture.player);
                off.tick(context.getLevel().getServer());
                context.assertTrue(fixture.offers().size() == 1, "关掉选择还发了候选包");
                context.assertTrue(!off.choose(fixture.player, Identifier.parse(offered))
                        && fixture.record().getChosen().isEmpty(), "关掉选择还接受了选定");
                context.assertTrue(off.chosen(fixture.player).isEmpty(), "没选的玩家查到了出身");
            }
            context.succeed();
        });
    }

    private static void verifyCard(GameTestHelper context, OriginCard card) {
        // 卡片上的图标和行数来自数据包里的出身。
        Origin origin = Origins.all().get(Identifier.parse(card.getId()));
        context.assertTrue(origin != null && card.getIcon().equals(origin.icon().toString())
                && card.getTalentLines() == origin.talents().size()
                && card.getThreatLines() == origin.threats().size(), "卡片和数据包里的出身对不上：" + card);
    }

    private static List<String> ids(OriginOffer offer) {
        return offer.getCardsList().stream().map(OriginCard::getId).toList();
    }

    private static List<String> steady(List<String> offered) {
        // 「第一次发不成」的测试出身也可能被抽到；选定类用例避开它，至少还剩两个。
        return offered.stream().filter(origin -> !origin.equals(FLAKY.toString())).toList();
    }

    private static boolean done(ServerPlayer player, Identifier advancement) {
        var holder = player.level().getServer().getAdvancements().get(advancement);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static void refused(GameTestHelper context, CommandDispatcher<CommandSourceStack> dispatcher,
                                String command, CommandSourceStack source) {
        // 没权限时节点不可见，解析就失败。
        try {
            dispatcher.execute(command, source);
        } catch (CommandSyntaxException expected) {
            return;
        }
        throw context.assertionException(Component.literal("没权限也执行了：" + command));
    }

    private static Set<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                           String input) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).join().getList().stream()
                .map(Suggestion::getText)
                .collect(Collectors.toSet());
    }

    private static void await(GameTestHelper context, int ticks, BooleanSupplier condition, String failure,
                              Runnable next) {
        // 每 tick 看一次，条件满足就往下走，到期还不满足就失败。
        if (condition.getAsBoolean()) {
            next.run();
        } else if (ticks == 0) {
            throw context.assertionException(Component.literal(failure));
        } else {
            context.runAfterDelay(1, () -> await(context, ticks - 1, condition, failure, next));
        }
    }
}
