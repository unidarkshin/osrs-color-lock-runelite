package com.osrscolorlock.colorlock;



import org.junit.Assert;

import org.junit.Test;



public class ColorLockItemsApiTest

{

	@Test

	public void applyPluginItemsQueryAppendsManualDefaults()

	{

		TestColorLockConfig cfg = new TestColorLockConfig();

		cfg.potions = true;

		cfg.includeFood = true;

		cfg.ammo = false;

		String base = "https://group.thegrandchart.com/api/v1/items";

		String out = ColorLockItemsApi.applyPluginItemsQuery(base, cfg);

		Assert.assertTrue(out.contains("includePotions=0"));

		Assert.assertTrue(out.contains("excludeFood=0"));

		Assert.assertTrue(out.contains("includeAmmunition=0"));

		String potUrl = ColorLockItemsApi.buildPotionSupplementItemsUrl(base, cfg);

		Assert.assertTrue(potUrl.contains("includePotions=1"));

		Assert.assertTrue(potUrl.contains("category=potion"));

	}



	@Test

	public void applyPluginItemsQueryReplacesPinnedHubPolicyParams()

	{

		TestColorLockConfig cfg = new TestColorLockConfig();

		cfg.potions = true;

		cfg.includeFood = true;

		String hub = "https://x/api/v1/items?mode=color-lock&colored=1&groupFilters=1"

			+ "&includePotions=0&excludeFood=1&includeAmmunition=0";

		String out = ColorLockItemsApi.applyPluginItemsQuery(hub, cfg);

		Assert.assertTrue(out.contains("includePotions=0"));

		Assert.assertTrue(out.contains("excludeFood=0"));

		Assert.assertFalse(out.contains("excludeFood=1"));

	}



	@Test

	public void syncedQueryOmitsManualPolicyParams()

	{

		TestColorLockConfig cfg = new TestColorLockConfig();

		cfg.sync = true;

		String q = ColorLockItemsApi.buildItemsQueryString(cfg);

		Assert.assertTrue(q.contains("groupFilters=1"));

		Assert.assertFalse(q.contains("includePotions="));

	}



	@Test
	public void paletteLookupUrlIncludesUsableByAndGroupFilters()
	{
		TestColorLockConfig cfg = new TestColorLockConfig();
		cfg.potions = true;
		String url = ColorLockItemsApi.buildPaletteLookupItemsUrl(
			"https://group.thegrandchart.com/api/v1/items", cfg, ColorLockColor.RED);
		Assert.assertTrue(url.contains("groupFilters=1"));
		Assert.assertTrue(url.contains("usableBy=red"));
	}

	@Test
	public void syncedApplyStripsCamelCasePinnedParams()
	{
		TestColorLockConfig cfg = new TestColorLockConfig();
		cfg.sync = true;
		String hub = "https://x/api/v1/items?mode=color-lock&colored=1&groupFilters=1"
			+ "&includePotions=0&excludeFood=0&includeAmmunition=0";
		String out = ColorLockItemsApi.applyPluginItemsQuery(hub, cfg);
		Assert.assertFalse("includePotions should be stripped when synced",
			out.contains("includePotions="));
		Assert.assertFalse("excludeFood should be stripped when synced",
			out.contains("excludeFood="));
		Assert.assertFalse("includeAmmunition should be stripped when synced",
			out.contains("includeAmmunition="));
		Assert.assertTrue(out.contains("groupFilters=1"));
		Assert.assertTrue(out.contains("mode=color-lock"));
		Assert.assertTrue(out.contains("colored=1"));
	}

}

