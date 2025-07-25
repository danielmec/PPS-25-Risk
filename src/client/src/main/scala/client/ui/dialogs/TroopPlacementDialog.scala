package client.ui.dialogs

import scalafx.Includes._
import scalafx.application.Platform
import scalafx.geometry.Insets
import scalafx.geometry.Pos
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.layout._
import scalafx.stage.Stage
import scalafx.stage.Modality
import scalafx.stage.StageStyle
import scalafx.collections.ObservableBuffer
import scalafx.beans.property.ObjectProperty
import scalafx.beans.property.IntegerProperty
import scalafx.beans.binding.Bindings
import client.AdapterMap.UITerritory
import client.ui.GameWindow
import model.cards.TerritoryCard
import utils.BonusCalculator

/**
 * Dialog that allows the player to assign troops to owned territories and optionally
 * trade a valid set of territory cards (a "tris") for bonus troops.
 * 
 * This dialog is used both during the Setup phase (to place initial troops)
 * and the Main phase (to place reinforcement troops and trade cards).
 *
 * It displays all owned territories, current troops on each, and interactive controls
 * for distributing available troops. It also shows owned territory cards and enables
 * trading if a valid combination is selected.
 *
 * @param owner Reference to the main game window.
 * @param territories List of owned territories (wrapped as UITerritory).
 * @param initialTroops Initial number of troops to place.
 * @param currentPhase Current phase of the game ("SetupPhase" or "MainPhase").
 * @param territoryCards List of territory cards held by the player.
 */
class TroopPlacementDialog(
  owner: GameWindow,
  territories: ObservableBuffer[UITerritory], 
  initialTroops: Int,
  currentPhase: String,
  territoryCards: Seq[TerritoryCard] 
) extends Stage {

  // Number of troops remaining to assign
  val remainingTroops = IntegerProperty(initialTroops)

  // Internal tracking of how many troops are assigned to each territory
  private val troopAssignments = collection.mutable.Map[String, Int]()

  // Initialize dialog properties
  initOwner(owner)
  initModality(Modality.APPLICATION_MODAL)
  initStyle(StageStyle.UTILITY)
  title = "Piazzamento Truppe"
  width = 600
  height = 700
  
  val headerLabel = new Label("Assegna tutte le tue truppe ai territori")
  headerLabel.style = "-fx-font-size: 16px; -fx-font-weight: bold;"
  
  val troopsLabel = new Label()
  troopsLabel.text <== remainingTroops.asString("Truppe rimanenti: %d")
  troopsLabel.style = "-fx-font-size: 14px;"
  
  val territoryControls = territories.map { territory =>
    val nameLabel = new Label(territory.name)
    nameLabel.prefWidth = 200
    nameLabel.style = "-fx-font-weight: bold;"
    
    val currentTroopsLabel = new Label(s"Truppe attuali: ${territory.armies.value}")
    currentTroopsLabel.prefWidth = 100
    
    val troopsSpinner = new Spinner[Int](0, remainingTroops.value, 0)
    troopsSpinner.editable = true
    
    troopAssignments(territory.name) = 0

    // Listener: handle troop count changes
    troopsSpinner.valueProperty().addListener { (_, oldValue, newValue) =>
      val oldVal = oldValue.intValue()
      val newVal = newValue.intValue()

      if (newVal > oldVal) {
        val diff = newVal - oldVal
        if (diff > remainingTroops.value) {
          val maxAllowed = oldVal + remainingTroops.value
          Platform.runLater {
            troopsSpinner.getValueFactory.setValue(maxAllowed)
          }
        } else {
          remainingTroops.value -= diff
          troopAssignments(territory.name) = newVal
        }
      } else if (newVal < oldVal) {
        val diff = oldVal - newVal
        remainingTroops.value += diff
        troopAssignments(territory.name) = newVal
      }
    }

    // Update spinner max value when remaining troops change
    remainingTroops.onChange { (_, _, newValue) =>
      Platform.runLater {
        val currentTroopCount = troopAssignments(territory.name)
        val maxAllowed = currentTroopCount + newValue.intValue
        val factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, maxAllowed, currentTroopCount)
        troopsSpinner.valueFactory = factory.asInstanceOf[SpinnerValueFactory[Int]]
      }
    }
    
    val row = new HBox(15)
    row.alignment = Pos.CenterLeft
    row.children = Seq(nameLabel, currentTroopsLabel, troopsSpinner)
    
    (row, territory, troopsSpinner)
  }
  
  val territoriesList = new VBox(10)
  territoriesList.padding = Insets(10)
  territoriesList.children = territoryControls.map(_._1)
  
  val scrollPane = new ScrollPane {
    content = territoriesList
    fitToWidth = true
    vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
  }
  
  val showObjectiveButton = new Button("Mostra Obiettivo")
  showObjectiveButton.onAction = handle {
    val wasShowing = showing.value
    hide()
    owner.showObjectiveDialog()
    if (wasShowing) {
      show()
      toFront()
    }
  }
  
  val showTerritoriesButton = new Button("Mostra Territori")
  showTerritoriesButton.onAction = handle {
    val wasShowing = showing.value
    hide()
    owner.showTerritoriesDialog()
    if (wasShowing) {
      show()
      toFront()
    }
  }
  
  val confirmButton = new Button("Conferma Piazzamento")
  confirmButton.disable <== remainingTroops =!= 0
  
  confirmButton.onAction = handle {
    troopAssignments.foreach { case (territoryName, troops) =>
      if (troops > 0) {
        val territory = territories.find(_.name == territoryName).get
        val currentTroops = territory.armies.value
        territory.armies.value = currentTroops + troops

        owner.actionHandler.placeTroops(owner.getGameId, territoryName, troops)
      }
    }
    
    if (currentPhase == "SetupPhase") {
      println("[Dialog] Fine piazzamento in fase SetupPhase, invio end_setup")
      owner.actionHandler.endSetup(owner.getGameId)
    } else {
      println("[Dialog] Fine piazzamento in fase MainPhase, chiudo solo il dialogo senza inviare end_turn")
    }
    
    close()
  }

  /** Row of action buttons */
  val buttonsBox = new HBox(10) {
    children = Seq(showObjectiveButton, showTerritoriesButton, confirmButton)
    alignment = Pos.CenterRight
  }

  /** ListView displaying the player's current territory cards */
  val cardsListView = new ListView[TerritoryCard](ObservableBuffer(territoryCards: _*)) {
    selectionModel().setSelectionMode(SelectionMode.Multiple)
    cellFactory = (_: ListView[TerritoryCard]) => new ListCell[TerritoryCard] {
      item.onChange { (_, _, newItem) =>
        text = Option(newItem).map(card => s"${card.territory.name} (${card.cardImg})").getOrElse("")
      }
    }
    maxHeight = 100
  }

  val tradeButton = new Button("Scambia tris per bonus") {
    disable = true
    onAction = handle {
      val selected = cardsListView.selectionModel().getSelectedItems.toSet
      val cardNames = selected.map(_.territory.name).toList
      owner.actionHandler.tradeCards(owner.getGameId, cardNames)
    }
  }

  // Enable trade only if a valid set of 3 cards is selected
  cardsListView.selectionModel().selectedItems.onChange { (_, _) =>
    refreshTradeButtonState()
  }

  /** Refreshes the enabled state of the trade button based on selection */
  def refreshTradeButtonState(): Unit = {
    val selected = cardsListView.selectionModel().getSelectedItems.toList
    val bonus = BonusCalculator.calculateTradeBonus(selected)
    tradeButton.disable = selected.size != 3 || bonus == 0
  }

  /** Section displaying card-related controls */
  val cardsSection = new VBox(8) {
    children = Seq(
      new Label("Le tue carte territorio:"),
      cardsListView,
      tradeButton
    )
    padding = Insets(10, 0, 10, 0)
  }

  /** Main layout of the dialog */
  val root = new BorderPane {
    padding = Insets(15)
    top = new VBox(10) {
      children = Seq(headerLabel, troopsLabel)
      alignment = Pos.Center
    }
    center = scrollPane
    bottom = new VBox(10) {
      children = Seq(cardsSection, new HBox(10) {
        children = Seq(showObjectiveButton, showTerritoriesButton, confirmButton)
        alignment = Pos.CenterRight
        padding = Insets(10, 0, 0, 0)
      })
      alignment = Pos.BottomCenter
    }
  }

  /** Scene initialization */
  scene = new Scene(root)

  /**
   * Updates the number of remaining troops when new reinforcements are received.
   * Should be called externally if the available troop count changes dynamically.
   *
   * @param newTroopsCount The new total number of troops available.
   */
  def updateTroops(newTroopsCount: Int): Unit = {
    val assignedTroops = troopAssignments.values.sum
    Platform.runLater {
      remainingTroops.value = newTroopsCount - assignedTroops
    }
  }

  /**
   * Updates the card list shown in the dialog.
   * Call this after card trades or updates from the game state.
   *
   * @param newCards The updated list of cards to display.
   */
  def updateCards(newCards: Seq[TerritoryCard]): Unit = {
    Platform.runLater {
      println(s"[DEBUG] updateCards: ${newCards.map(_.cardImg)}")
      cardsListView.items.value.setAll(newCards: _*)
      refreshTradeButtonState()
    }
  }
}
