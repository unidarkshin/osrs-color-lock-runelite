package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class PluginQuestKeysTest
{
	@Test
	public void slugifyAndNormalizeDisplayNames()
	{
		Assert.assertEquals("sheep_herder", PluginQuestKeys.normalize("Sheep Herder"));
		Assert.assertEquals("cooks_assistant", PluginQuestKeys.normalize("Cook's Assistant"));
		Assert.assertEquals("dragon_slayer_i", PluginQuestKeys.normalize("Dragon Slayer I"));
		Assert.assertEquals("lost_city", PluginQuestKeys.normalize("lost_city"));
	}
}
