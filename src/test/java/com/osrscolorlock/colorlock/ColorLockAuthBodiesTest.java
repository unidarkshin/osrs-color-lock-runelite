package com.osrscolorlock.colorlock;

import org.junit.Assert;
import org.junit.Test;

public class ColorLockAuthBodiesTest
{
	@Test
	public void prefersAccessCodeWhenSlugAndMemberPresent()
	{
		String json = ColorLockAuthBodies.buildPluginAuthJson("FoxMango42", "#42", "");
		Assert.assertTrue(json.contains("\"accessCode\":\"FoxMango42#0042\""));
		Assert.assertFalse(json.contains("\"slug\""));
	}

	@Test
	public void legacyMemberCodeUsesSlugAndPublicCodeNotAccessCode()
	{
		String json = ColorLockAuthBodies.buildPluginAuthJson("GeckoMango45", "Frog12", "");
		Assert.assertTrue(json.contains("\"publicCode\":\"Frog12\""));
		Assert.assertTrue(json.contains("\"slug\":\"GeckoMango45\""));
		Assert.assertFalse(json.contains("accessCode"));
	}

	@Test
	public void resolveLegacyUsesPublicCodeOnly()
	{
		String json = ColorLockAuthBodies.buildPluginResolveJson("GeckoMango45", "Frog12", "");
		Assert.assertTrue(json.contains("\"publicCode\":\"Frog12\""));
		Assert.assertFalse(json.contains("\"slug\""));
	}

	@Test
	public void acceptsCombinedCodeInGroupField()
	{
		String json = ColorLockAuthBodies.buildPluginAuthJson("FoxMango42#0042", "", "");
		Assert.assertTrue(json.contains("\"accessCode\":\"FoxMango42#0042\""));
	}

	@Test
	public void legacyInviteUrlStillWorks()
	{
		String json = ColorLockAuthBodies.buildPluginAuthJson(
			"https://group.thegrandchart.com/g/FoxMango42", "#0042", "secret");
		Assert.assertTrue(json.contains("\"inviteUrl\""));
		Assert.assertTrue(json.contains("\"publicCode\""));
	}
}
