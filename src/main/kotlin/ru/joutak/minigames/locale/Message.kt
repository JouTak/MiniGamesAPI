package ru.joutak.minigames.locale

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.LinearComponents
import net.kyori.adventure.text.format.NamedTextColor

object Message {
    val KICK_NON_PARTICIPANT =
        LinearComponents.linear(
            Component.text("☒ ", NamedTextColor.RED),
            Component.text("Вы "),
            Component.text("не являетесь ", NamedTextColor.RED),
            Component.text("участником спартакиады!"),
        )

    val KICK_NO_ATTEMPTS =
        LinearComponents.linear(
            Component.text("К сожалению, для вас cпартакиада "),
            Component.text("закончилась ", NamedTextColor.RED),
            Component.text("☹"),
        )

    val KICK_WINNER =
        LinearComponents.linear(
            Component.text("☑ ", NamedTextColor.GREEN),
            Component.text("Вы уже "),
            Component.text("прошли ", NamedTextColor.GREEN),
            Component.text("в следующий этап!"),
        )

    val KICK_UNEXPECTED_ERROR =
        LinearComponents.linear(
            Component.text("☒ ", NamedTextColor.RED),
            Component.text("При подключении возникла "),
            Component.text("ошибка ", NamedTextColor.RED),
            Component.text(", пожалуйста, обратитесь к админу!"),
        )
}
