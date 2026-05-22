package com.osrscolorlock.colorlock;

/** Parsed hub credentials from plugin config (access code or legacy slug + member). */
final class ColorLockCredentials
{
	final String groupField;
	final String memberField;
	final String accessCode;
	final String pathSlug;

	private ColorLockCredentials(String groupField, String memberField, String accessCode, String pathSlug)
	{
		this.groupField = groupField;
		this.memberField = memberField;
		this.accessCode = accessCode;
		this.pathSlug = pathSlug;
	}

	static ColorLockCredentials from(ColorLockConfig config)
	{
		if (config == null)
		{
			return new ColorLockCredentials("", "", null, "");
		}
		String group = config.groupSlug() == null ? "" : config.groupSlug().trim();
		String member = config.memberPublicCode() == null ? "" : config.memberPublicCode().trim();

		if (ColorLockAuthBodies.isValidPluginAccessCode(group))
		{
			return new ColorLockCredentials(group, member, group, slugFromAccessCode(group));
		}
		if (ColorLockAuthBodies.isValidPluginAccessCode(member))
		{
			return new ColorLockCredentials(group, member, member, slugFromAccessCode(member));
		}
		String combined = ColorLockAuthBodies.formatAccessCode(group, member);
		if (ColorLockAuthBodies.isValidPluginAccessCode(combined))
		{
			return new ColorLockCredentials(group, member, combined, group);
		}
		return new ColorLockCredentials(group, member, null, group);
	}

	boolean isFilled()
	{
		if (accessCode != null)
		{
			return true;
		}
		return !groupField.isEmpty() && !memberField.isEmpty();
	}

	boolean usesAccessCodeOnly()
	{
		return accessCode != null && memberField.isEmpty();
	}

	static String slugFromAccessCode(String accessCode)
	{
		if (accessCode == null)
		{
			return "";
		}
		int hash = accessCode.indexOf('#');
		return hash > 0 ? accessCode.substring(0, hash) : accessCode.trim();
	}
}
