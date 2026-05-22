package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockCredentialsTest
{
	@Test
	public void accessCodeOnlyInGroupFieldIsFilled()
	{
		TestColorLockConfig cfg = new TestColorLockConfig();
		cfg.group = "GeckoGlacier38#0723";
		cfg.member = "";
		ColorLockCredentials cred = ColorLockCredentials.from(cfg);
		Assert.assertTrue(cred.isFilled());
		Assert.assertEquals("GeckoGlacier38#0723", cred.accessCode);
		Assert.assertEquals("GeckoGlacier38", cred.pathSlug);
	}

	@Test
	public void legacySlugAndMemberStillFilled()
	{
		TestColorLockConfig cfg = new TestColorLockConfig();
		cfg.group = "GeckoMango45";
		cfg.member = "Frog12";
		ColorLockCredentials cred = ColorLockCredentials.from(cfg);
		Assert.assertTrue(cred.isFilled());
		Assert.assertNull(cred.accessCode);
	}
}
