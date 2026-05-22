package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockGroupSyncItemsUrlTest
{
	private static final String BASE = "https://group.thegrandchart.com";

	@Test
	public void rejectsLoopbackItemsUrl()
	{
		Assert.assertNull(ColorLockGroupSync.normalizeItemsUrl(
			"https://0.0.0.0:3000/api/v1/items?mode=color-lock", BASE));
		Assert.assertNull(ColorLockGroupSync.normalizeItemsUrl(
			"https://127.0.0.1:3000/api/v1/items", BASE));
	}

	@Test
	public void acceptsPublicItemsUrl()
	{
		String url = ColorLockGroupSync.normalizeItemsUrl(
			"https://group.thegrandchart.com/api/v1/items?colored=1", BASE);
		Assert.assertNotNull(url);
		Assert.assertTrue(url.contains("thegrandchart.com"));
	}
}
