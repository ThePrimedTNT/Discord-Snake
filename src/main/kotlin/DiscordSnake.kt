import kotlinx.coroutines.experimental.*
import net.dv8tion.jda.bot.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import java.awt.Point
import java.util.*
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    val shardManager = DefaultShardManagerBuilder()
            .setToken(System.getenv("discordToken"))
            .addEventListeners(object : ListenerAdapter() {

                val map = mutableMapOf<Long, SnakeGame>()

                override fun onGuildMessageReceived(event: GuildMessageReceivedEvent?) {
                    if (event!!.message.contentRaw.equals("!snake start", true)) {
                        if (map.containsKey(event.channel.idLong)) {
                            event.channel.sendMessage("Game already started!").queue()
                        } else {
                            map[event.channel.idLong] = SnakeGame(event.channel, {
                                map.remove(event.channel.idLong)?.destroy()
                            })
                            event.channel.sendMessage("Game of snake started!").queue()
                        }
                    }
                    if (event.message.contentRaw.equals("!snake stop", true)) {
                        if (map.containsKey(event.channel.idLong)) {
                            map.remove(event.channel.idLong)!!.destroy()
                            event.channel.sendMessage("Game has stopped!").queue()
                        } else {
                            event.channel.sendMessage("There currently is no active game of snake!").queue()
                        }
                    }
                }
            })
            .build()
}

class SnakeGame(val channel: TextChannel, val removeHandler: () -> Unit) {

    companion object {
        const val MAP_SIZE = 10
    }

    private var updateJob: Job

    private val random = Random()

    private var appleLocation = randomPoint()

    private val snake = mutableListOf<Point>()

    init {
        var startLocation = randomPoint()
        while (startLocation == appleLocation) startLocation = randomPoint()
        snake.add(startLocation)

        updateJob = launch {
            while (isActive) {
                update()
                delay(5, TimeUnit.SECONDS)
            }
        }
    }

    fun randomPoint() = Point(random.nextInt(MAP_SIZE), random.nextInt(MAP_SIZE))

    private var currentMessage: Deferred<Message>? = null

    suspend fun update() {
        val lastMessage = currentMessage?.await()
        if (lastMessage != null) {
            val reactions = channel.getMessageById(lastMessage.idLong).complete().reactions
            val upReactions = reactions.find { it.reactionEmote.name == "\uD83D\uDD3C" }?.count ?: 0
            val downReactions = reactions.find { it.reactionEmote.name == "\uD83D\uDD3D" }?.count ?: 0
            val leftReactions = reactions.find { it.reactionEmote.name == "\u2B05" }?.count ?: 0
            val rightReactions = reactions.find { it.reactionEmote.name == "\u27A1" }?.count ?: 0

            val maxVoted = listOf(upReactions, downReactions, leftReactions, rightReactions).max()

            val frontSnake = snake.first()

            val newPoint = when (maxVoted) {
                upReactions -> Point(frontSnake.x, frontSnake.y - 1)
                downReactions -> Point(frontSnake.x, frontSnake.y + 1)
                leftReactions -> Point(frontSnake.x - 1, frontSnake.y)
                rightReactions -> Point(frontSnake.x + 1, frontSnake.y)
                else -> TODO("sir")
            }

            snake.add(0, newPoint)

            if (snake.map { c1 -> snake.filter { c1 == it }.count() }.any { it > 1 }) {
                channel.sendMessage("Oof you ran into yourself!").queue()
                removeHandler.invoke()
                return
            }
            if (snake.any { it.x < 0 || it.x >= MAP_SIZE || it.y < 0 || it.y >= MAP_SIZE }) {
                channel.sendMessage("Oof your snake went outside its cage!").queue()
                removeHandler.invoke()
                return
            }

            if (appleLocation == newPoint) {
                var startLocation = randomPoint()
                while (startLocation == appleLocation || snake.contains(startLocation)) startLocation = randomPoint()
                appleLocation = startLocation
            } else {
                snake.removeAt(snake.size - 1)
            }
        }

        val messageContent = EmbedBuilder()
                .setDescription("Apples: ${snake.size}\n" +
                        (0 until MAP_SIZE).joinToString(separator = "\n") { y ->
                            (0 until MAP_SIZE).joinToString(separator = "") { x ->
                                val point = Point(x, y)
                                if (point == appleLocation) {
                                    "\uD83C\uDF4E"
                                } else if (snake.contains(point)) {
                                    val index = snake.indexOf(point)
                                    if (index == 0) {
                                        "\uD83D\uDD34"
                                    } else {
                                        "\uD83D\uDD35"
                                    }
                                } else {
                                    "\u2B1B"
                                }
                            }
                        })
                .build()

        currentMessage = async {
            val message = channel.sendMessage(messageContent).complete()
            lastMessage?.delete()?.complete()
            message.addReaction("\uD83D\uDD3C").complete()
            message.addReaction("\uD83D\uDD3D").complete()
            message.addReaction("⬅").complete()
            message.addReaction("➡").complete()
            message
        }
    }

    fun destroy() {
        updateJob.cancel()
        launch { currentMessage?.await()?.delete()?.queue() }
    }

}