package com.zachpotter.bukkit.factions.tnt;

import java.util.HashMap;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.FPlayer;
import com.massivecraft.factions.FPlayers;
import com.massivecraft.factions.Faction;
import com.massivecraft.factions.struct.Rel;

/**
 * Listens for certain world events: Block placement, explosion priming, and
 * explosions.
 * 
 * @author Zachary Potter
 */
public class TNTListener implements Listener {

	private final FactionsTNT plugin;

	private static final int STICKY_FUSE = 20; // 20 ticks = 1 second
	private static final int STICKY_TP_PERIOD = 5; // 5 ticks = .25 second

	private static final float TNT_RADIUS = 2.1f;
	private static final float TNT_STICKY_RADIUS = 1.9f;

	private static final int[][] critCoords = {	// crit hit coords
		{0,0,0}, {-1,0,0}, {0,-1,0}, {0,0,-1}, {1,0,0}, {0,1,0}, {0,0,1}};
	private static final int[][] edgeCoords = {	// edge blocks
		{0,-1,-1}, {0,-1,1}, {0,1,-1}, {0,1,1},
		{-1,0,-1}, {-1,0,1}, {1,0,-1}, {1,0,1},
		{-1,-1,0}, {1,-1,0}, {1,1,0}, {-1,1,0}};
	private static final int[][] cornerCoords={	// corner blocks
		{-1,-1,-1},{-1,-1,1},{-1,1,1},{-1,1,-1},
		{1,1,1},{1,1,-1},{1,-1,-1},{1,-1,1}};

	/** A map of 'sticky TNT' to its originally placed location. **/
	private HashMap<TNTPrimed, Location> stickyTNT;

	public TNTListener(FactionsTNT plugin) {
		this.plugin = plugin;
		stickyTNT = new HashMap<TNTPrimed, Location>();

	}

	/**
	 * Receives all player block placements. Event priority is low, therefore
	 * intercepting the event before it reaches Factions.
	 * 
	 * We check for TNT placement in foreign Faction territories and cancel
	 * the event. Then, if the placement attempt is in an ENEMY chunk, spawn
	 * a lit TNT entity.
	 */
	@EventHandler(priority = EventPriority.LOW)
	public void onBlockPlace(BlockPlaceEvent event) {
		if (!event.canBuild() || event.isCancelled()) return;

		Player player = event.getPlayer();
		Block block = event.getBlock();

		// If placing TNT in enemy territory, allow + auto-ignite!
		if ( event.getBlock().getType() == Material.TNT) {
			// Cancel the event so Factions doesn't process it (and spam player with no-placement msgs)
			//  and then process event MANUALLY
			event.setCancelled(true);
			// Using Factions objects, find out if block was placed in enemy territory
			FLocation loc = new FLocation(block.getLocation());
			Faction fac = Board.getFactionAt(loc);
			FPlayer me = FPlayers.i.get(event.getPlayer());
			Rel rel = me.getRelationTo(fac);
			// Determine if TNT should auto ignite
			if (rel.equals(Rel.ENEMY)) {
				// Bypassing Faction's land protection now...
				spawnTNT(player, block);
				plugin.logInfo("Let "+player.getDisplayName()+" place TNT in territory of "+fac.getTag());
			} else if (!rel.equals(Rel.MEMBER)) {
				// If the land isn't your own but isn't an enemy, you can't place it.
				player.sendMessage(ChatColor.RED+"You can only attack enemies with TNT!");
			}

		} else if (block.getType() == Material.IRON_DOOR_BLOCK) {
			// Prevent players from placing doors on bedrock
			// TODO test for vulnerabilities
			if (block.getRelative(0, -1, 0).getType() == Material.BEDROCK) {
				player.sendMessage(ChatColor.RED+"You can't place iron doors on bedrock!");
				event.setCancelled(true);
			}
		}

	}

	/**
	 * Receives all block explosion events with a list of blocks to be destroyed.
	 * Throws the list out and uses our own algorithm.
	 * 
	 * The area of explosion is a 3x3 cube.
	 * 
	 * The flow of block degradation:
	 * 	SOLID ORE~ > OBSIDIAN~ > LAPIS BLOCK > SMOOTH BRICK > COBBLESTONE (and everything else) > AIR
	 * 
	 *  Iron doors don't break unless the block beneath them does.
	 *  Stone/cobble has a 30% chance of turning to gravel if not directly adjacent with tnt
	 *  ~ only degrades when directly adjacent with tnt
	 * 
	 *  Block checking algorithm:
	 *   - degrade critical hit spots (directly adjacent to tnt faces)
	 *   - degrade edge blocks
	 *       only if at least one of two laterally adjacent blocks are air or are being destroyed
	 *   - degrade corner blocks
	 *       only if at least one of three adjacent blocks is or or being destroyed
	 * 
	 * Event priority is highest so other plugins have a chance to cancel the event.
	 */
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onEntityExplode(EntityExplodeEvent event) {
		if (event.isCancelled()) return;

		// Warn the faction (if any) about an explosion in their land
		Faction fac = Board.getFactionAt(new FLocation(event.getEntity().getLocation()));
		if (fac.isNormal()) {
			fac.msg(ChatColor.GRAY.toString()+ChatColor.ITALIC+"*An explosion rumbles from within your territory!*");
		}

		Block center = event.getLocation().getBlock();
		// Clear block destroy list, we'll do that manually.
		event.blockList().clear();
		// If explosion isn't from TNT, don't do any block damage
		// TODO Determine if this should always be the case
		if (!(event.getEntity() instanceof TNTPrimed)) return;

		// Go through all block coords and degrade/destroy
		int x,y,z;

		// Hit blocks adjacent to the TNT faces, AKA crit-hits
		for (int i = 0; i < critCoords.length; i++) {
			x = critCoords[i][0];
			y = critCoords[i][1];
			z = critCoords[i][2];

			if (degrade(center.getRelative(x,y,z), true)) {
				// If the hit block was destroyed, add it to the event
				//  blocklist so that it will be destroyed 'naturally'
				event.blockList().add(center.getRelative(x,y,z));
			}
		}
		// Hit blocks with one edge exposed (not corners)
		for (int i = 0; i < edgeCoords.length; i++) {
			x = edgeCoords[i][0];
			y = edgeCoords[i][1];
			z = edgeCoords[i][2];

			int[][] adj = edgeAdjacents(edgeCoords[i]);

			boolean degrade = event.blockList().contains(center.getRelative(adj[0][0],adj[0][1],adj[0][2]))
					|| event.blockList().contains(center.getRelative(adj[1][0],adj[1][1],adj[1][2]));

			if (degrade && degrade(center.getRelative(x,y,z), false)) {
				event.blockList().add(center.getRelative(x,y,z));
			}
		}

		// Hit corner blocks
		for (int i = 0; i < cornerCoords.length; i++) {
			x = cornerCoords[i][0];
			y = cornerCoords[i][1];
			z = cornerCoords[i][2];

			int[][] adj = cornerAdjacents(cornerCoords[i]);
			boolean degrade = event.blockList().contains(center.getRelative(adj[0][0],adj[0][1],adj[0][2]))
					|| event.blockList().contains(center.getRelative(adj[1][0],adj[1][1],adj[1][2]))
					|| event.blockList().contains(center.getRelative(adj[2][0],adj[2][1],adj[2][2]));

			if (degrade && degrade(center.getRelative(x,y,z), false)) {
				event.blockList().add(center.getRelative(x,y,z));
			}
		}

	}

	/**
	 * Spawns a TNTPrimed entity as if the player had placed and lit TNT by hand.
	 * @param player Who attempted to place the TNT
	 * @param block Where the TNT would have been placed
	 */
	private void spawnTNT(Player player, Block block) {
		//remove 1 TNT from player inventory because the event was cancelled
		ItemStack tntStack = player.getItemInHand();
		if (tntStack.getType() != Material.TNT) {
			// Sanity check, this code shouldn't really be reached.
			plugin.logWarning( "ERROR: Placing TNT in enemy territory, "+
					player.getDisplayName()+" didn't have TNT in hand!");
			return; //quit event (don't ignite TNT)
		}
		// Deduct 1 TNT from hand
		if (tntStack.getAmount() > 1) {
			tntStack.setAmount(tntStack.getAmount() -1);
		} else {
			// Can't set amount to 0 so set hand to null
			player.setItemInHand(null);
		}

		// Never actually place a block in enemy land... Just spawn a lit TNT entity
		final Location location = new Location(block.getWorld(),
				block.getX() + 0.5D,
				block.getY() + 0.5D,
				block.getZ() + 0.5D);
		final TNTPrimed tnt = block.getWorld().spawn(location, TNTPrimed.class);
		// If player is crouching, then TNT is 'sticky' and ignores effects of gravity
		if (player.isSneaking()) {
			// Keep track of the originally placed location for later
			stickyTNT.put(tnt, location);
			// Change fuse time of sticky TNT
			tnt.setFuseTicks(STICKY_FUSE);
			final Vector zeroVector = new Vector(0,0,0);
			// Schedule periodic delayed tasks to move the TNT back at its original location
			for (long delay = STICKY_TP_PERIOD; delay <= (STICKY_FUSE - STICKY_TP_PERIOD); delay += STICKY_TP_PERIOD) {
				plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() {
					@Override
					public void run() {
						tnt.teleport(location);
						tnt.setVelocity(zeroVector);
					}
				}, delay);
			}
		}
	}

	/**
	 * Degrades the given block. If block should instead be destroyed, make no change and return true.
	 * @param block to degrade
	 * @param critHit is the block adjacent to a tnt face?
	 * @return true if block should be destroyed, else false
	 */
	private boolean degrade(Block block, boolean critHit) {
		//type of current block
		Material type = block.getType();

		if (type == Material.DIAMOND_BLOCK || type == Material.GOLD_BLOCK
				|| type == Material.IRON_BLOCK) {
			if (critHit) {
				block.setType(Material.OBSIDIAN);
			}

		} else if (type == Material.OBSIDIAN) {
			if (critHit) {
				block.setType(Material.LAPIS_BLOCK);
			}

		} else if (type == Material.LAPIS_BLOCK) {
			block.setType(Material.SMOOTH_BRICK);

		} else if (type == Material.SMOOTH_BRICK) {
			block.setType(Material.COBBLESTONE);

		} else if ( !critHit && (type == Material.COBBLESTONE || type == Material.STONE)
				&& (Math.random() < 0.2) ) {
			block.setType(Material.GRAVEL);

			// every other type of block gets destroyed (except bedrock and iron door)
		} else if (type != Material.BEDROCK && type != Material.IRON_DOOR_BLOCK){
			return true;
		}
		return false;
	}

	// find adjacent blocks to an edge
	private int[][] edgeAdjacents(int[] coords) {
		int[][] adjacents = new int[2][3];

		// time for magic HA HA HA
		if (coords[0] == 0) {
			adjacents[0] = new int[] {0,coords[1],0};
			adjacents[1] = new int[] {0,0,coords[2]};
		} else if (coords[1] == 0) {
			adjacents[0] = new int[] {coords[0],0,0};
			adjacents[1] = new int[] {0,0,coords[2]};
		} else if (coords[2] == 0) {
			adjacents[0] = new int[] {coords[0],0,0};
			adjacents[1] = new int[] {0,coords[1],0};
		} else {
			return null;
		}

		return adjacents;
	}

	private int[][] cornerAdjacents(int[] coords) {
		int[][] adjacents = new int[3][3];
		adjacents[0] = new int[] {0, coords[1], coords[2]};
		adjacents[1] = new int[] {coords[0], 0, coords[2]};
		adjacents[2] = new int[] {coords[0], coords[1], 0};
		return adjacents;
	}

}
