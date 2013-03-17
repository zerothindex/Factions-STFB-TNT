package com.zachpotter.bukkit.factions.tnt;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.FPlayer;
import com.massivecraft.factions.FPlayers;
import com.massivecraft.factions.Faction;
import com.massivecraft.factions.struct.FFlag;
import com.massivecraft.factions.struct.Rel;

/**
 * TODO
 * @author Zachary Potter
 *
 */
public class TNTListener implements Listener {

	private final FactionsTNT plugin;

	private HashMap<TNTPrimed, Location> stickyTNT;

	public TNTListener(FactionsTNT plugin) {
		this.plugin = plugin;
		stickyTNT = new HashMap<TNTPrimed, Location>();

	}

	@EventHandler(priority = EventPriority.LOW)
	public void onBlockPlace(BlockPlaceEvent event) {
		if (!event.canBuild() || event.isCancelled()) return;

		//For Factions only:
		//If placing TNT in enemy territory, allow + auto-ignite!
		if ( event.getBlock().getType() == Material.TNT) {
			//Using Factions objects, find out if block was placed in enemy territory
			FLocation loc = new FLocation(event.getBlock().getLocation());
			Faction fac = Board.getFactionAt(loc);
			FPlayer me = FPlayers.i.get(event.getPlayer());
			Rel rel = me.getRelationTo(fac);
			//determine if TNT should auto ignite
			if (rel.isAtMost(Rel.ENEMY)) {
				//Bypassing Faction's land protection now...
				//Cancel the event so Factions doesn't process it (and spam player with no-placement msgs)
				//  and then process event MANUALLY
				event.setCancelled(true);
				//remove 1 TNT from player inventory because the event was cancelled
				ItemStack tntStack = event.getPlayer().getItemInHand();
				if (tntStack.getType() != Material.TNT) {
					//THIS SHOULD NEVER HAPPEN
					//Honestly I don't even know why I check...
					plugin.logWarning( "ERROR: Placing TNT in enemy territory, "+
							event.getPlayer().getDisplayName()+" didn't have TNT in hand!");
					return; //quit event (don't ignite TNT)
				}
				if (tntStack.getAmount() <= 1) {
					//Can't set amount to 0 for some reason...
					event.getPlayer().setItemInHand(null);
				} else {
					//Deduct 1 TNT from hand
					tntStack.setAmount(tntStack.getAmount() -1);
				}

				//Never actually place a block in enemy land... Just spawn a lit TNT entity
				Block block = event.getBlock();
				Location location = new Location(block.getWorld(), block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
				TNTPrimed tnt = block.getWorld().spawn(location, TNTPrimed.class);
				//keep track of this TNT if it's "sticky tnt"
				if (event.getPlayer().isSneaking()) {
					stickyTNT.put(tnt, location);
					tnt.setFuseTicks(20); //20 ticks = 1 second
					//schedule a delayed task to TP the tnt back to it's original location
					final TNTPrimed sticky = tnt;
					final Location stickyLoc = location;
					long period = 5;
					for (long delay = period; delay <= (20-period); delay += period) {
						plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() {
							@Override
							public void run() {
								sticky.teleport(stickyLoc);
								sticky.setVelocity(new Vector(0,0,0));
							}
						}, delay);
					}
					plugin.logInfo("Let "+event.getPlayer().getDisplayName()+" place sticky TNT in territory of "+fac.getTag());
					return;
				}
				plugin.logInfo("Let "+event.getPlayer().getDisplayName()+" place TNT in territory of "+fac.getTag());
			}
		} else if (event.getBlock().getType() == Material.IRON_DOOR_BLOCK) {
			if (event.getBlock().getRelative(0, -1, 0).getType() == Material.BEDROCK) {
				event.getPlayer().sendMessage(ChatColor.RED+"I bet you think you're clever.");
				event.getPlayer().setFireTicks(20);
				event.setCancelled(true);
				return;
			}
		}

	}

	@EventHandler(priority = EventPriority.NORMAL)
	public void onExplosionPrime(ExplosionPrimeEvent event)
	{
		if ( event.isCancelled()) return;

		Faction fac = Board.getFactionAt(new FLocation(event.getEntity().getLocation()));
		if (fac.isNormal()) {
			fac.msg(ChatColor.GRAY+"*An explosion rumbles from within your territory!*");
		}

		if (event.getEntity() instanceof TNTPrimed) {
			TNTPrimed tnt = (TNTPrimed)event.getEntity();
			if (stickyTNT.containsKey(tnt)) {
				tnt.teleport(stickyTNT.get(tnt)); //put TNT back at original placed location
				stickyTNT.remove(tnt);
				event.setRadius( 1.9f ); //sticky tnt radius
				return;
			}
		}

		event.setRadius( 2.1f ); //normal explosion

	}

	@EventHandler(priority = EventPriority.LOW)
	public void onEntityExplode(EntityExplodeEvent event) {

		/* The flow of block destruction:
		 * 
		 *  OBSIDIAN* > LAPIS BLOCK > SMOOTH BRICK > CRACKED BRICK > AIR
		 * 
		 *  Obsidian will be transformed to lapis block only if directly adjacent to tnt
		 */

		if (event.isCancelled()) return;
		// Stop creeper block damage in non-wilderness, but still do explosion
		// TODO
		if (event.getEntity() instanceof Creeper) {
			Faction fac = Board.getFactionAt(event.getEntity().getLocation());
			if (fac != null && !fac.getFlag(FFlag.EXPLOSIONS)) {
				event.blockList().clear();
				return;
			}
		}
		//Get a lit of all blocks to be destroyed
		List<Block> blist = event.blockList();
		Iterator<Block> iter = blist.iterator();
		while (iter.hasNext()) {
			Block b = iter.next();
			boolean dontblow = false;
			if (b.getType() == Material.SMOOTH_BRICK) {
				//Set smooth brick or mossy smooth brick to cobble
				if (b.getData() == (byte)0 || b.getData() == (byte)1) {
					b.setType(Material.COBBLESTONE);
					dontblow = true;
				}
			} else if (b.getType() == Material.IRON_DOOR_BLOCK) {
				dontblow = true;
				//Check for obsidian beneath this door-block or the door-block beneath it
				/*
				if (b.getRelative(0, -1, 0).getType() == Material.LAPIS_BLOCK) {
					b.getRelative(0, -1, 0).setType(Material.SMOOTH_BRICK);
				} else if (b.getRelative(0, -1, 0).getType() == Material.OBSIDIAN) {
					b.getRelative(0, -1, 0).setType(Material.LAPIS_BLOCK);
				} else if (b.getRelative(0, -2, 0).getType() == Material.LAPIS_BLOCK) {
					b.getRelative(0, -2, 0).setType(Material.SMOOTH_BRICK);
				} else if (b.getRelative(0, -2, 0).getType() == Material.OBSIDIAN) {
					b.getRelative(0, -2, 0).setType(Material.LAPIS_BLOCK);
				}*/

			} else if (b.getType() == Material.LAPIS_BLOCK) {
				dontblow = true;
				if (b.getLocation().distance(event.getLocation().getBlock().getLocation()) <= 2.0) {
					b.setType(Material.SMOOTH_BRICK);
				}
			}
			if (dontblow) {
				iter.remove();
			}
		}
		//do obsidian directly adjacent blockssearch (not in blockList)
		Block center = event.getLocation().getBlock();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if ( (Math.abs(x)+Math.abs(y)+Math.abs(z) == 1)
							&& center.getRelative(x,y,z).getType() == Material.OBSIDIAN) {
						center.getRelative(x,y,z).setType(Material.LAPIS_BLOCK);
					}
				}
			}
		}
	}

}
