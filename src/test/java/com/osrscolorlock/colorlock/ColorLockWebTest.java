package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockWebTest
{
	@Test
	public void legacyVercelItemsUrlIsMigrated()
	{
		Assert.assertTrue(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(ColorLockWeb.LEGACY_VERCEL_ITEMS_JSON));
		Assert.assertTrue(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(ColorLockWeb.LEGACY_VERCEL_API_ITEMS));
		Assert.assertTrue(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(
			"https://osrs-color-lock.vercel.app/data/items.json?x=1"));
		Assert.assertFalse(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(ColorLockWeb.DEFAULT_ITEMS_JSON));
		Assert.assertFalse(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(null));
		Assert.assertFalse(ColorLockWeb.shouldMigrateLegacyVercelItemsUrl(""));
	}
}
