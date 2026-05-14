package com.osrscolorlock.colorlock;

import com.google.gson.Gson;
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

}
