package org.tc.autonomous;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI.CrewXPLevel;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import java.awt.Color;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;


public class AutonomousCommandsPlugin implements EveryFrameCombatPlugin {

	private static Logger LOG = Global.getLogger(AutonomousCommandsPlugin.class);
	
	private static Color TEXT_COLOR = Global.getSettings().getColor("standardTextColor");
	private static Color SHIP_COLOR = Global.getSettings().getColor("textFriendColor");
	private static Color MESSAGE_COLOR = Color.CYAN;

	public static final String CONFIG_FILE = "data/config/autonomous-ships.json";
	public static final String RETREAT_COMMAND_PREFIX = "autonomous_retreat_";

	public static Map<String, Double> retreatHullMods = new HashMap<>();
	static {
		try {
			LOG.info("Loading autonomous commands configuration from " + CONFIG_FILE + " ...");
			JSONObject settings = Global.getSettings().loadJSON(CONFIG_FILE);
			for (int i = 1; i <= 3; i++) {
				String key = RETREAT_COMMAND_PREFIX + i;
				double value = settings.getDouble(key);
				retreatHullMods.put(key, value);
			}
			LOG.info("Retreat command hullmods: " + retreatHullMods);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private CombatEngineAPI engine;

	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		if (engine == null) return;
		if (engine.isPaused()) return;
		if (engine.isSimulation()) return;
		if (Global.getCurrentState() == GameState.TITLE) return;
		
		// TODO(hqz) only act every second or so.

		// TODO(hqz) improve performance by skipping some later checks once we assign an order.

		CombatFleetManagerAPI fleetManager = engine.getFleetManager(FleetSide.PLAYER);
		for (FleetMemberAPI member : fleetManager.getDeployedCopy())
		{
			if (member.isFighterWing()) continue;
			if (member.isFlagship()) continue;
			if (member.isAlly()) continue;

			ShipAPI ship = fleetManager.getShipFor(member);
			if (ship.getShipAI() == null) continue;
			if (!ship.isAlive()) continue;
			ShipVariantAPI variant = ship.getVariant();

			// Retreat when no missile left.
			if (variant.hasHullMod("autonomous_retreat_no_missile")) {
				boolean hasMissileSlots = false;
				boolean hasMissileLeft = false;

				for (WeaponAPI weapon : ship.getAllWeapons()) {
					if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
						hasMissileSlots = true;
						if (weapon.getAmmo() != 0) {
							hasMissileLeft = true;
							break;
						}
					}
				}

				if (hasMissileSlots && !hasMissileLeft) {
					orderRetreat(fleetManager, ship, "no missile left");
					continue;
				}
			}

			// Retreat when exceeded peak performance time and below a certain level of CR.
			for (int level : Arrays.asList(100, 60, 40)) {
				if (variant.hasHullMod("autonomous_retreat_cr_below_" + level)) {
					float threshold = (float)level / 100f;
					if (ship.getCurrentCR() < ship.getCRAtDeployment() && ship.getCurrentCR() < threshold) {
						String reason =  String.format("combat readiness is %d%%", (int)(ship.getCurrentCR() * 100f));
						orderRetreat(fleetManager, ship, reason);
						break;
					}
				}
			}

			// Retreat on hull damage.
			float hull = ship.getHullLevel();
			if (hull == 1.0) return;
			for (Map.Entry<String, Double> hullMod : retreatHullMods.entrySet()) {
				if (variant.hasHullMod(hullMod.getKey()) && hull < hullMod.getValue()) {
					orderRetreat(fleetManager, ship, String.format("hull integrity is %d%%", (int)(hull * 100f)));
					break;
				}
			}
		}
	}

	private static void orderRetreat(CombatFleetManagerAPI fleetManager, ShipAPI ship, String reason) {
		CombatTaskManagerAPI taskManager = fleetManager.getTaskManager(false);
		CombatFleetManagerAPI.AssignmentInfo assignment = taskManager.getAssignmentFor(ship);
		if (assignment == null || assignment.getType() != CombatAssignmentType.RETREAT) {
			LOG.info(ship.getName() + " retreating (" + reason + ")");
			String message = reason + " - retreating ...";
			DeployedFleetMemberAPI member = fleetManager.getDeployedFleetMember(ship);
			Global.getCombatEngine().getCombatUI().addMessage(1, member, SHIP_COLOR, ship.getName(), TEXT_COLOR, ": ", MESSAGE_COLOR, message);
			taskManager.orderRetreat(member, false);
		}
	}

	@Override
	public void renderInWorldCoords(ViewportAPI vapi) {
	}

	@Override
	public void renderInUICoords(ViewportAPI vapi) {
	}

	@Override
	public void init(CombatEngineAPI engine) {
		this.engine = engine;
		LOG.info("Autonomous commands plugin initialized");
	}
}