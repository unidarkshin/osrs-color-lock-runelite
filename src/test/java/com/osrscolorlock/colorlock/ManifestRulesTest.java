package com.osrscolorlock.colorlock;

import com.google.gson.Gson;

import java.util.Arrays;
import java.util.Collections;

import java.util.HashSet;

import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class ManifestRulesTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void usableMatchAndRestricted()
	{
		ManifestItem redFood = GSON.fromJson(
			"{\"id\":1,\"name\":\"x\",\"usableColors\":[\"red\"]}",
			ManifestItem.class);
		Assert.assertTrue(ManifestRules.isUsableByAssignment(redFood, ColorLockColor.RED));
		Assert.assertFalse(ManifestRules.isUsableByAssignment(redFood, ColorLockColor.BLUE));
		Assert.assertTrue(ManifestRules.isRestrictedForAssignment(redFood, ColorLockColor.BLUE));
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(redFood, ColorLockColor.RED));
	}

	@Test
	public void unknownOrEmptyNotRestricted()
	{
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(null, ColorLockColor.RED));
		ManifestItem empty = GSON.fromJson(
			"{\"id\":2,\"name\":\"e\",\"usableColors\":[]}",
			ManifestItem.class);
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(empty, ColorLockColor.RED));
	}

	@Test
	public void potionIsGatedUntilHubOptsOutWithoutFlagMeansTracked()
	{
		ManifestItem potionTracked = GSON.fromJson(
			"{\"id\":99,\"category\":\"potion\",\"usableColors\":[\"red\"]}",
			ManifestItem.class);
		Assert.assertNull(potionTracked.getColorLockApplies());
		Assert.assertTrue(ManifestRules.isLockEnforced(potionTracked));
		Assert.assertTrue(ManifestRules.isRestrictedForAssignment(potionTracked, ColorLockColor.BLUE));
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(potionTracked, ColorLockColor.RED));
	}

	@Test
	public void explicitColorLockAppliesFalseSkipsThrowables()
	{
		ManifestItem dart = GSON.fromJson(
			"{\"id\":808,\"category\":\"weapon\",\"usableColors\":[\"red\",\"black\"],\"colorLockApplies\":false}",
			ManifestItem.class);
		Assert.assertFalse(ManifestRules.isLockEnforced(dart));
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(dart, ColorLockColor.BLUE));
	}

	@Test
	public void explicitColorLockAppliesTrueEnforcesEvenForPotionCategory()
	{
		ManifestItem forcedPotion = GSON.fromJson(
			"{\"id\":1,\"category\":\"potion\",\"usableColors\":[\"red\"],\"colorLockApplies\":true}",
			ManifestItem.class);
		Assert.assertTrue(ManifestRules.isLockEnforced(forcedPotion));
		Assert.assertTrue(ManifestRules.isRestrictedForAssignment(forcedPotion, ColorLockColor.BLUE));
	}

	@Test
	public void colorLockExcludedTrueSkipsWeapon()
	{
		ManifestItem thrown = GSON.fromJson(
			"{\"id\":800,\"category\":\"weapon\",\"usableColors\":[\"red\"],\"colorLockExcluded\":true}",
			ManifestItem.class);
		Assert.assertFalse(ManifestRules.isLockEnforced(thrown));
	}

	@Test
	public void crewPaletteIntersectsManifest()
	{
		ManifestItem redYellow = GSON.fromJson(
			"{\"id\":3,\"name\":\"x\",\"usableColors\":[\"red\",\"yellow\"]}",
			ManifestItem.class);
		Set<String> crewYellowGreen = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("yellow", "green")));
		Assert.assertTrue(ManifestRules.isRestrictedForAssignment(redYellow, ColorLockColor.RED, crewYellowGreen));
		Assert.assertFalse(ManifestRules.isRestrictedForAssignment(redYellow, ColorLockColor.YELLOW, crewYellowGreen));
		List<String> manifestAll = ManifestRules.usableColorsManifestOrdered(redYellow);
		Assert.assertEquals(2, manifestAll.size());
		List<String> crewOnlyYellow = ManifestRules.usableColorsEffectiveForCrew(redYellow, crewYellowGreen);
		Assert.assertEquals(Arrays.asList("yellow"), crewOnlyYellow);
	}

}
