package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.joutak.minigames.command.PluginCommand
import java.util.concurrent.CompletableFuture

object ReadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {
    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands
            .literal("ready")
            .executes { ctx ->
                ctx.source.sender.sendMessage("Hi")
                return@executes Command.SINGLE_SUCCESS
            }
        // .literal("giveitem") // Require a player to execute the command
        // .requires(Predicate { ctx: CommandSourceStack? -> ctx!!.getExecutor() is Player }) // Declare a new ItemStack argument
        // .then(
        //     Commands
        //         .argument<ItemStack>(
        //             "item",
        //             ArgumentTypes.itemStack(),
        //         ) // Declare a new integer argument with the bounds of 1 to 99
        //         .then(
        //             Commands
        //                 .argument<Int>(
        //                     "amount",
        //                     IntegerArgumentType.integer(1, 99),
        //                 ) // Here, we use method references, since otherwise, our command definition would grow too big
        //                 .suggests(this::getAmountSuggestions)
        //                 .executes(this::executeCommandLogic),
        //         ),
        // )
    }

    private fun getAmountSuggestions(
        ctx: CommandContext<CommandSourceStack>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        // Suggest 1, 16, 32, and 64 to the user when they reach the 'amount' argument
        builder.suggest(1)
        builder.suggest(16)
        builder.suggest(32)
        builder.suggest(64)
        return builder.buildFuture()
    }

    private fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        // We know that the executor will be a player, so we can just silently return
        if (ctx.source.executor !is Player) {
            return Command.SINGLE_SUCCESS
        }

        val player = ctx.source.executor as Player

        // If the player has no empty slot, we tell the player that they have no free inventory space
        val firstEmptySlot: Int = player.inventory.firstEmpty()
        if (firstEmptySlot == -1) {
            player.sendRichMessage("<light_purple>You do not have enough space in your inventory!")
            return Command.SINGLE_SUCCESS
        }

        // Retrieve our argument values
        val item = ctx.getArgument<ItemStack>("item", ItemStack::class.java)
        val amount = IntegerArgumentType.getInteger(ctx, "amount")

        // Set the item's amount and give it to the player
        item.setAmount(amount)
        player.getInventory().setItem(firstEmptySlot, item)

        // Send a confirmation message
        player.sendRichMessage(
            "<light_purple>You have been given <white><amount>x</white> <aqua><item></aqua>!",
            Placeholder.component("amount", Component.text(amount)),
            Placeholder.component("item", Component.translatable(item).hoverEvent(item)),
        )
        return Command.SINGLE_SUCCESS
    }
}
