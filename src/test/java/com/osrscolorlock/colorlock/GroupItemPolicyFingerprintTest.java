package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class GroupItemPolicyFingerprintTest
{
	@Test
	public void fingerprintTracksAmmunitionToggle()
	{
		ColorLockGroupSync.GroupDto g = new ColorLockGroupSync.GroupDto();
		Assert.assertEquals(0, ColorLockGroupSync.groupItemPolicyFingerprint(g));
		g.colorLockIncludeAmmunition = true;
		Assert.assertEquals(4, ColorLockGroupSync.groupItemPolicyFingerprint(g));
	}
}
