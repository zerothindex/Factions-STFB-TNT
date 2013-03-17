package com.zachpotter.bukkit.factions.tnt;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TODO
 * @author Zachary Potter
 *
 */
public class FactionsTNT extends JavaPlugin{

	//Block and entity listeners
	private TNTListener tntListener;

	@Override
	public void onDisable() {
		logInfo("Disabling "+this.getDescription().getFullName()+".");
	}

	@Override
	public void onEnable() {
		logInfo("Enabling "+this.getDescription().getFullName()+"...");

		//find out if Factions exists and is enabled
		Plugin factionTester = this.getServer().getPluginManager().getPlugin("Factions");

		if (factionTester != null && factionTester.isEnabled()) {
			logInfo("Factions plugin found. Hijacking the plugin!");

			tntListener = new TNTListener(this);
			getServer().getPluginManager().registerEvents(tntListener, this);

			logInfo("Done!");

		} else {
			logInfo("Factions plugin not found. Aborting!");
		}

	}

	//Prints msg to console
	public void logInfo(String msg) {
		getLogger().info( "["+this.getDescription().getName()+"] " + msg );
	}

	public void logWarning(String errorMsg) {
		getLogger().warning( "["+this.getDescription().getName()+"] " + errorMsg );
	}

}














