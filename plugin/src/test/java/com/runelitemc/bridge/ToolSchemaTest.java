package com.runelitemc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.mcp.McpTool;
import com.runelitemc.bridge.mcp.McpToolCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * The tool contracts in /mcp/tools are the source of truth for what this plugin
 * serves, so a typo in one is a broken contract, not a cosmetic slip. These
 * checks are structural — a full JSON Schema validation run against live
 * payloads is documented in the README.
 */
public class ToolSchemaTest
{
	private static final List<String> TOOLS = Arrays.asList(
		"client_status", "game_state", "combat_achievements", "collection_log", "bank_snapshot", "find_item");

	private static final McpTool.Handler NOOP = args -> new JsonObject();

	@Test
	public void everyToolLoadsAndDescribesItself()
	{
		for (String name : TOOLS)
		{
			JsonObject described = McpToolCatalog.load(name, NOOP).describe();
			assertEquals(name, described.get("name").getAsString());
			assertTrue(name, described.get("description").getAsString().length() > 40);
			assertEquals(name, "object", described.getAsJsonObject("inputSchema").get("type").getAsString());
			assertEquals(name, "object", described.getAsJsonObject("outputSchema").get("type").getAsString());
		}
	}

	@Test
	public void everyRefResolvesWithinItsOwnDocument()
	{
		for (String name : TOOLS)
		{
			JsonObject schema = McpToolCatalog.load(name, NOOP).describe().getAsJsonObject("outputSchema");
			JsonObject defs = schema.has("$defs") ? schema.getAsJsonObject("$defs") : new JsonObject();
			for (String ref : refs(schema))
			{
				if (!ref.startsWith("#/$defs/"))
				{
					fail(name + ": only local $defs refs are supported, found " + ref);
				}
				String key = ref.substring("#/$defs/".length());
				assertTrue(name + ": unresolved $ref " + ref, defs.has(key));
			}
		}
	}

	@Test
	public void requiredFieldsAreDeclaredProperties()
	{
		for (String name : TOOLS)
		{
			JsonObject schema = McpToolCatalog.load(name, NOOP).describe().getAsJsonObject("outputSchema");
			checkRequired(name, schema);
		}
	}

	/** The game_state section filter and its schema enum must not drift apart. */
	@Test
	public void gameStateSectionsMatchTheSnapshot()
	{
		JsonArray allowed = McpToolCatalog.load("game_state", NOOP).describe()
			.getAsJsonObject("inputSchema")
			.getAsJsonObject("properties")
			.getAsJsonObject("sections")
			.getAsJsonObject("items")
			.getAsJsonArray("enum");

		List<String> fromSchema = new ArrayList<>();
		for (JsonElement e : allowed)
		{
			fromSchema.add(e.getAsString());
		}
		assertEquals(GielinorCompanionPlugin.SNAPSHOT_SECTIONS, fromSchema);

		// Each section also needs somewhere to land in the output schema.
		JsonObject properties = McpToolCatalog.load("game_state", NOOP).describe()
			.getAsJsonObject("outputSchema").getAsJsonObject("properties");
		for (String section : fromSchema)
		{
			assertTrue("game_state outputSchema has no " + section, properties.has(section));
		}
	}

	private static void checkRequired(String tool, JsonObject schema)
	{
		if (schema.has("required") && schema.has("properties"))
		{
			JsonObject properties = schema.getAsJsonObject("properties");
			for (JsonElement required : schema.getAsJsonArray("required"))
			{
				assertTrue(tool + ": required '" + required.getAsString() + "' is not a declared property",
					properties.has(required.getAsString()));
			}
		}

		for (String key : schema.keySet())
		{
			JsonElement value = schema.get(key);
			if (value.isJsonObject())
			{
				checkRequired(tool, value.getAsJsonObject());
			}
		}
	}

	private static List<String> refs(JsonElement element)
	{
		List<String> found = new ArrayList<>();
		if (element.isJsonObject())
		{
			JsonObject object = element.getAsJsonObject();
			for (String key : object.keySet())
			{
				if ("$ref".equals(key))
				{
					found.add(object.get(key).getAsString());
				}
				else
				{
					found.addAll(refs(object.get(key)));
				}
			}
		}
		else if (element.isJsonArray())
		{
			for (JsonElement entry : element.getAsJsonArray())
			{
				found.addAll(refs(entry));
			}
		}
		return found;
	}

	/**
	 * No "format" keyword in any schema we publish. It buys nothing here — every
	 * timestamp is already documented as an ISO-8601 string — and it costs real
	 * clients: a consumer compiling these schemas through a validator that maps
	 * format: "date-time" onto a native date type rejects every successful
	 * response, because a JSON string can never satisfy it. That blocked the
	 * Gielinor client on all four tools that carry a capturedAt.
	 */
	@Test
	public void noSchemaDeclaresAFormatKeyword()
	{
		for (String name : TOOLS)
		{
			JsonObject described = McpToolCatalog.load(name, NOOP).describe();
			for (String key : new String[]{"inputSchema", "outputSchema"})
			{
				if (described.has(key))
				{
					List<String> found = new ArrayList<>();
					collectFormats(described.get(key), key, found);
					assertTrue(name + " declares format at " + found, found.isEmpty());
				}
			}
		}
	}

	private static void collectFormats(JsonElement element, String path, List<String> found)
	{
		if (element.isJsonObject())
		{
			JsonObject o = element.getAsJsonObject();
			if (o.has("format"))
			{
				found.add(path + " (" + o.get("format") + ")");
			}
			for (String key : o.keySet())
			{
				collectFormats(o.get(key), path + "." + key, found);
			}
		}
		else if (element.isJsonArray())
		{
			JsonArray array = element.getAsJsonArray();
			for (int i = 0; i < array.size(); i++)
			{
				collectFormats(array.get(i), path + "[" + i + "]", found);
			}
		}
	}

	/**
	 * A sideloaded jar is whatever sits in the sideload directory, and "version"
	 * is a constant that does not move between builds — so client_status has to
	 * carry a build stamp, or the only way to tell a current jar from a stale one
	 * is to diff the schemas it serves.
	 */
	@Test
	public void clientStatusIdentifiesTheRunningBuild()
	{
		JsonObject out = McpToolCatalog.load("client_status", NOOP).describe()
			.getAsJsonObject("outputSchema");
		JsonObject properties = out.getAsJsonObject("properties");

		List<String> required = new ArrayList<>();
		for (JsonElement e : out.getAsJsonArray("required"))
		{
			required.add(e.getAsString());
		}

		for (String field : new String[]{"build", "builtAt"})
		{
			assertTrue("client_status must declare " + field, properties.has(field));
			assertTrue("client_status must require " + field, required.contains(field));
			assertEquals(field + " must be a plain string", "string",
				properties.getAsJsonObject(field).get("type").getAsString());
		}

		// Whatever the build wrote, the values must be present and non-empty —
		// an unstamped build degrades to "dev" rather than to null.
		assertTrue("build is empty", !BuildInfo.build().isEmpty());
		assertTrue("builtAt is empty", !BuildInfo.builtAt().isEmpty());
	}
}
