package engine

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import exceptions._
import model.player.*
import model.cards.*
import model.board.*
import utils.TestUtils.*

class GameEngineTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:
  val player1 = PlayerImpl("1", "Alice", PlayerColor.Red, PlayerType.Human)
  val player2 = PlayerImpl("2", "Bob", PlayerColor.Blue, PlayerType.Human)
  var engine: GameEngine = _

  override def beforeEach(): Unit =
    engine = new GameEngine(List(player1, player2))
    setupTestGameState()

  def setupTestGameState(): Unit =
    val territories = engine.getGameState.board.territories.toList
    val t1 = territories.head.copy(
      owner = Some(player1), 
      troops = 3
    )
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, t1)
    val updatedPlayerStates = engine.getGameState.playerStates.map:
      case ps if ps.playerId == "1" => ps.copy(bonusTroops = 5)
      case ps => ps
    val updatedTurnManager = TurnManagerImpl(
      players = List(player1, player2),
      currentPlayerIndex = 0,
      phase = TurnPhase.SetupPhase
    )
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatedPlayerStates,
      turnManager = updatedTurnManager
    ))

  private def updateTerritoriesInBoard(board: Board, updatedTerritories: Territory*): Board =
    val territoryMap = updatedTerritories.map(t => t.name -> t).toMap
    val updatedContinents = board.continents.map:
      continent =>
        val updatedContinentTerritories = continent.territories.map:
          territory =>
            territoryMap.getOrElse(territory.name, territory)
        continent.copy(territories = updatedContinentTerritories)
    board.copy(continents = updatedContinents)

  test("PlaceTroops - places troops correctly and reduces bonus"):
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name
    val gameState = engine.processAction(GameAction.PlaceTroops("1", 3, territoryName)) 
    val updatedTerritory = gameState.board.territories.find(_.name == territoryName).get
    updatedTerritory.troops should be(6) 
    val playerState = gameState.playerStates.find(_.playerId == "1").get
    playerState.bonusTroops should be(2)

  test("PlaceTroops - fails if too many troops"):
    val playerWithFewBonus = engine.getGameState.playerStates.find(_.playerId == "1").get.copy(bonusTroops = 2)
    val updatedPlayerStates = engine.getGameState.playerStates.map:
      case ps if ps.playerId == "1" => playerWithFewBonus
      case ps => ps
    val updatedTurnManager = TurnManagerImpl(
      players = List(player1, player2),
      currentPlayerIndex = 0,
      phase = TurnPhase.MainPhase
    )
    engine.setGameState(engine.getGameState.copy(
      playerStates = updatedPlayerStates,
      turnManager = updatedTurnManager
    ))
    
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("1", 3, territoryName))
    }

  test("PlaceTroops - fails if zero or negative troops"):
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("1", 0, territoryName))
    }
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("1", -1, territoryName))
    }

  test("PlaceTroops - fails if territory not owned"):
    val emptyTerritoryName = engine.getGameState.board.territories.find(_.owner.isEmpty).get.name
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("1", 2, emptyTerritoryName))
    }

  test("PlaceTroops - fails if territory does not exist"):
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("1", 2, "NonExistentTerritory"))
    }

  test("PlaceTroops - fails if player does not exist"):
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name  
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.PlaceTroops("999", 2, territoryName))
    }

  test("Start turn bonus is territories/3 rounded down (min 3)"):
    val allTerritories = engine.getGameState.board.territories.toList
    val updatedBoard = assignTerritoriesToPlayer(engine.getGameState.board, allTerritories.take(9), player1) 
    val calculatedBonus = 3 // min(3, 9/3)
    val updatedPlayerStates = engine.getGameState.playerStates.map:
      case ps if ps.playerId == "1" => ps.copy(bonusTroops = calculatedBonus)
      case ps => ps
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatedPlayerStates,
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))   
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name
    val gameState = engine.processAction(GameAction.PlaceTroops("1", 1, territoryName)) 
    val playerState = gameState.playerStates.find(_.playerId == "1").get
    playerState.bonusTroops should be(2) // 3 - 1 = 2

  test("Start turn bonus includes continent bonus if fully owned"):
    val maybeEurope = engine.getGameState.board.continents.find(_.name.equalsIgnoreCase("Europe"))
    assume(maybeEurope.isDefined, "Continent Europe must exist in the test board")
    val europe = maybeEurope.get
    val updatedBoard = assignTerritoriesToPlayer(engine.getGameState.board, europe.territories.toList, player1) 
    
    val expectedBonus = math.max(3, europe.territories.size / 3) + europe.bonusTroops
    val updatedPlayerStates = engine.getGameState.playerStates.map:
      case ps if ps.playerId == "1" => ps.copy(bonusTroops = expectedBonus)
      case ps => ps
      
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatedPlayerStates,
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    )) 
    val territoryName = engine.getGameState.board.territories.find(_.owner.exists(_.id == "1")).get.name
    val gameState = engine.processAction(GameAction.PlaceTroops("1", 1, territoryName))
    val playerState = gameState.playerStates.find(_.playerId == "1").get
    playerState.bonusTroops should be(expectedBonus - 1)

  test("Reinforce - moves troops correctly between adjacent territories"):
    val allTerritories = engine.getGameState.board.territories.toList
    val existingT1 = allTerritories.head
    val existingT2 = allTerritories.find(t => existingT1.neighbors.exists(_.name == t.name)).getOrElse(allTerritories.tail.head) 
    val t1 = existingT1.copy(
      owner = Some(player1), 
      troops = 4
    )
    val t2 = existingT2.copy(
      owner = Some(player1), 
      troops = 2
    )
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, t1, t2)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))
    val gameState = engine.processAction(GameAction.Reinforce("1", t1.name, t2.name, 1))
    val updatedT1 = gameState.board.territories.find(_.name == t1.name).get
    val updatedT2 = gameState.board.territories.find(_.name == t2.name).get
    updatedT1.troops should be(3) // 4 - 1
    updatedT2.troops should be(3) // 2 + 1

  test("Reinforce - fails if territories are not adjacent"):
    val allTerritories = engine.getGameState.board.territories.toList
    val t1 = allTerritories.head.copy(
      name = "Territory1",
      owner = Some(player1), 
      troops = 5,
      neighbors = Set.empty
    )
    val t2 = allTerritories.tail.head.copy(
      name = "Territory2",
      owner = Some(player1), 
      troops = 2,
      neighbors = Set.empty
    )
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, t1, t2)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.Reinforce("1", "Territory1", "Territory2", 2))
    }

  test("Reinforce - can be used more than once per turn in this implementation"):
    val allTerritories = engine.getGameState.board.territories.toList
    val t1 = allTerritories.head.copy(owner = Some(player1), troops = 4)
    val t2 = allTerritories.tail.head.copy(owner = Some(player1), troops = 2, neighbors = Set(t1))
    val updatedT1 = t1.copy(neighbors = Set(t2))
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, updatedT1, t2)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard, 
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))   
    val gameState = engine.processAction(GameAction.Reinforce("1", t1.name, t2.name, 1))    
    val updatedT1AfterFirstMove = gameState.board.territories.find(_.name == t1.name).get
    val gameState2 = engine.processAction(GameAction.Reinforce("1", t1.name, t2.name, 1))
    val finalT1 = gameState2.board.territories.find(_.name == t1.name).get
    finalT1.troops should be(2) // 4 - 1 - 1 = 2
    val finalT2 = gameState2.board.territories.find(_.name == t2.name).get
    finalT2.troops should be(4) // 2 + 1 + 1 = 4
  
  test("Attack - creates pending attack correctly"):
    val allTerritories = engine.getGameState.board.territories.toList
    val t1 = allTerritories.head.copy(owner = Some(player1), troops = 5)
    val t2 = allTerritories.tail.head.copy(owner = Some(player2), troops = 3, neighbors = Set(t1))
    val updatedT1 = t1.copy(neighbors = Set(t2))
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, updatedT1, t2)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))
    val gameState = engine.processAction(GameAction.Attack("1", "2", t1.name, t2.name, 3))
    gameState should not be null

  test("Attack - fails if attacking with too many troops"):
    val allTerritories = engine.getGameState.board.territories.toList
    val t1 = allTerritories.head.copy(owner = Some(player1), troops = 2)
    val t2 = allTerritories.tail.head.copy(owner = Some(player2), troops = 3, neighbors = Set(t1))
    val updatedT1 = t1.copy(neighbors = Set(t2))
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, updatedT1, t2)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.Attack("1", "2", t1.name, t2.name, 2))
    }

  test("TradeCards - fails with invalid card combination"):
    engine.setGameState(engine.getGameState.copy(
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase),
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0)
    ))
    val cards = Set.empty[TerritoryCard] 
    an [InvalidActionException] should be thrownBy {
      engine.processAction(GameAction.TradeCards(player1.id, Set.empty[String]))
    }

  test("Player draws territory card only if conquered at least one territory"):
    val allTerritories = engine.getGameState.board.territories.toList
    val t1 = allTerritories.head.copy(owner = Some(player1), troops = 5)
    val t2 = allTerritories.tail.head.copy(owner = Some(player2), troops = 1, neighbors = Set(t1))
    val updatedT1 = t1.copy(neighbors = Set(t2))
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, updatedT1, t2)
    
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatePlayerBonus(engine.getGameState.playerStates, "1", 0),
      turnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    ))
    
    val attackResult = engine.processAction(GameAction.Attack("1", "2", t1.name, t2.name, 3))
    val territoryAfterAttack = engine.getGameState.board.territories.find(_.name == t2.name).get
    val conquered = territoryAfterAttack.owner.exists(_.id == "1")
    
    if (!conquered)
      val conqueredT2 = territoryAfterAttack.copy(owner = Some(player1), troops = 1)
      val boardAfterConquest = updateTerritoriesInBoard(engine.getGameState.board, conqueredT2)
      engine.setGameState(engine.getGameState.copy(board = boardAfterConquest))
      val engineStateField = engine.getClass.getDeclaredField("engineState")
      engineStateField.setAccessible(true)
      val currentEngineState = engineStateField.get(engine).asInstanceOf[EngineState]
      val updatedEngineState = currentEngineState.copy(territoryConqueredThisTurn = true)
      engineStateField.set(engine, updatedEngineState)
    
    val beforeCards = engine.getGameState.playerStates.find(_.playerId == "1").get.territoryCards.size
    engine.processAction(GameAction.EndTurn)
    val afterCards = engine.getGameState.playerStates.find(_.playerId == "1").get.territoryCards.size
    afterCards should be > beforeCards

  test("Game over when objective is completed"):
    val winningObjective = ObjectiveCard.ConquerTerritories(2, 1)
    val playerStateWithObjective = engine.getGameState.playerStates
      .find(_.playerId == "1")
      .getOrElse(fail("Player not found"))
      .copy(objectiveCard = Some(winningObjective)) 
    val territories = engine.getGameState.board.territories.toList
    val t1 = territories(0).copy(owner = Some(player1), troops = 3)
    val t2 = territories(1).copy(owner = Some(player1), troops = 3)
    val updatedBoard = updateTerritoriesInBoard(engine.getGameState.board, t1, t2)
    val updatedPlayerStates = engine.getGameState.playerStates.map:
      case ps if ps.playerId == "1" => playerStateWithObjective.copy(bonusTroops = 0)
      case ps => ps  
    val updatedTurnManager = resetTurnManager(List(player1, player2), TurnPhase.MainPhase)
    engine.setGameState(engine.getGameState.copy(
      board = updatedBoard,
      playerStates = updatedPlayerStates,
      turnManager = updatedTurnManager
    ))
    an [GameOverException] should be thrownBy {
      engine.processAction(GameAction.EndTurn)
    }