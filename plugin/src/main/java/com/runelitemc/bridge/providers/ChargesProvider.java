package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/**
 * How many charges are left on charged gear.
 *
 * Charges reach a client three different ways, and a caller that does not know
 * which is which will draw the wrong conclusion from a zero:
 *
 * 1. In the item name. Ring of wealth (4), Digsite pendant (5), Skills
 *    necklace (6), Prayer potion(4) — the game encodes uses in the item id, so
 *    the count travels with the item. Those are NOT here: they are broken out
 *    as a "charges" field on every item in bank_snapshot, game_state and
 *    find_item, where the item itself is.
 * 2. In a varbit. Xeric's talisman, the crystal and toxic equipment, tomes,
 *    Tumeken's shadow and so on keep an account-wide counter. Those are exact,
 *    and they are what this tool mostly serves.
 * 3. Counted by RuneLite from chat. Dodgy necklace, ring of forging, amulet of
 *    chemistry and friends have no counter the client can read, so RuneLite's
 *    Item Charges plugin infers them from game messages. Those are marked
 *    source "runelite" and are an estimate: they need that plugin enabled, and
 *    they drift if the item was used while it was off.
 *
 * A varbit counter reads zero both for an uncharged item and for an item the
 * player has never owned, so zeroes are dropped unless asked for.
 */
public class ChargesProvider
{
	private static final String ITEM_CHARGE_GROUP = "itemCharge";

	private static final class Charged
	{
		private final String item;
		private final int varbit;
		private final String unit;

		private Charged(String item, int varbit, String unit)
		{
			this.item = item;
			this.varbit = varbit;
			this.unit = unit;
		}
	}

	/**
	 * Only counters whose meaning is unambiguous. RuneLite's varbit list also
	 * carries upgrade-progress counters that look like charges and are not
	 * (arclight synapses, blade/bow corruption); reporting those as charges
	 * would be worse than omitting them.
	 */
	private static final Charged[] VARBIT_CHARGES = {
		// Teleports and jewellery
		new Charged("Xeric's talisman", VarbitID.CHARGES_XERICS_TALISMAN_QUANTITY, "teleports"),
		new Charged("Ring of the elements", VarbitID.RING_OF_THE_ELEMENTS_CHARGES, "teleports"),
		new Charged("Ring of shadows", VarbitID.CHARGES_RING_OF_SHADOWS_QUANTITY, "teleports"),
		new Charged("Camulet", VarbitID.ENAKH_CAMULET_CHARGE, "teleports"),
		new Charged("Ring of suffering (r)", VarbitID.CHARGES_RING_OF_SUFFERING_QUANTITY, "recoil charges"),
		new Charged("Amulet of blood fury", VarbitID.CHARGES_BLOOD_FURY_QUANTITY, "charges"),
		new Charged("Bracelet of ethereum", VarbitID.CHARGES_BRACELET_OF_ETHEREUM_QUANTITY, "charges"),
		new Charged("Celestial ring", VarbitID.CHARGES_CELESTIAL_RING_QUANTITY, "charges"),
		new Charged("Alchemist's amulet", VarbitID.CHARGES_ALCHEMISTS_AMULET_QUANTITY, "charges"),
		new Charged("Amulet of the giantsoul", VarbitID.CHARGES_GIANTSOUL_AMULET_QUANTITY, "charges"),
		new Charged("Pendant of ates", VarbitID.CHARGES_PENDANT_OF_ATES_QUANTITY, "charges"),
		new Charged("Eye of ayak", VarbitID.CHARGES_EYE_OF_AYAK_QUANTITY, "charges"),
		new Charged("Bonecrusher necklace", VarbitID.CHARGES_BONECRUSHER_QUANTITY, "prayer charges"),
		new Charged("Ash sanctifier", VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY, "prayer charges"),
		new Charged("Soul bearer", VarbitID.CHARGES_SOUL_BEARER_QUANTITY, "charges"),

		// Weapons and armour
		new Charged("Trident of the seas", VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY, "casts"),
		new Charged("Trident of the seas (e)", VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_E_QUANTITY, "casts"),
		new Charged("Merfolk trident", VarbitID.FOSSIL_MERFOLK_TRIDENT_CHARGES, "casts"),
		new Charged("Toxic staff of the dead", VarbitID.CHARGES_TOXIC_STAFF_OF_THE_DEAD_QUANTITY, "charges"),
		new Charged("Toxic blowpipe", VarbitID.CHARGES_TOXIC_BLOWPIPE_QUANTITY, "scales"),
		new Charged("Serpentine helm", VarbitID.CHARGES_SERPENTINE_HELM_QUANTITY, "scales"),
		new Charged("Sanguinesti staff", VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY, "charges"),
		new Charged("Tumeken's shadow", VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY, "charges"),
		new Charged("Arclight", VarbitID.CHARGES_ARCLIGHT_QUANTITY, "charges"),
		new Charged("Craw's bow / Viggora's chainmace / Thammaron's sceptre",
			VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, "charges"),
		new Charged("Bryophyta's staff", VarbitID.CHARGES_BRYOPHYTAS_STAFF_QUANTITY, "charges"),
		new Charged("Warped sceptre", VarbitID.CHARGES_WARPED_SCEPTRE_QUANTITY, "charges"),
		new Charged("Venator bow", VarbitID.CHARGES_VENATOR_BOW_QUANTITY, "charges"),
		new Charged("Tonalztics of ralos", VarbitID.CHARGES_TONALZTICS_OF_RALOS_QUANTITY, "charges"),
		new Charged("Blade of saeldor", VarbitID.CHARGES_BLADE_OF_SAELDOR_QUANTITY, "charges"),
		new Charged("Bow of faerdhinen", VarbitID.CHARGES_BOW_OF_FAERDHINEN_QUANTITY, "charges"),
		new Charged("Crystal armour", VarbitID.CHARGES_CRYSTAL_ARMOUR_QUANTITY, "charges"),
		new Charged("Crystal tools", VarbitID.CHARGES_CRYSTAL_TOOLS_QUANTITY, "charges"),
		new Charged("Echo boots", VarbitID.CHARGES_ECHO_BOOTS_QUANTITY, "charges"),
		new Charged("Dizana's quiver", VarbitID.CHARGES_DIZANAS_QUIVER_QUANTITY, "charges"),

		// Tomes and utility
		new Charged("Tome of fire", VarbitID.CHARGES_TOME_OF_FIRE_QUANTITY, "pages"),
		new Charged("Tome of water", VarbitID.CHARGES_TOME_OF_WATER_QUANTITY, "pages"),
		new Charged("Tome of earth", VarbitID.CHARGES_TOME_OF_EARTH_QUANTITY, "pages"),
		new Charged("Circlet of water", VarbitID.CHARGES_CIRCLET_OF_WATER_QUANTITY, "charges"),
		new Charged("Gricoller's can", VarbitID.ZEAH_WATERINGCAN_CHARGES, "waterings"),
		new Charged("Crystal saw", VarbitID.EYEGLO_CRYSTAL_SAW_CHARGES, "charges"),
	};

	private static final class Tracked
	{
		private final String item;
		private final String key;
		private final String unit;

		private Tracked(String item, String key, String unit)
		{
			this.item = item;
			this.key = key;
			this.unit = unit;
		}
	}

	/** Keys written by RuneLite's Item Charges plugin into its RS-profile config. */
	private static final Tracked[] TRACKED_CHARGES = {
		new Tracked("Dodgy necklace", "dodgyNecklace", "pickpockets"),
		new Tracked("Ring of forging", "ringOfForging", "iron ore smelts"),
		new Tracked("Amulet of chemistry", "amuletOfChemistry", "potions"),
		new Tracked("Amulet of bounty", "amuletOfBounty", "seeds planted"),
		new Tracked("Binding necklace", "bindingNecklace", "runecrafts"),
		new Tracked("Explorer's ring", "explorerRing", "alchemies today"),
		new Tracked("Bracelet of slaughter", "braceletOfSlaughter", "charges"),
		new Tracked("Expeditious bracelet", "expeditiousBracelet", "charges"),
		new Tracked("Bracelet of clay", "braceletOfClay", "clay mines"),
		new Tracked("Blood essence", "bloodEssence", "charges"),
		new Tracked("Chronicle", "chronicle", "teleports"),
	};

	private final Client client;
	private final ConfigManager configManager;

	public ChargesProvider(Client client, ConfigManager configManager)
	{
		this.client = client;
		this.configManager = configManager;
	}

	/**
	 * @param includeEmpty report counters reading zero too. Off by default: a
	 *                     zero varbit does not distinguish "uncharged" from
	 *                     "never owned", so the zeroes are mostly noise.
	 */
	public JsonObject charges(boolean includeEmpty)
	{
		JsonObject out = new JsonObject();
		out.addProperty("capturedAt", Instant.now().toString());

		JsonArray charges = new JsonArray();
		for (Charged charged : VARBIT_CHARGES)
		{
			int value = client.getVarbitValue(charged.varbit);
			if (value > 0 || includeEmpty)
			{
				charges.add(entry(charged.item, value, charged.unit, "game", null));
			}
		}

		String profile = configManager.getRSProfileKey();
		for (Tracked tracked : TRACKED_CHARGES)
		{
			Integer value = profile == null
				? null
				: configManager.getConfiguration(ITEM_CHARGE_GROUP, profile, tracked.key, Integer.class);
			if (value == null)
			{
				continue;
			}
			if (value > 0 || includeEmpty)
			{
				charges.add(entry(tracked.item, value, tracked.unit, "runelite",
					"Counted by RuneLite's Item Charges plugin from game messages, not read from the game — "
						+ "an estimate that drifts if the item was used with that plugin disabled."));
			}
		}

		out.add("chargedItems", charges);
		out.addProperty("includesEmpty", includeEmpty);
		out.addProperty("note", "Items whose charge count is part of their name — Ring of wealth (4), Digsite "
			+ "pendant (5), Skills necklace (6), potions — are not listed here. Their count rides on the item "
			+ "itself, as a \"charges\" field in bank_snapshot, game_state and find_item.");
		return out;
	}

	private static JsonObject entry(String item, int value, String unit, String source, String note)
	{
		JsonObject o = new JsonObject();
		o.addProperty("item", item);
		o.addProperty("charges", value);
		o.addProperty("unit", unit);
		o.addProperty("source", source);
		if (note != null)
		{
			o.addProperty("chargeNote", note);
		}
		return o;
	}
}
