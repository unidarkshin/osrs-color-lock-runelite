package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockSitesTest
{
	@Test
	public void deriveBaseFromV1ItemsPath()
	{
		String base = ColorLockSites.deriveBaseSiteUrl("https://group.thegrandchart.com/api/v1/items?colored=1");
		Assert.assertEquals("https://group.thegrandchart.com", base);
	}
}
