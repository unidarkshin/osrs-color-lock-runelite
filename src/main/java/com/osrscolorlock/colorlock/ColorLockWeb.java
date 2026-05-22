package com.osrscolorlock.colorlock;

/** Canonical group app URLs. Kept in sync with the deployed Color Lock Hub. */
public final class ColorLockWeb
{
	public static final String HUB = "https://group.thegrandchart.com/";
	public static final String ITEMS_PAGE = "https://group.thegrandchart.com/items";
	/** Typical static manifest path. Used only to derive the hub origin. Live pulls go through {@code /api/v1/items}. */
	public static final String DEFAULT_ITEMS_JSON = "https://group.thegrandchart.com/data/items.json";

	public static final String API_PLUGIN_AUTH = "/api/plugin/v1/auth";
	public static final String API_PLUGIN_STATE = "/api/plugin/v1/state";
	public static final String API_PLUGIN_ME = "/api/plugin/v1/me";
	/** Versioned items endpoint (contract {@link ColorLockApiContracts#EXPECTED_ITEMS_API_CONTRACT_VERSION}). */
	public static final String API_V1_ITEMS = "/api/v1/items";
	/** Deprecated unversioned alias — same query params and payload as {@link #API_V1_ITEMS}. */
	public static final String API_ITEMS_LEGACY = "/api/items";
	/** Rule catalog (UI/docs); plugin uses per-row {@code colorLockApplies} from items instead. */
	public static final String API_V1_COLOR_LOCK_RULES = "/api/v1/color-lock/rules";
	/** OpenAPI YAML for this deployment (optional discovery). */
	public static final String API_OPENAPI = "/api/openapi";
	/** Stateless member resolve when {@link #API_PLUGIN_AUTH} returns 404 (JWT-less; hub may lag on auth rollout). */
	public static final String API_PLUGIN_RESOLVE_V1 = "/api/plugin/v1/resolve/";

	private ColorLockWeb()
	{
	}
}
