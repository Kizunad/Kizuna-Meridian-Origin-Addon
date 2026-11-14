package dev.kizuna.meridian.origin.fabric;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.origin.protocol.pb.OriginMessages.OriginRecord;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 出身的指令。Framework 不许附属在 /meridian 下开新的二级节点，所以挂在现有分支下：
 * <ul>
 * <li>{@code /meridian body origin}（玩家）：没选就重新弹选择界面，选过就打开只读的那一张；</li>
 * <li>{@code /meridian debug origin show [<targets>]}（op）：看抽到的候选和选定的；</li>
 * <li>{@code /meridian debug origin set <target> <origin>}（op）：直接定出身，照常发一次性天赋；</li>
 * <li>{@code /meridian debug origin offer <target>}（op）：清掉候选和选定，重新抽、重新弹界面。</li>
 * </ul>
 */
final class OriginCommands {
    private static final String NODE = "origin";
    private static final String TARGET = "target";
    private static final String ORIGIN = "origin";

    private OriginCommands() {
        // 指令登记只在模组初始化时做一次。
    }

    static void register(OriginService service) {
        // 字面量和出身 ID 都能 Tab 补全。
        MeridianCommands.register(MeridianCommands.Branch.BODY, NODE, MeridianCommands.PLAYER,
                (node, context) -> node.executes(command -> open(service, command)));
        MeridianCommands.register(MeridianCommands.Branch.DEBUG, NODE, MeridianCommands.GAMEMASTER, (node, context) -> {
            var show = Commands.literal("show");
            MeridianCommands.optionalPlayers(show, (parent, targets) -> parent.executes(
                    command -> show(service, command.getSource(), targets.resolve(command))));
            node.then(show);
            node.then(Commands.literal("set").then(Commands.argument(TARGET, EntityArgument.player())
                    .then(Commands.argument(ORIGIN, IdentifierArgument.id())
                            .suggests((command, builder) -> SharedSuggestionProvider.suggestResource(
                                    Origins.all().keySet(), builder))
                            .executes(command -> set(service, command)))));
            node.then(Commands.literal("offer").then(Commands.argument(TARGET, EntityArgument.player())
                    .executes(command -> offer(service, command))));
        });
    }

    private static int open(OriginService service, CommandContext<CommandSourceStack> command)
            throws CommandSyntaxException {
        // 没有可看的（配置关了选择、身体还没读到、候选都不在数据包里了）就说一声。
        CommandSourceStack source = command.getSource();
        if (!service.open(source.getPlayerOrException())) {
            source.sendFailure(Component.translatable("commands.meridian_origin.open.nothing"));
            return 0;
        }
        return 1;
    }

    private static int show(OriginService service, CommandSourceStack source, Collection<ServerPlayer> players) {
        // 每个玩家一行：选定的出身和抽到的候选。
        int shown = 0;
        for (ServerPlayer player : players) {
            Optional<OriginRecord> record = service.record(player);
            if (record.isEmpty()) {
                source.sendFailure(Component.translatable("commands.meridian_origin.no_body", player.getDisplayName()));
                continue;
            }
            Component chosen = record.get().getChosen().isEmpty()
                    ? Component.translatable("commands.meridian_origin.show.undecided")
                    : Component.literal(record.get().getChosen());
            source.sendSuccess(() -> Component.translatable("commands.meridian_origin.show", player.getDisplayName(),
                    chosen, String.join(", ", record.get().getOfferedList())), false /* broadcastToOps */);
            shown++;
        }
        return shown;
    }

    private static int set(OriginService service, CommandContext<CommandSourceStack> command)
            throws CommandSyntaxException {
        // 出身得在数据包里，玩家的身体得读得到。
        CommandSourceStack source = command.getSource();
        ServerPlayer player = EntityArgument.getPlayer(command, TARGET);
        Identifier origin = IdentifierArgument.getId(command, ORIGIN);
        if (!Origins.all().containsKey(origin)) {
            source.sendFailure(Component.translatable("commands.meridian_origin.set.unknown", origin.toString()));
            return 0;
        }
        if (!service.assign(player, origin)) {
            source.sendFailure(Component.translatable("commands.meridian_origin.no_body", player.getDisplayName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.meridian_origin.set", player.getDisplayName(),
                origin.toString()), true /* broadcastToOps */);
        return 1;
    }

    private static int offer(OriginService service, CommandContext<CommandSourceStack> command)
            throws CommandSyntaxException {
        // 重抽只为测试：已经发过的一次性天赋不收回。
        CommandSourceStack source = command.getSource();
        ServerPlayer player = EntityArgument.getPlayer(command, TARGET);
        if (!service.redraw(player)) {
            source.sendFailure(Component.translatable("commands.meridian_origin.no_body", player.getDisplayName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.meridian_origin.offer", player.getDisplayName()),
                true /* broadcastToOps */);
        return 1;
    }
}
