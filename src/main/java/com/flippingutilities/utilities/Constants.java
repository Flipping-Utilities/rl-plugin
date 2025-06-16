package com.flippingutilities.utilities;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

public class Constants {
    //epoch seconds that GE tax was introduced. This is not the exact time, just a close
    //enough approximation
    public static int GE_TAX_START = 1639072800;
	// Approximate timestamp of when GE tax was increased to 2%
	public static int GE_TAX_INCREASED = 1748514600;
    public static int GE_TAX_CAP = 5000000;
	// Max price prior to tax being increased to 2%
	public static int OLD_MAX_PRICE_FOR_GE_TAX = 500000000;
    public static int MAX_PRICE_FOR_GE_TAX = 250000000;
	// Tax rate prior to 29 May 2025
	public static double OLD_GE_TAX = 0.01; // 1%
    public static double GE_TAX = 0.02; // 2%
    public static String DUMMY_ITEM = "dummy";

	public static final Set<Integer> TAX_EXEMPT_ITEMS = ImmutableSet.of(
		ItemID.CHISEL,
		ItemID.GARDENING_TROWEL,
		ItemID.HAMMER,
		ItemID.NEEDLE,
		ItemID.OSRS_BOND,
		ItemID.PESTLE_AND_MORTAR,
		ItemID.RAKE,
		ItemID.POH_SAW,
		ItemID.SECATEURS,
		ItemID.DIBBER,
		ItemID.SHEARS,
		ItemID.SPADE,
		ItemID.WATERING_CAN_0
	);

	// Items made tax-exempt on 29 May 2025
	public static final Set<Integer> NEW_TAX_EXEMPT_ITEMS = ImmutableSet.of(
		ItemID.POH_TABLET_ARDOUGNETELEPORT,
		ItemID.BASS,
		ItemID.BREAD,
		ItemID.BRONZE_ARROW,
		ItemID.BRONZE_DART,
		ItemID.CAKE,
		ItemID.POH_TABLET_CAMELOTTELEPORT,
		ItemID.POH_TABLET_FORTISTELEPORT,
		ItemID.COOKED_CHICKEN,
		ItemID.COOKED_MEAT,
		ItemID._4DOSE1ENERGY,
		ItemID._3DOSE1ENERGY,
		ItemID._2DOSE1ENERGY,
		ItemID._1DOSE1ENERGY,
		ItemID.POH_TABLET_FALADORTELEPORT,
		ItemID.NECKLACE_OF_MINIGAMES_8,
		ItemID.HERRING,
		ItemID.IRON_ARROW,
		ItemID.IRON_DART,
		ItemID.POH_TABLET_KOURENDTELEPORT,
		ItemID.LOBSTER,
		ItemID.POH_TABLET_LUMBRIDGETELEPORT,
		ItemID.MACKEREL,
		ItemID.MEAT_PIE,
		ItemID.MINDRUNE,
		ItemID.PIKE,
		ItemID.RING_OF_DUELING_8,
		ItemID.SALMON,
		ItemID.SHRIMP,
		ItemID.STEEL_ARROW,
		ItemID.STEEL_DART,
		ItemID.POH_TABLET_TELEPORTTOHOUSE,
		ItemID.TUNA,
		ItemID.POH_TABLET_VARROCKTELEPORT
	);

}
