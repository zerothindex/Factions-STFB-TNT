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
		// Nothing to do here.
	}

	@Override
	public void onEnable() {
		//find out if Factions exists and is enabled
		Plugin factionTester = this.getServer().getPluginManager().getPlugin("Factions");

		if (factionTester != null && factionTester.isEnabled()
				&& factionTester.getDescription().getVersion().compareTo("1.8") >= 0) {
			logInfo("Factions plugin found. Hijacking the plugin!");

			tntListener = new TNTListener(this);
			getServer().getPluginManager().registerEvents(tntListener, this);

			logInfo("Done!");
			return;
		}

		logInfo("Suitable Factions plugin not found. Get Factions v1.8 or higher!");
		getServer().getPluginManager().disablePlugin(this);
	}

	//Prints msg to console
	public void logInfo(String msg) {
		getLogger().info( msg );
	}

	public void logWarning(String errorMsg) {
		getLogger().warning( errorMsg );
	}

}














