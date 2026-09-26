package com.runelitemc.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.mcp.McpServer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The release version has to reach the wire, not just the tag.
 *
 * A client compares the MCP {@code serverInfo.version} it is talking to against
 * the release it expects, and prompts for a restart when they differ. A jar that
 * reports a stale or hardcoded version therefore asks the user to restart
 * forever, however many times they update — which is exactly what a literal
 * {@code "0.1.0"} in the plugin source used to do.
 *
 * So the chain is pinned here: the version declared in plugin/build.gradle (the
 * one semantic-release writes before this build runs, and tags as v&lt;version&gt;)
 * must be the version {@link BuildInfo} reports, which is what the plugin hands
 * to {@code McpServer} as serverInfo.version and to client_status as "version".
 */
public class BuildInfoTest
{
	/** Set by the test task in build.gradle, straight from project.version. */
	private static final String DECLARED = System.getProperty("declaredVersion");

	@Test
	public void theBuildStampCarriesTheDeclaredReleaseVersion()
	{
		assertTrue("build.gradle did not pass declaredVersion to the test task",
			DECLARED != null && !DECLARED.isEmpty());
		assertEquals("BuildInfo.version() must be the version declared in plugin/build.gradle,"
			+ " or the client's restart prompt never clears", DECLARED, BuildInfo.version());
	}

	/**
	 * The fallback exists for an IDE run with no processed resources. Reaching it
	 * in a Gradle build means the stamp is not being written at all.
	 */
	@Test
	public void theVersionIsStampedRatherThanFallingBack()
	{
		assertTrue("version fell back to the unstamped default: is processResources still"
			+ " templating build-info.properties?", !BuildInfo.version().endsWith("-dev"));
	}

	/**
	 * The one that actually matters: what an MCP client reads at initialize. A
	 * literal here (as there once was) survives every version bump, so assert the
	 * wire value rather than trusting that the plumbing is still connected.
	 */
	@Test
	public void theMcpHandshakeReportsTheReleaseVersion()
	{
		// Providers are only captured by tool handler lambdas, and initialize
		// invokes no tool, so the handshake needs none of them.
		McpServer mcp = new McpServerPlugin().buildMcpServer(null, null, null, null, null);
		JsonObject response = mcp.handle(new Gson().fromJson(
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":\"2025-06-18\"}}", JsonElement.class))
			.getAsJsonObject();

		JsonObject serverInfo = response.getAsJsonObject("result").getAsJsonObject("serverInfo");
		assertEquals("serverInfo.name must be the published plugin name",
			"runelite-mcp-server", serverInfo.get("name").getAsString());
		assertEquals("serverInfo.version must be the release version, not a literal:"
			+ " a client that compares it against the release it expects would prompt for a"
			+ " restart forever", DECLARED, serverInfo.get("version").getAsString());
	}

	@Test
	public void theBuildStampIdentifiesTheCommit()
	{
		// "dev" is legitimate off a checkout with no git; a blank never is.
		assertTrue("empty build stamp", BuildInfo.build() != null && !BuildInfo.build().isEmpty());
		assertTrue("empty builtAt stamp", BuildInfo.builtAt() != null && !BuildInfo.builtAt().isEmpty());
	}
}
