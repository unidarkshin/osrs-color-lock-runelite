package com.osrscolorlock.colorlock;

import java.util.regex.Pattern;

/** Builds JSON bodies for {@code POST /api/plugin/v1/auth} and resolve (see hub {@code openapi.yml}). */
final class ColorLockAuthBodies
{
	/** Hub {@code parsePluginAccessCode} — only `{slug}#{0000}` counts as {@code accessCode}. */
	private static final Pattern PLUGIN_ACCESS_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9]*#\\d{1,4}$");

	private ColorLockAuthBodies()
	{
	}

	static String buildPluginAuthJson(String slugOrUrl, String publicCode, String joinPasscode)
	{
		String slugField = slugOrUrl == null ? "" : slugOrUrl.trim();
		String codeField = publicCode == null ? "" : publicCode.trim();

		if (isValidPluginAccessCode(slugField))
		{
			return jsonWithAccessCode(slugField, joinPasscode);
		}
		if (!slugField.isEmpty() && !codeField.isEmpty()
			&& !slugField.startsWith("http://") && !slugField.startsWith("https://") && !slugField.contains("/g/"))
		{
			String accessCode = formatAccessCode(slugField, codeField);
			if (isValidPluginAccessCode(accessCode))
			{
				return jsonWithAccessCode(accessCode, joinPasscode);
			}
		}
		return jsonLegacy(slugField, codeField, joinPasscode);
	}

	/** Resolve slug is in the URL path; body is {@code accessCode} or legacy {@code publicCode} only. */
	static String buildPluginResolveJson(String pathSlug, String publicCode, String joinPasscode)
	{
		String codeField = publicCode == null ? "" : publicCode.trim();
		if (isValidPluginAccessCode(codeField))
		{
			return jsonWithAccessCode(codeField, joinPasscode);
		}
		String accessCode = formatAccessCode(pathSlug, codeField);
		if (isValidPluginAccessCode(accessCode))
		{
			return jsonWithAccessCode(accessCode, joinPasscode);
		}
		return jsonResolveLegacy(codeField, joinPasscode);
	}

	static boolean isValidPluginAccessCode(String raw)
	{
		return raw != null && PLUGIN_ACCESS_CODE.matcher(raw.trim()).matches();
	}

	private static String jsonWithAccessCode(String accessCode, String joinPasscode)
	{
		StringBuilder sb = new StringBuilder(96);
		sb.append("{\"accessCode\":\"").append(gsonEscape(accessCode.trim())).append("\"");
		appendJoinPasscode(sb, joinPasscode);
		sb.append("}");
		return sb.toString();
	}

	private static String jsonResolveLegacy(String publicCode, String joinPasscode)
	{
		StringBuilder sb = new StringBuilder(64);
		sb.append("{\"publicCode\":\"").append(gsonEscape(publicCode.trim())).append("\"");
		appendJoinPasscode(sb, joinPasscode);
		sb.append("}");
		return sb.toString();
	}

	private static String jsonLegacy(String slugOrUrl, String publicCode, String joinPasscode)
	{
		String escCode = gsonEscape(publicCode == null ? "" : publicCode.trim());
		StringBuilder sb = new StringBuilder(96);
		sb.append("{\"publicCode\":\"").append(escCode).append("\"");
		String t = slugOrUrl == null ? "" : slugOrUrl.trim();
		if (!t.isEmpty())
		{
			boolean looksLikeUrl = t.startsWith("http://") || t.startsWith("https://") || t.contains("/g/");
			String field = looksLikeUrl ? "inviteUrl" : "slug";
			sb.append(",\"").append(field).append("\":\"").append(gsonEscape(t)).append("\"");
		}
		appendJoinPasscode(sb, joinPasscode);
		sb.append("}");
		return sb.toString();
	}

	private static void appendJoinPasscode(StringBuilder sb, String joinPasscode)
	{
		if (joinPasscode != null && !joinPasscode.trim().isEmpty())
		{
			sb.append(",\"joinPasscode\":\"").append(gsonEscape(joinPasscode.trim())).append("\"");
		}
	}

	/**
	 * Hub combined credential {@code GroupSlug#0042} only when member code normalizes to {@code #dddd}.
	 * Legacy codes (e.g. {@code Frog12}) must use {@code slug} + {@code publicCode} JSON.
	 */
	static String formatAccessCode(String slug, String publicCode)
	{
		if (slug == null || publicCode == null)
		{
			return null;
		}
		String s = slug.trim();
		String c = normalizeMemberPublicCode(publicCode);
		if (s.isEmpty() || c == null || !c.startsWith("#"))
		{
			return null;
		}
		return s + c;
	}

	static String normalizeMemberPublicCode(String input)
	{
		if (input == null)
		{
			return null;
		}
		String t = input.trim();
		if (t.isEmpty())
		{
			return null;
		}
		if (t.matches("^#\\d{4}$"))
		{
			return t;
		}
		java.util.regex.Matcher digits = Pattern.compile("^#?(\\d{1,4})$").matcher(t);
		if (digits.matches())
		{
			return "#" + String.format("%04d", Integer.parseInt(digits.group(1)));
		}
		if (t.matches("^[A-Za-z]+\\d{2}$"))
		{
			return t;
		}
		return null;
	}

	static String gsonEscape(String s)
	{
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
