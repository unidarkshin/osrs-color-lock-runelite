package com.osrscolorlock.colorlock;

/**
 * Mirrors hub {@code src/lib/apiContractVersions.ts} and repo-root {@code openapi.yml}.
 * Log mismatches; bump when OpenAPI contract integers change.
 */
public final class ColorLockApiContracts
{
	public static final String HEADER_API_CONTRACT = "X-OCL-API-Contract-Version";
	public static final String HEADER_ITEMS_SCHEMA = "X-OCL-Items-Schema-Version";

	/** {@code GET /api/v1/items} handler contract (not per-row usability math). */
	public static final int EXPECTED_ITEMS_API_CONTRACT_VERSION = 10;

	/** Per-row {@code usableColors} semantics in item payloads. */
	public static final int EXPECTED_ITEMS_JSON_SCHEMA_VERSION = 88;

	/** {@code PATCH /api/plugin/v1/me} request/response shape. */
	public static final int EXPECTED_PLUGIN_ME_API_CONTRACT_VERSION = 8;

	private ColorLockApiContracts()
	{
	}
}
