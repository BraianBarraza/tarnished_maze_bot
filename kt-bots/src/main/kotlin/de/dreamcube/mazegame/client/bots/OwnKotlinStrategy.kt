package de.dreamcube.mazegame.client.bots

import de.dreamcube.mazegame.client.maze.strategy.Bot
import de.dreamcube.mazegame.client.maze.strategy.Move
import de.dreamcube.mazegame.client.maze.strategy.Strategy

/**
 * Implement your bot strategy using this class as foundation. You probably want to change the class name and the
 * annotation. You also most likely want to remove or change this class description. Have Fun!
 */
@Bot("failure_kt")
class OwnKotlinStrategy : Strategy() {
    override fun getNextMove(): Move {
        // TODO: implement the best bot strategy in the world
        return Move.DO_NOTHING
    }
}