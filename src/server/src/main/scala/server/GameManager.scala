package server

import akka.actor.Actor
import akka.actor.Props
import akka.stream.Materializer
import scala.sys.Prop

import java.util.UUID
import akka.actor.ActorRef
import akka.actor.ActorLogging
import protocol.{Message, ServerMessages}

object GameManager:

    sealed trait Command

    case class CreateGameSession(gameName: String, maxPlayers: Int, creator: ActorRef) extends Command
    case class JoinGameSession(gameId: String, player: ActorRef) extends Command
    case class LeaveGameSession(gameId: String, player: ActorRef) extends Command
    case class GetAllGames(replyTo: ActorRef) extends Command
    case class ForwardToGame(gameId: String, message: Message) extends Command
    case class PlayerDisconnected(player: ActorRef) extends Command
    case class RegisterClient(client: ActorRef) extends Command

    //Factory method per creare un'istanza di GameManager
    def props: Props = Props(new GameManager())
    // Props è una configurazione Akka per creare un attore

    //Caso vuoto, utilizzato per inizializzare lo stato del GameManager
    private case object Empty

class GameManager extends Actor with ActorLogging:

    import GameManager._
   
    // metodo speciale chiamato quando l'attore viene avviato
    override def preStart(): Unit =
        log.info("GameManager started")
        context.become(running(Map.empty, Set.empty, Map.empty)) 
        // Inizializza anche il set di client connessi vuoto

    def receive: Receive =
        case Empty => // caso dummy per inizializzare lo stato
            log.info("GameManager initialized with empty state") 
    
    def running(
        games: Map[String, ActorRef],
        connectedClients: Set[ActorRef],
        playerToGame: Map[ActorRef, String]
    ): Receive = 

        case RegisterClient(client) =>
            val updatedClients = connectedClients + client
            println(s"Client ${client.path} registered. Current clients: ${connectedClients.map(_.path)}")
            println(s"Updated clients: ${updatedClients.map(_.path)}")
            println(s"Total connected clients: ${updatedClients.size}")
            log.warning(s"Client ${client.path} connected. Total connected clients: ${updatedClients.size}")
            context.become(running(games, updatedClients, playerToGame))
            
        case CreateGameSession(gameName, maxPlayers, creator) =>
            val gameId = UUID.randomUUID().toString() // crea ID randomico per la nuova partita
            log.warning(s"Creating game session: $gameName with ID: $gameId")

            val gameSession = context.actorOf(
                GameSession.props(gameId, gameName, maxPlayers),
                s"GameSession-$gameId"
            )
            val updatedGames = games + (gameId -> gameSession)
            val updatedPlayerToGame = playerToGame + (creator -> gameId)

            creator ! ServerMessages.GameCreated(
                gameId,
                gameName,
                creator.path.name
            )

            gameSession ! GameSession.JoinGame(creator.path.name, creator)

            context.become(running(updatedGames, connectedClients, updatedPlayerToGame))

        case JoinGameSession(gameId, player) =>
            games.get(gameId) match 
                case Some(gameSession) =>
                    log.warning(s"Player ${player.path.name} joining game session: $gameId")
                    gameSession ! GameSession.JoinGame(player.path.name, player)

                    // Aggiorna la mappatura dei giocatori al gioco
                    val updatedPlayerToGame = playerToGame + (player -> gameId)
                    context.become(running(games, connectedClients, updatedPlayerToGame))
                    
                case None =>
                    // partita non trovata
                    log.warning(s"Game session $gameId not found for player ${player.path.name}")
                    player ! ServerMessages.Error(s"Game session $gameId not found")
            
        // abbandono della partita
        case LeaveGameSession(gameId, player) =>
            games.get(gameId) match 
                case Some(gameSession) =>
                    log.info(s"Player ${player.path.name} leaving game session: $gameId")
                    gameSession ! GameSession.LeaveGame(player.path.name)

                    //Rimuove il giocatore dalla mappatura dei giocatori al gioco
                    val updatedPlayerToGame = playerToGame - player
                    context.become(running(games, connectedClients, updatedPlayerToGame))
                    
                case None =>
                    log.warning(s"Game session $gameId not found for player ${player.path.name}")
            
        case GetAllGames(replyTo) =>
            log.info("Sending all game sessions to the requester")
            val gameList = games.keys.toList
            replyTo ! gameList

        case ForwardToGame(gameId, message) =>
            games.get(gameId) match 
                case Some(gameSession) =>
                    gameSession forward message
                case None =>
                    log.warning(s"Forwarding failed: Game session $gameId not found")
                    sender() ! ServerMessages.Error(s"Game session $gameId not found")
            
        case PlayerDisconnected(player) =>
            // Rimuove il client dal set di client connessi
            val updatedClients = connectedClients - player
            log.warning(s"Client ${player.path.name} disconnected. Total connected clients: ${updatedClients.size}")
            println(s"Client ${player.path.name} disconnected. Total connected clients: ${updatedClients.size}")
            playerToGame.get(player) match 
                case Some(gameId) =>
                    log.info(s"Player ${player.path.name} disconnected from game session: $gameId")
                    games.get(gameId) match 
                        case Some(gameSession) =>
                            gameSession ! GameSession.LeaveGame(player.path.name)
                        case None =>
                            log.warning(s"Game session $gameId not found for disconnected player ${player.path.name}")
                    
                    // Rimuove il giocatore dalla mappatura dei giocatori al gioco
                    val updatedPlayerToGame = playerToGame - player
                    context.become(running(games, updatedClients, updatedPlayerToGame))
                
                case None =>
                    log.warning(s"Player ${player.path.name} not found in any game session")
                    context.become(running(games, updatedClients, playerToGame))

end GameManager



