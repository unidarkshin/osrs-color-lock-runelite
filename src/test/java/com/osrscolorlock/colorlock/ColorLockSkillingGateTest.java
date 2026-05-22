package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockSkillingGateTest
{
	@Test
	public void verbsRequirePickaxeOrAxe()
	{
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.PICKAXE,
			ColorLockSkillingGate.requiredToolForVerb("Mine"));
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.AXE,
			ColorLockSkillingGate.requiredToolForVerb("Chop down"));
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.AXE,
			ColorLockSkillingGate.requiredToolForVerb("Cut"));
		Assert.assertNull(ColorLockSkillingGate.requiredToolForVerb("Talk-to"));
	}

	@Test
	public void classifyPickaxeBeforeAxeSubstring()
	{
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.PICKAXE,
			ColorLockSkillingGate.classifyToolFromName("Rune pickaxe"));
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.AXE,
			ColorLockSkillingGate.classifyToolFromName("Dragon axe"));
		Assert.assertEquals(ColorLockSkillingGate.SkillingToolKind.OTHER,
			ColorLockSkillingGate.classifyToolFromName("Rune scimitar"));
	}

}
