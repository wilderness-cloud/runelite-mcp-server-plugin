package com.runelitemc.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.Varbits;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.UsernameChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import com.runelitemc.bridge.mcp.McpServer;
import com.runelitemc.bridge.mcp.McpToolCatalog;
import com.runelitemc.bridge.providers.ActivitiesProvider;
import com.runelitemc.bridge.providers.BossKcProvider;
import com.runelitemc.bridge.providers.ChargesProvider;
import com.runelitemc.bridge.providers.CollectionLogProvider;
import com.runelitemc.bridge.providers.CombatAchievementsProvider;
import com.runelitemc.bridge.providers.FarmingData;
import com.runelitemc.bridge.providers.FarmingProvider;
import com.runelitemc.bridge.providers.ItemStateProvider;
import com.runelitemc.bridge.providers.PlayerStateProvider;
import com.runelitemc.bridge.providers.ProgressProvider;
import com.runelitemc.bridge.providers.SlayerProvider;

@Slf4j
@PluginDescriptor(
	name = "RuneLite MCP Server",
	description = "Serves your live account state to an MCP client over localhost. Read-only.",
	tags = {"mcp", "llm", "ai", "assistant", "agent"}
)
public class McpServerPlugin extends Plugin
{
	static final String CONFIG_GROUP = "runelitemcpserver";

	/**
	 * Top-level keys of {@link SnapshotService#snapshot()}. The authoritative list
	 * is the sections enum in /mcp/tools/game_state.json; this copy only builds the
	 * error text when a client names something else.
	 */
	static final List<String> SNAPSHOT_SECTIONS = Arrays.asList(
		"state", "quests", "diaries", "combatAchievements", "slayer",
		"bossKc", "inventory", "equipment", "bank", "collectionLog", "activities", "charges");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private McpServerConfig config;

	@Inject
	private ConfigManager configManager;

	private final RuntimeState runtimeState = new RuntimeState();
	private final CollectionLogCapture collectionLogCapture = new CollectionLogCapture();
	private final GrandExchangeTracker grandExchange = new GrandExchangeTracker();
	private BridgeHttpServer server;

	@com.google.inject.Provides
	McpServerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(McpServerConfig.class);
	}

	@Override
	protected void startUp()
	{
		startServer();
	}

	@Override
	protected void shutDown()
	{
		stopServer();
	}

	private synchronized void startServer()
	{
		if (server != null)
		{
			return;
		}

		PlayerStateProvider playerState = new PlayerStateProvider(client);
		ItemStateProvider itemState = new ItemStateProvider(client, runtimeState);
		ProgressProvider progress = new ProgressProvider(client);
		CombatAchievementsProvider combatAchievements = new CombatAchievementsProvider(client);
		SlayerProvider slayer = new SlayerProvider(client);
		BossKcProvider bossKc = new BossKcProvider(client);
		CollectionLogProvider collectionLog = new CollectionLogProvider(client, collectionLogCapture);
		FarmingData farmingData = FarmingData.load();
		FarmingProvider farming = new FarmingProvider(client, configManager, farmingData);
		ActivitiesProvider activities = new ActivitiesProvider(client, farmingData);
		ChargesProvider charges = new ChargesProvider(client, configManager);
		SnapshotService snapshots = new SnapshotService(playerState, itemState, progress, combatAchievements,
			slayer, bossKc, collectionLog, activities, charges);

		Map<String, BridgeHttpServer.Responder> routes = new HashMap<>();
		routes.put("/health", this::health);
		routes.put("/state", () -> onClientThread(playerState::state));
		routes.put("/quests", () -> onClientThread(progress::quests));
		routes.put("/diaries", () -> onClientThread(progress::diaries));
		routes.put("/combat-achievements", () -> onClientThread(combatAchievements::combatAchievements));
		routes.put("/slayer", () -> onClientThread(slayer::slayer));
		routes.put("/kc", () -> onClientThread(bossKc::bossKc));
		routes.put("/inventory", () -> onClientThread(itemState::inventory));
		routes.put("/equipment", () -> onClientThread(itemState::equipment));
		routes.put("/bank", () -> onClientThread(itemState::bank));
		routes.put("/collection-log", () -> onClientThread(collectionLog::collectionLog));
		routes.put("/grand-exchange", () -> onClientThread(
			() -> grandExchange.grandExchange(client.getGrandExchangeOffers(), this::itemName)));
		routes.put("/activities", () -> onClientThread(activities::activities));
		routes.put("/charges", () -> onClientThread(() -> charges.charges(false)));
		routes.put("/farming", () -> onClientThread(() -> farming.farming(null, null, false)));
		routes.put("/birdhouses", () -> onClientThread(farming::birdHouses));
		routes.put("/snapshot", () -> onClientThread(snapshots::snapshot));

		McpServer mcp = buildMcpServer(itemState, combatAchievements, collectionLog, farming, snapshots);

		try
		{
			server = BridgeHttpServer.start(config.port(), routes, mcp);
			log.info("MCP bridge listening on http://127.0.0.1:{} (MCP endpoint {})",
				config.port(), BridgeHttpServer.MCP_PATH);
		}
		catch (IOException e)
		{
			log.error("Failed to start MCP bridge server on port {}", config.port(), e);
		}
	}

	private synchronized void stopServer()
	{
		if (server != null)
		{
			server.stop();
			server = null;
			log.info("MCP bridge server stopped");
		}
	}

	/**
	 * The MCP tool surface: live account state only. Reference data (wiki quest
	 * requirements, GE prices, Wise Old Man history) is the client app's job —
	 * it can update that without anyone reinstalling a plugin.
	 *
	 * <p>Package-private rather than private so {@link BuildInfoTest} can check
	 * what this reports as serverInfo.version: a client compares that against the
	 * release it expects, so a hardcoded copy here would prompt for a restart
	 * that no update ever satisfies.
	 */
	McpServer buildMcpServer(ItemStateProvider itemState, CombatAchievementsProvider combatAchievements,
		CollectionLogProvider collectionLog, FarmingProvider farming, SnapshotService snapshots)
	{
		return new McpServer("runelite-mcp-server", BuildInfo.version(),
			"Live Old School RuneScape account state read from the player's running RuneLite client. "
				+ "Read-only: this cannot click, move, or change anything in game. "
				+ "Start with game_state; it covers skills, quests, diaries, slayer, boss KC, and carried items in one call.")
			.register(McpToolCatalog.load("client_status", args -> health()))
			.register(McpToolCatalog.load("game_state",
				args -> filterSections(onClientThread(snapshots::snapshot), args)))
			.register(McpToolCatalog.load("combat_achievements",
				args -> onClientThread(() -> combatAchievements.combatAchievements(
					str(args, "tier"), bool(args, "completed"), str(args, "search")))))
			.register(McpToolCatalog.load("collection_log",
				args -> onClientThread(() -> collectionLog.collectionLog(
					str(args, "page"), str(args, "search"), bool(args, "obtained")))))
			.register(McpToolCatalog.load("bank_snapshot", args -> onClientThread(itemState::bank)))
			.register(McpToolCatalog.load("find_item",
				args -> onClientThread(() -> itemState.findItem(str(args, "query"), containers(args)))))
			.register(McpToolCatalog.load("grand_exchange",
				args -> onClientThread(() -> grandExchange.grandExchange(
					client.getGrandExchangeOffers(), this::itemName))))
			.register(McpToolCatalog.load("farming_state",
				args -> onClientThread(() -> farming.farming(
					strings(args, "tabs"), strings(args, "states"), Boolean.TRUE.equals(bool(args, "includeBare"))))));
	}

	private static String str(JsonObject args, String key)
	{
		JsonElement e = args.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive())
		{
			return null;
		}
		String v = e.getAsString().trim();
		return v.isEmpty() ? null : v;
	}

	/** A string-array argument, or an empty set when absent. */
	private static Set<String> strings(JsonObject args, String key)
	{
		Set<String> out = new LinkedHashSet<>();
		JsonElement e = args.get(key);
		if (e != null && e.isJsonArray())
		{
			for (JsonElement entry : e.getAsJsonArray())
			{
				if (entry.isJsonPrimitive())
				{
					out.add(entry.getAsString());
				}
			}
		}
		return out;
	}

	private static Boolean bool(JsonObject args, String key)
	{
		JsonElement e = args.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive()
			? null
			: Boolean.valueOf(e.getAsBoolean());
	}

	/** Containers find_item should search; all three unless narrowed. */
	private static Set<String> containers(JsonObject args)
	{
		Set<String> out = new LinkedHashSet<>();
		JsonElement e = args.get("containers");
		if (e != null && e.isJsonArray() && e.getAsJsonArray().size() > 0)
		{
			for (JsonElement entry : e.getAsJsonArray())
			{
				if (entry.isJsonPrimitive())
				{
					out.add(entry.getAsString());
				}
			}
			return out;
		}
		out.add("bank");
		out.add("inventory");
		out.add("equipment");
		return out;
	}

	/** Trims the snapshot to the requested sections; capturedAt always survives. */
	private JsonObject filterSections(JsonObject snapshot, JsonObject args)
	{
		JsonElement sections = args.get("sections");
		if (sections == null || !sections.isJsonArray() || sections.getAsJsonArray().size() == 0)
		{
			return snapshot;
		}

		JsonObject out = new JsonObject();
		out.add("capturedAt", snapshot.get("capturedAt"));
		List<String> ignored = new ArrayList<>();
		for (JsonElement section : sections.getAsJsonArray())
		{
			String key = section.getAsString();
			if (snapshot.has(key))
			{
				out.add(key, snapshot.get(key));
			}
			else
			{
				ignored.add(key);
			}
		}

		if (!ignored.isEmpty())
		{
			out.addProperty("ignoredSections", String.join(", ", ignored)
				+ " (valid: " + String.join(", ", SNAPSHOT_SECTIONS) + ")");
		}
		return out;
	}

	/**
	 * Hops onto the client thread (the RuneLite Client is not thread-safe) and
	 * blocks the HTTP worker for at most 10s. invoke() runs immediately when
	 * the caller is already on the client thread.
	 */
	private <T> T onClientThread(Supplier<T> fn)
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				future.complete(fn.get());
			}
			catch (Throwable t)
			{
				future.completeExceptionally(t);
			}
			return true;
		});

		try
		{
			return future.get(10, TimeUnit.SECONDS);
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			throw new RuntimeException(cause);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted while waiting for client thread", e);
		}
		catch (TimeoutException e)
		{
			throw new RuntimeException("timeout waiting for client thread (client paused or at login screen?)", e);
		}
	}

	private JsonObject health()
	{
		JsonObject o = new JsonObject();
		o.addProperty("status", "ok");
		o.addProperty("service", "runelite-mcp-server");
		o.addProperty("version", BuildInfo.version());
		// Which jar is actually running. The version string does not move
		// between builds, so it cannot answer that on its own.
		o.addProperty("build", BuildInfo.build());
		o.addProperty("builtAt", BuildInfo.builtAt());
		o.addProperty("port", config.port());
		o.addProperty("gameState", runtimeState.getGameState());
		o.addProperty("loggedIn", GameState.LOGGED_IN.name().equals(runtimeState.getGameState()));

		String username = runtimeState.getUsername();
		if (username != null && !username.isEmpty())
		{
			o.addProperty("username", username);
		}

		long lastTick = runtimeState.getLastTickMs();
		o.addProperty("msSinceLastTick", lastTick <= 0 ? -1 : System.currentTimeMillis() - lastTick);
		return o;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		runtimeState.setGameState(event.getGameState().name());
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Never serve one account's snapshots to another session. Only a
			// return to the login screen ends a session: LOADING fires on every
			// teleport, staircase and world hop, and clearing on it threw away
			// the bank snapshot (which only exists client-side once the bank has
			// been opened) several times an hour.
			runtimeState.clearBank();
			collectionLogCapture.clear();
			grandExchange.clear();
		}
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			String username = client.getUsername();
			if (username != null && !username.isEmpty())
			{
				runtimeState.setUsername(username);
			}
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		grandExchange.observe(event.getSlot(), event.getOffer());
	}

	/** Item name for a numeric id, empty when the cache cannot answer. */
	private String itemName(int id)
	{
		try
		{
			net.runelite.api.ItemComposition comp = client.getItemDefinition(id);
			return comp == null ? null : comp.getName();
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged event)
	{
		String username = client.getUsername();
		if (username != null && !username.isEmpty())
		{
			runtimeState.setUsername(username);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		runtimeState.setLastTickMs(System.currentTimeMillis());

		// Jagex-account logins leave client.getUsername() (the login form field)
		// empty; the authoritative name is the local player, which can spawn a
		// tick or two after GameStateChanged(LOGGED_IN)
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			String name = player.getName();
			if (name != null && !name.isEmpty())
			{
				runtimeState.setUsername(name);
			}
		}
	}

	private static final int[] BANK_TAB_COUNT_VARBITS = {
		Varbits.BANK_TAB_ONE_COUNT, Varbits.BANK_TAB_TWO_COUNT, Varbits.BANK_TAB_THREE_COUNT,
		Varbits.BANK_TAB_FOUR_COUNT, Varbits.BANK_TAB_FIVE_COUNT, Varbits.BANK_TAB_SIX_COUNT,
		Varbits.BANK_TAB_SEVEN_COUNT, Varbits.BANK_TAB_EIGHT_COUNT, Varbits.BANK_TAB_NINE_COUNT,
	};

	private int[] bankTabCounts()
	{
		int[] counts = new int[BANK_TAB_COUNT_VARBITS.length];
		for (int i = 0; i < counts.length; i++)
		{
			counts[i] = client.getVarbitValue(BANK_TAB_COUNT_VARBITS[i]);
		}
		return counts;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			// Read alongside the items, on the client thread: tab ranges are
			// positional, so counts from a later moment can describe a layout
			// the captured array no longer has.
			runtimeState.updateBank(event.getItemContainer(), bankTabCounts(),
				client.getVarbitValue(Varbits.CURRENT_BANK_TAB),
				client.getVarbitValue(Varbits.BANK_LEAVEPLACEHOLDERS) == 1);
		}
	}

	/**
	 * When a collection log page is drawn, snapshot its items: the game only
	 * materializes per-item obtained state (widget opacity) for the visible
	 * page, so captured coverage grows as the player browses their log.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}
		clientThread.invokeLater(this::captureCollectionLogPage);
	}

	private boolean captureCollectionLogPage()
	{
		try
		{
			Widget header = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_HEADER);
			Widget itemsContainer = client.getWidget(ComponentID.COLLECTION_LOG_ENTRY_ITEMS);
			if (header == null || itemsContainer == null)
			{
				return false;
			}

			String pageName = Text.removeTags(header.getText()).trim();
			if (pageName.isEmpty())
			{
				return false;
			}

			List<CollectionLogCapture.CapturedItem> items = new ArrayList<>();
			for (Widget widgetItem : itemsContainer.getDynamicChildren())
			{
				if (widgetItem == null || widgetItem.getItemId() <= 0)
				{
					continue;
				}
				boolean obtained = widgetItem.getOpacity() == 0;
				items.add(new CollectionLogCapture.CapturedItem(
					widgetItem.getItemId(),
					obtained ? widgetItem.getItemQuantity() : 0,
					obtained));
			}

			if (!items.isEmpty())
			{
				collectionLogCapture.record(pageName, items);
			}
		}
		catch (Exception e)
		{
			log.debug("Collection log page capture failed", e);
		}
		return false;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("port".equals(event.getKey()))
		{
			stopServer();
			startServer();
		}
	}
}
