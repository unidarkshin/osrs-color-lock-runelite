package com.osrscolorlock.colorlock;

/** Minimal {@link ColorLockConfig} for unit tests. */
final class TestColorLockConfig implements ColorLockConfig
{
	boolean sync;
	boolean potions;
	boolean includeFood = true;
	boolean ammo;
	String group = "";
	String member = "";

	@Override
	public String groupSlug()
	{
		return group;
	}

	@Override
	public String groupJoinPasscode()
	{
		return "";
	}

	@Override
	public String memberPublicCode()
	{
		return member;
	}

	@Override
	public boolean hubGroupSyncEnabled()
	{
		return sync;
	}

	@Override
	public boolean manualLockPotionsToColors()
	{
		return potions;
	}

	@Override
	public boolean manualIncludeFood()
	{
		return includeFood;
	}

	@Override
	public boolean manualLockAmmunitionToColors()
	{
		return ammo;
	}

	@Override
	public ColorLockColor assignedColor()
	{
		return ColorLockColor.RED;
	}

	@Override
	public boolean showColorOverlay()
	{
		return true;
	}
}
