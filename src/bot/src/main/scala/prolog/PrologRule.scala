package prolog

import strategy.*
import engine.GameState
import engine.GameAction
import alice.tuprolog.*
import prolog.PrologEngine.given_Conversion_String_Theory
import prolog.PrologEngine.given_Conversion_String_Term
import exceptions._

/**
 * Trait that implements a strategy rule using Prolog.
 * @param theoryName the name of the Prolog theory file (without extension)
 */
trait PrologRule(val theoryName: String) extends StrategyRule:

  private val engine: PrologEngine = PrologEngine("/theories/" + theoryName + ".pl")

  /**
   * Evaluates all possible actions for the player using the Prolog theory.
   * @param gameState The current state of the game.
   * @param playerId The id of the player for whom to evaluate actions.
   * @return A set of RatedAction representing possible actions and their scores.
   */
  override def evaluateAction(gameState: GameState, playerId: String): Set[RatedAction] =
    val (territoriesStr, neighborStr) = encodeGameState(gameState)
    val phase = gameState.turnManager.currentPhase.toString
    val actionString = "Action"
    val scoreString = "Score" 
    val descString = "Description"
    val goal = s"${theoryName.toLowerCase}($territoriesStr, $neighborStr, '$phase', '$playerId', $actionString, $scoreString, $descString)"   
    val solutions = engine.solveAll(goal, actionString, scoreString, descString)
    val actions = solutions.map(terms => {
      val actionTerm = terms(actionString)
      val score = terms(scoreString).toString.toDouble
      val description = terms(descString).toString.replaceAll("'", "")        
      try {
        val parsedAction = parseAction(gameState, actionTerm, playerId)
        RatedAction(parsedAction, score, description)
      } catch {
        case e: Exception => throw e
      }
    }).toSet

    actions

  /**
   * Encodes the game state as Prolog facts for territories and neighbors.
   * @param gameState The current state of the game.
   * @return A tuple of (territoriesStr, neighborStr) as Prolog lists.
   */
  protected def encodeGameState(gameState: GameState): (String, String) = 
    val territoryMap = gameState.board.territories.map(t => t.name -> t).toMap
    // territory('TerritoryName', 'OwnerId', Troops)
    val territoriesStr = gameState.board.territories.map { t =>
      val owner = t.owner.map(_.id).getOrElse("none")
      s"territory('${escapeName(t.name)}', '$owner', ${t.troops})"
    }.mkString("[", ",", "]")    
    // neighbor('TerritoryName', 'NeighborName', 'NeighborOwnerId')
    val neighborStr = gameState.board.territories.flatMap { t =>
      t.neighbors.map { n => 
        val updatedNeighbor = territoryMap.getOrElse(n.name, n)
        val neighOwner = updatedNeighbor.owner.map(_.id).getOrElse("none")
        s"neighbor('${escapeName(t.name)}', '${escapeName(n.name)}', '$neighOwner')"
      }
    }.mkString("[", ",", "]")    
    (territoriesStr, neighborStr)

  private def escapeName(name: String): String = 
    name.replace("'", "\\'")

  /**
   * Parses a Prolog action term into a GameAction.
   * @param gameState The current state of the game.
   * @param actionTerm The Prolog term representing the action.
   * @param playerId The id of the player.
   * @return The corresponding GameAction.
   */
  protected def parseAction(gameState: GameState, actionTerm: Term, playerId: String): GameAction =
    if (actionTerm.toString.startsWith("place_troops"))   // functors
      val args = extractArgs(actionTerm)
      val territoryName = args(0)
      val troops = args(1).toInt
      GameAction.PlaceTroops(playerId, troops, territoryName)

    else if (actionTerm.toString.startsWith("reinforce"))
      val args = extractArgs(actionTerm)
      val from = args(0)
      val to = args(1)
      val troops = args(2).toInt
      GameAction.Reinforce(playerId, from, to, troops)

    else if (actionTerm.toString.startsWith("attack"))
      val args = extractArgs(actionTerm)
      val from = args(0)
      val to = args(1)
      val troops = args(2).toInt
      val defenderId = gameState.board.territories
        .find(_.name == to)
        .flatMap(_.owner)
        .map(_.id)
        .getOrElse(throw new InvalidActionException())
      GameAction.Attack(playerId, defenderId, from, to, troops)
     
    else if (actionTerm.toString.startsWith("end_turn")) then GameAction.EndTurn
    else throw new InvalidActionException()

  private def extractArgs(term: Term): Array[String] = 
    val content = term.toString
    val argsStr = content.substring(content.indexOf("(") + 1, content.lastIndexOf(")"))
    argsStr.split(",").map(_.trim.replaceAll("'", ""))
